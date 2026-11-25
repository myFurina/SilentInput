package com.silentinput.app.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure JVM binary AEAD codec for the low-latency UDP mouse datagram channel.
 * Matches `pc-daemon-rs/crates/silentinput-daemon/src/udp_mouse.rs`.
 */
object UdpMouseCodec {
    const val MAGIC_0: Byte = 0x53 // 'S'
    const val MAGIC_1: Byte = 0x49 // 'I'
    const val VERSION: Byte = 0x01
    const val PKT_PROBE: Byte = 0x01
    const val PKT_PROBE_ACK: Byte = 0x02
    const val PKT_MOUSE_MOVE: Byte = 0x03

    const val HEADER_LEN: Int = 20
    const val GCM_TAG_BITS: Int = 128
    const val GCM_TAG_LEN: Int = 16
    const val NONCE_LEN: Int = 12

    const val DIR_CLIENT_TO_SERVER: Int = 1
    const val DIR_SERVER_TO_CLIENT: Int = 2

    fun parseSessionIdHex(hex: String): Long? {
        val trimmed = hex.trim()
        if (trimmed.isEmpty() || trimmed.length > 16) return null
        return try {
            val id = java.lang.Long.parseUnsignedLong(trimmed, 16)
            if (id == 0L) null else id
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun decodeAes256Key(base64Key: String): SecretKeySpec? {
        val trimmed = base64Key.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val bytes = Base64.getDecoder().decode(trimmed)
            if (bytes.size != 32) return null
            SecretKeySpec(bytes, "AES")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encodeProbePayload(tag: Int): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(tag)
        return buf.array()
    }

    fun encodeMovePayload(dx: Int, dy: Int, wheel: Int = 0, flags: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(dx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        buf.putShort(dy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        buf.putShort(wheel.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        buf.putShort((flags and 0xFFFF).toShort())
        return buf.array()
    }

    fun makeNonce(dir: Int, seq: Long): ByteArray {
        val buf = ByteBuffer.allocate(NONCE_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(dir)
        buf.putLong(seq)
        return buf.array()
    }

    fun sealPacket(
        secretKey: SecretKeySpec,
        pktType: Byte,
        sessionId: Long,
        dir: Int,
        seq: Long,
        plaintext: ByteArray,
        cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    ): ByteArray {
        val header = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC_0)
            .put(MAGIC_1)
            .put(VERSION)
            .put(pktType)
            .putLong(sessionId)
            .putLong(seq)
            .array()

        val nonce = makeNonce(dir, seq)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(header)
        val ctAndTag = cipher.doFinal(plaintext)

        val out = ByteArray(HEADER_LEN + ctAndTag.size)
        System.arraycopy(header, 0, out, 0, HEADER_LEN)
        System.arraycopy(ctAndTag, 0, out, HEADER_LEN, ctAndTag.size)
        return out
    }

    fun openPacket(
        secretKey: SecretKeySpec,
        expectedSessionId: Long,
        expectedDir: Int,
        expectedPktType: Byte,
        packet: ByteArray,
        length: Int = packet.size,
        cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    ): ByteArray? {
        if (length < HEADER_LEN + GCM_TAG_LEN || length > packet.size) return null
        if (packet[0] != MAGIC_0 || packet[1] != MAGIC_1 || packet[2] != VERSION) return null
        if (packet[3] != expectedPktType) return null

        val headerBuf = ByteBuffer.wrap(packet, 0, HEADER_LEN).order(ByteOrder.BIG_ENDIAN)
        headerBuf.position(4)
        val sessionId = headerBuf.long
        val seq = headerBuf.long
        if (sessionId != expectedSessionId || seq == 0L) return null

        val nonce = makeNonce(expectedDir, seq)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(packet, 0, HEADER_LEN)
            cipher.doFinal(packet, HEADER_LEN, length - HEADER_LEN)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Non-blocking, single-worker UDP transport for high-frequency relative mouse motion.
 * Probes reachability on startup so blocked firewalls fall back transparently to WebSocket.
 */
class UdpMouseChannel(
    private val host: String,
    private val port: Int,
    private val sessionId: Long,
    private val secretKey: SecretKeySpec,
    private val onReadyChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "SilentInputUDP"
        private const val PROBE_ATTEMPTS = 3
        private const val PROBE_TIMEOUT_MS = 320
    }

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    private var closed = false

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var workerThread: Thread? = null

    private val nextSeq = AtomicLong(0L)
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)

    fun start() {
        val thread = Thread({ runWorker() }, "SilentInput-UDP-Mouse").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY.coerceAtMost(Thread.NORM_PRIORITY + 2)
        }
        workerThread = thread
        thread.start()
    }

    /**
     * Lock-free delta accumulation safe to call directly on the Android UI thread.
     */
    fun enqueueMove(dx: Int, dy: Int) {
        if (closed || !isReady || (dx == 0 && dy == 0)) return
        if (dx != 0) pendingDx.addAndGet(dx)
        if (dy != 0) pendingDy.addAndGet(dy)
        LockSupport.unpark(workerThread)
    }

    fun close() {
        if (closed) return
        closed = true
        isReady = false
        onReadyChanged(false)
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        LockSupport.unpark(workerThread)
    }

    private fun runWorker() {
        val localSocket: DatagramSocket
        val targetAddr: InetAddress
        try {
            targetAddr = InetAddress.getByName(host)
            localSocket = DatagramSocket()
            localSocket.soTimeout = PROBE_TIMEOUT_MS
            socket = localSocket
        } catch (e: Exception) {
            Log.w(TAG, "UDP socket init failed for $host:$port: ${e.message}")
            close()
            return
        }

        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding")
        } catch (e: Exception) {
            Log.w(TAG, "AES-GCM unavailable: ${e.message}")
            close()
            return
        }

        // Phase 1: Reachability probe (detect firewall drops and fall back cleanly to TCP)
        val probeTag = SecureRandom().nextInt()
        val probePayload = UdpMouseCodec.encodeProbePayload(probeTag)
        val recvBuf = ByteArray(128)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
        var probeOk = false

        for (attempt in 1..PROBE_ATTEMPTS) {
            if (closed) break
            try {
                val seq = nextSeq.incrementAndGet()
                val wire = UdpMouseCodec.sealPacket(
                    secretKey = secretKey,
                    pktType = UdpMouseCodec.PKT_PROBE,
                    sessionId = sessionId,
                    dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                    seq = seq,
                    plaintext = probePayload,
                    cipher = cipher
                )
                localSocket.send(DatagramPacket(wire, wire.size, targetAddr, port))

                val deadlineMs = System.currentTimeMillis() + PROBE_TIMEOUT_MS
                while (!closed) {
                    val remaining = (deadlineMs - System.currentTimeMillis()).toInt()
                    if (remaining <= 0) break
                    localSocket.soTimeout = remaining
                    try {
                        recvPacket.setData(recvBuf, 0, recvBuf.size)
                        localSocket.receive(recvPacket)
                        if (recvPacket.address != targetAddr || recvPacket.port != port) {
                            continue
                        }
                        val pt = UdpMouseCodec.openPacket(
                            secretKey = secretKey,
                            expectedSessionId = sessionId,
                            expectedDir = UdpMouseCodec.DIR_SERVER_TO_CLIENT,
                            expectedPktType = UdpMouseCodec.PKT_PROBE_ACK,
                            packet = recvBuf,
                            length = recvPacket.length,
                            cipher = cipher
                        )
                        if (pt != null && pt.size >= 4) {
                            val echoedTag = ByteBuffer.wrap(pt, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                            if (echoedTag == probeTag) {
                                probeOk = true
                                break
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                if (probeOk) break
            } catch (e: Exception) {
                Log.d(TAG, "UDP probe attempt $attempt failed: ${e.message}")
            }
        }

        if (!probeOk || closed) {
            if (!closed) {
                Log.i(TAG, "UDP probe timed out for $host:$port; keeping WebSocket TCP fallback")
            }
            close()
            return
        }

        isReady = true
        onReadyChanged(true)
        Log.i(TAG, "UDP low-latency mouse channel active for $host:$port")

        // Phase 2: Dedicated send loop
        val sendBuf = ByteArray(UdpMouseCodec.HEADER_LEN + 8 + UdpMouseCodec.GCM_TAG_LEN)
        val sendPacket = DatagramPacket(sendBuf, sendBuf.size, targetAddr, port)

        while (!closed) {
            val dx = pendingDx.getAndSet(0)
            val dy = pendingDy.getAndSet(0)
            if (dx == 0 && dy == 0) {
                LockSupport.parkNanos(200_000_000L)
                continue
            }

            try {
                val seq = nextSeq.incrementAndGet()
                val payload = UdpMouseCodec.encodeMovePayload(dx, dy)
                val wire = UdpMouseCodec.sealPacket(
                    secretKey = secretKey,
                    pktType = UdpMouseCodec.PKT_MOUSE_MOVE,
                    sessionId = sessionId,
                    dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                    seq = seq,
                    plaintext = payload,
                    cipher = cipher
                )
                sendPacket.setData(wire, 0, wire.size)
                localSocket.send(sendPacket)
            } catch (e: Exception) {
                if (!closed) {
                    Log.w(TAG, "UDP send error; falling back to WebSocket: ${e.message}")
                }
                break
            }
        }

        close()
    }
}
