package com.silentinput.app.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
    const val MOVE_PAYLOAD_LEN: Int = 8
    const val GCM_TAG_BITS: Int = 128
    const val GCM_TAG_LEN: Int = 16
    const val NONCE_LEN: Int = 12
    const val MOVE_WIRE_LEN: Int = HEADER_LEN + MOVE_PAYLOAD_LEN + GCM_TAG_LEN

    const val DIR_CLIENT_TO_SERVER: Int = 1
    const val DIR_SERVER_TO_CLIENT: Int = 2
    const val FLAG_HI_RES_WHEEL: Int = 0x0001

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
        val out = ByteArray(4)
        putIntBE(out, 0, tag)
        return out
    }

    fun encodeMovePayload(dx: Int, dy: Int, wheel: Int = 0, flags: Int = 0): ByteArray {
        val out = ByteArray(MOVE_PAYLOAD_LEN)
        writeMovePayloadInto(out, dx, dy, wheel, flags)
        return out
    }

    fun writeMovePayloadInto(out: ByteArray, dx: Int, dy: Int, wheel: Int = 0, flags: Int = 0) {
        putShortBE(out, 0, dx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        putShortBE(out, 2, dy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        putShortBE(out, 4, wheel.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        putShortBE(out, 6, flags and 0xFFFF)
    }

    fun makeNonce(dir: Int, seq: Long): ByteArray {
        val out = ByteArray(NONCE_LEN)
        writeNonceInto(out, dir, seq)
        return out
    }

    fun writeNonceInto(out: ByteArray, dir: Int, seq: Long) {
        putIntBE(out, 0, dir)
        putLongBE(out, 4, seq)
    }

    fun writeHeaderInto(out: ByteArray, pktType: Byte, sessionId: Long, seq: Long) {
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = VERSION
        out[3] = pktType
        putLongBE(out, 4, sessionId)
        putLongBE(out, 12, seq)
    }

    /**
     * Zero-allocation AEAD seal for 8-byte mouse move/scroll packets using caller-owned scratch arrays.
     * Returns total packet bytes written to [wireOut] ([MOVE_WIRE_LEN] = 44 bytes).
     */
    fun sealMoveInto(
        secretKey: SecretKeySpec,
        sessionId: Long,
        dir: Int,
        seq: Long,
        dx: Int,
        dy: Int,
        wheel: Int = 0,
        flags: Int = 0,
        payloadScratch: ByteArray,
        nonceScratch: ByteArray,
        wireOut: ByteArray,
        cipher: Cipher
    ): Int {
        writeHeaderInto(wireOut, PKT_MOUSE_MOVE, sessionId, seq)
        writeNonceInto(nonceScratch, dir, seq)
        writeMovePayloadInto(payloadScratch, dx, dy, wheel, flags)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonceScratch))
        cipher.updateAAD(wireOut, 0, HEADER_LEN)
        val written = cipher.doFinal(payloadScratch, 0, MOVE_PAYLOAD_LEN, wireOut, HEADER_LEN)
        return HEADER_LEN + written
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
        val header = ByteArray(HEADER_LEN)
        writeHeaderInto(header, pktType, sessionId, seq)

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

        val sessionId = getLongBE(packet, 4)
        val seq = getLongBE(packet, 12)
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

    private fun putShortBE(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value ushr 8).toByte()
        buf[off + 1] = value.toByte()
    }

    private fun putIntBE(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    private fun putLongBE(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 56).toByte()
        buf[off + 1] = (value ushr 48).toByte()
        buf[off + 2] = (value ushr 40).toByte()
        buf[off + 3] = (value ushr 32).toByte()
        buf[off + 4] = (value ushr 24).toByte()
        buf[off + 5] = (value ushr 16).toByte()
        buf[off + 6] = (value ushr 8).toByte()
        buf[off + 7] = value.toByte()
    }

    private fun getLongBE(buf: ByteArray, off: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (buf[off + i].toLong() and 0xFFL)
        }
        return result
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
    private val pendingWheel = AtomicInteger(0)
    private val pendingWheelIsHiRes = AtomicBoolean(false)

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

    /**
     * Lock-free two-finger scroll accumulation coalesced into the UDP datagram loop.
     */
    fun enqueueScroll(wheel: Int, hiRes: Boolean = false) {
        if (closed || !isReady || wheel == 0) return
        if (pendingWheelIsHiRes.getAndSet(hiRes) != hiRes) {
            pendingWheel.set(0)
        }
        pendingWheel.addAndGet(wheel)
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
            localSocket.connect(targetAddr, port)
            try {
                // DSCP EF (0xB8) maps to WMM AC_VO/AC_VI low-latency queue on Wi-Fi; fall back to IPTOS_LOWDELAY.
                localSocket.trafficClass = 0xB8
            } catch (_: Exception) {
                try {
                    localSocket.trafficClass = 0x10
                } catch (_: Exception) {}
            }
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

        // Phase 1: Reachability probe (detect firewall drops/ICMP reject and fall back cleanly to TCP)
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
                localSocket.send(DatagramPacket(wire, wire.size))

                val deadlineMs = System.currentTimeMillis() + PROBE_TIMEOUT_MS
                while (!closed) {
                    val remaining = (deadlineMs - System.currentTimeMillis()).toInt()
                    if (remaining <= 0) break
                    localSocket.soTimeout = remaining
                    try {
                        recvPacket.setData(recvBuf, 0, recvBuf.size)
                        localSocket.receive(recvPacket)
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
                            val echoedTag = ((pt[0].toInt() and 0xFF) shl 24) or
                                ((pt[1].toInt() and 0xFF) shl 16) or
                                ((pt[2].toInt() and 0xFF) shl 8) or
                                (pt[3].toInt() and 0xFF)
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
            } catch (e: PortUnreachableException) {
                Log.i(TAG, "UDP port unreachable for $host:$port; falling back immediately to WebSocket")
                break
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

        // Phase 2: Dedicated zero-allocation send loop over connected UDP socket
        val payloadScratch = ByteArray(UdpMouseCodec.MOVE_PAYLOAD_LEN)
        val nonceScratch = ByteArray(UdpMouseCodec.NONCE_LEN)
        val wireBuf = ByteArray(UdpMouseCodec.MOVE_WIRE_LEN)
        val sendPacket = DatagramPacket(wireBuf, wireBuf.size)

        while (!closed) {
            val dx = pendingDx.getAndSet(0)
            val dy = pendingDy.getAndSet(0)
            val wheel = pendingWheel.getAndSet(0)
            if (dx == 0 && dy == 0 && wheel == 0) {
                LockSupport.parkNanos(200_000_000L)
                continue
            }

            val flags = if (wheel != 0 && pendingWheelIsHiRes.get()) {
                UdpMouseCodec.FLAG_HI_RES_WHEEL
            } else {
                0
            }

            try {
                val seq = nextSeq.incrementAndGet()
                val totalLen = UdpMouseCodec.sealMoveInto(
                    secretKey = secretKey,
                    sessionId = sessionId,
                    dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                    seq = seq,
                    dx = dx,
                    dy = dy,
                    wheel = wheel,
                    flags = flags,
                    payloadScratch = payloadScratch,
                    nonceScratch = nonceScratch,
                    wireOut = wireBuf,
                    cipher = cipher
                )
                sendPacket.setLength(totalLen)
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
