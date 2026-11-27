package com.silentinput.app.network

import android.util.Log
import com.silentinput.app.model.TouchpadContact
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
    const val PKT_TOUCHPAD: Byte = 0x04

    const val HEADER_LEN: Int = 20
    const val MOVE_PAYLOAD_LEN: Int = 8
    const val MOVE_FEC_PAYLOAD_LEN: Int = MOVE_PAYLOAD_LEN * 3
    const val MAX_TOUCHPAD_CONTACTS: Int = 5
    const val TOUCHPAD_MAX_PAYLOAD_LEN: Int = 1 + MAX_TOUCHPAD_CONTACTS * 6
    const val GCM_TAG_BITS: Int = 128
    const val GCM_TAG_LEN: Int = 16
    const val NONCE_LEN: Int = 12
    const val MOVE_WIRE_LEN: Int = HEADER_LEN + MOVE_PAYLOAD_LEN + GCM_TAG_LEN
    const val MOVE_FEC_WIRE_LEN: Int = HEADER_LEN + MOVE_FEC_PAYLOAD_LEN + GCM_TAG_LEN
    const val TOUCHPAD_MAX_WIRE_LEN: Int = HEADER_LEN + TOUCHPAD_MAX_PAYLOAD_LEN + GCM_TAG_LEN

    const val DIR_CLIENT_TO_SERVER: Int = 1
    const val DIR_SERVER_TO_CLIENT: Int = 2
    const val FLAG_HI_RES_WHEEL: Int = 0x0001
    const val FLAG_FEC_PREV1: Int = 0x0002
    const val FLAG_FEC_PREV2: Int = 0x0004
    const val FLAG_HWHEEL: Int = 0x0008
    private const val FLAG_FRAME_MASK: Int = FLAG_HI_RES_WHEEL or FLAG_HWHEEL

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

    fun writeMovePayloadInto(
        out: ByteArray,
        dx: Int,
        dy: Int,
        wheel: Int = 0,
        flags: Int = 0,
        offset: Int = 0
    ) {
        putShortBE(out, offset, dx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        putShortBE(out, offset + 2, dy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        putShortBE(out, offset + 4, wheel.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        putShortBE(out, offset + 6, flags and 0xFFFF)
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
        return sealMoveWithFecInto(
            secretKey = secretKey,
            sessionId = sessionId,
            dir = dir,
            seq = seq,
            dx = dx,
            dy = dy,
            wheel = wheel,
            flags = flags,
            hasPrev1 = false,
            prev1Dx = 0,
            prev1Dy = 0,
            prev1Wheel = 0,
            prev1Flags = 0,
            hasPrev2 = false,
            prev2Dx = 0,
            prev2Dy = 0,
            prev2Wheel = 0,
            prev2Flags = 0,
            payloadScratch = payloadScratch,
            nonceScratch = nonceScratch,
            wireOut = wireOut,
            cipher = cipher
        )
    }

    /**
     * Zero-allocation AEAD seal for mouse moves with up to 2 redundant prior frames (`seq-1`, `seq-2`)
     * so single or double UDP drops on Wi-Fi recover instantly without retransmit stalls.
     */
    fun sealMoveWithFecInto(
        secretKey: SecretKeySpec,
        sessionId: Long,
        dir: Int,
        seq: Long,
        dx: Int,
        dy: Int,
        wheel: Int = 0,
        flags: Int = 0,
        hasPrev1: Boolean = false,
        prev1Dx: Int = 0,
        prev1Dy: Int = 0,
        prev1Wheel: Int = 0,
        prev1Flags: Int = 0,
        hasPrev2: Boolean = false,
        prev2Dx: Int = 0,
        prev2Dy: Int = 0,
        prev2Wheel: Int = 0,
        prev2Flags: Int = 0,
        payloadScratch: ByteArray,
        nonceScratch: ByteArray,
        wireOut: ByteArray,
        cipher: Cipher
    ): Int {
        var effectiveFlags = flags and FLAG_FRAME_MASK
        var payloadLen = MOVE_PAYLOAD_LEN
        if (hasPrev1) {
            effectiveFlags = effectiveFlags or FLAG_FEC_PREV1
            writeMovePayloadInto(payloadScratch, prev1Dx, prev1Dy, prev1Wheel, prev1Flags and FLAG_FRAME_MASK, offset = MOVE_PAYLOAD_LEN)
            payloadLen = MOVE_PAYLOAD_LEN * 2
            if (hasPrev2) {
                effectiveFlags = effectiveFlags or FLAG_FEC_PREV2
                writeMovePayloadInto(payloadScratch, prev2Dx, prev2Dy, prev2Wheel, prev2Flags and FLAG_FRAME_MASK, offset = MOVE_PAYLOAD_LEN * 2)
                payloadLen = MOVE_FEC_PAYLOAD_LEN
            }
        }
        writeHeaderInto(wireOut, PKT_MOUSE_MOVE, sessionId, seq)
        writeNonceInto(nonceScratch, dir, seq)
        writeMovePayloadInto(payloadScratch, dx, dy, wheel, effectiveFlags, offset = 0)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonceScratch))
        cipher.updateAAD(wireOut, 0, HEADER_LEN)
        val written = cipher.doFinal(payloadScratch, 0, payloadLen, wireOut, HEADER_LEN)
        return HEADER_LEN + written
    }

    fun writeTouchpadPayloadInto(out: ByteArray, contacts: List<TouchpadContact>): Int {
        val count = contacts.size.coerceAtMost(MAX_TOUCHPAD_CONTACTS)
        out[0] = count.toByte()
        for (i in 0 until count) {
            val c = contacts[i]
            val off = 1 + i * 6
            putShortBE(out, off, c.pointerId and 0xFFFF)
            putShortBE(out, off + 2, c.x.coerceIn(0, 0xFFFF))
            putShortBE(out, off + 4, c.y.coerceIn(0, 0xFFFF))
        }
        return 1 + count * 6
    }

    /**
     * Zero-allocation AEAD seal for native multi-touch snapshots (`PKT_TOUCHPAD = 0x04`).
     */
    fun sealTouchpadInto(
        secretKey: SecretKeySpec,
        sessionId: Long,
        dir: Int,
        seq: Long,
        contacts: List<TouchpadContact>,
        payloadScratch: ByteArray,
        nonceScratch: ByteArray,
        wireOut: ByteArray,
        cipher: Cipher
    ): Int {
        val payloadLen = writeTouchpadPayloadInto(payloadScratch, contacts)
        writeHeaderInto(wireOut, PKT_TOUCHPAD, sessionId, seq)
        writeNonceInto(nonceScratch, dir, seq)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonceScratch))
        cipher.updateAAD(wireOut, 0, HEADER_LEN)
        val written = cipher.doFinal(payloadScratch, 0, payloadLen, wireOut, HEADER_LEN)
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

data class UdpMoveBarrier(
    val pendingDx: Int,
    val pendingDy: Int,
    val lastSentSeq: Long
)

/**
 * Non-blocking, single-worker UDP transport for high-frequency relative mouse motion.
 * Probes reachability on startup so blocked firewalls fall back transparently to WebSocket.
 */
class UdpMouseChannel(
    private val host: String,
    private val port: Int,
    private val sessionId: Long,
    private val secretKey: SecretKeySpec,
    private val seqCounter: AtomicLong = AtomicLong(0L),
    private val onReadyChanged: (Boolean) -> Unit = {},
    private val onUnexpectedClose: () -> Unit = {},
    private val keepaliveIntervalMs: Long = 15_000L,
    private val keepaliveTimeoutMs: Long = 2_000L
) {
    companion object {
        private const val TAG = "SilentInputUDP"
        private const val PROBE_ATTEMPTS = 3
        private const val PROBE_TIMEOUT_MS = 320
        private const val FEC_MAX_AGE_NS = 100_000_000L
        private const val MAX_MISSED_KEEPALIVES = 2
        private const val LIFTOFF_REPEAT_DELAY_NS = 15_000_000L
        private const val MAX_PENDING_TOUCH_TRANSITIONS = 8
    }

    @Volatile
    var isReady: Boolean = false
        private set

    private val lifecycleLock = Any()

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    private val explicitClose = AtomicBoolean(false)

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var workerThread: Thread? = null

    private val pendingLock = Any()
    private var pendingDx: Int = 0
    private var pendingDy: Int = 0
    private var pendingWheel: Int = 0
    private var pendingHWheel: Int = 0
    private var pendingWheelIsHiRes: Boolean = false
    private var latestMoveSeq: Long = 0L
    private val pendingTouchTransitions = ArrayDeque<List<TouchpadContact>>()
    private var pendingTouchMove: List<TouchpadContact>? = null

    fun start() {
        val thread = synchronized(lifecycleLock) {
            if (closed || started) return
            started = true
            Thread({ runWorker() }, "SilentInput-UDP-Mouse").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY.coerceAtMost(Thread.NORM_PRIORITY + 2)
            }.also { workerThread = it }
        }
        thread.start()
    }

    /**
     * Delta accumulation safe to call directly on the Android UI thread.
     */
    fun enqueueMove(dx: Int, dy: Int) {
        if (closed || !isReady || (dx == 0 && dy == 0)) return
        synchronized(pendingLock) {
            pendingDx += dx
            pendingDy += dy
        }
        LockSupport.unpark(workerThread)
    }

    /**
     * Two-finger scroll accumulation coalesced into the UDP datagram loop.
     */
    fun enqueueScroll(wheel: Int, hiRes: Boolean = false, hwheel: Int = 0) {
        if (closed || !isReady || (wheel == 0 && hwheel == 0)) return
        synchronized(pendingLock) {
            if (pendingWheelIsHiRes != hiRes) {
                pendingWheel = 0
                pendingHWheel = 0
                pendingWheelIsHiRes = hiRes
            }
            pendingWheel += wheel
            pendingHWheel += hwheel
        }
        LockSupport.unpark(workerThread)
    }

    /**
     * Multi-touch touchpad snapshot transmission: transitions (pointer down/up/liftoff) are
     * preserved in order while consecutive moves coalesce to the newest coordinate snapshot.
     */
    fun enqueueTouchpadFrame(contacts: List<TouchpadContact>, isTransition: Boolean) {
        if (closed || !isReady) return
        synchronized(pendingLock) {
            if (!isTransition) {
                pendingTouchMove = contacts
            } else {
                val staleMove = pendingTouchMove
                pendingTouchMove = null
                if (staleMove != null && pendingTouchTransitions.size < MAX_PENDING_TOUCH_TRANSITIONS) {
                    pendingTouchTransitions.addLast(staleMove)
                }
                while (pendingTouchTransitions.size >= MAX_PENDING_TOUCH_TRANSITIONS) {
                    val dropIdx = pendingTouchTransitions.indexOfFirst { it.isNotEmpty() }
                    if (dropIdx >= 0) {
                        pendingTouchTransitions.removeAt(dropIdx)
                    } else {
                        pendingTouchTransitions.removeFirst()
                    }
                }
                pendingTouchTransitions.addLast(contacts)
            }
        }
        LockSupport.unpark(workerThread)
    }

    /**
     * Atomically drain any unsent relative move delta and return the sequence number of the
     * latest move datagram sent, allowing subsequent WebSocket clicks to preserve causal order.
     */
    fun drainUnsentMoveAndLastSeq(): UdpMoveBarrier {
        return synchronized(pendingLock) {
            val dx = pendingDx
            val dy = pendingDy
            pendingDx = 0
            pendingDy = 0
            UdpMoveBarrier(pendingDx = dx, pendingDy = dy, lastSentSeq = latestMoveSeq)
        }
    }

    fun close() {
        explicitClose.set(true)
        closeInternal(notifyUnexpected = false)
    }

    private fun closeInternal(notifyUnexpected: Boolean) {
        val sockToClose: DatagramSocket?
        val threadToUnpark: Thread?
        val shouldNotify: Boolean
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            isReady = false
            sockToClose = socket
            socket = null
            threadToUnpark = workerThread
            shouldNotify = notifyUnexpected && !explicitClose.get()
        }
        onReadyChanged(false)
        try {
            sockToClose?.close()
        } catch (_: Exception) {}
        LockSupport.unpark(threadToUnpark)
        if (shouldNotify) {
            onUnexpectedClose()
        }
    }

    private fun runWorker() {
        var localSocket: DatagramSocket? = null
        try {
            val targetAddr: InetAddress
            try {
                targetAddr = InetAddress.getByName(host)
                val createdSocket = DatagramSocket()
                createdSocket.connect(targetAddr, port)
                try {
                    // DSCP AF41 (0x88) maps to WMM AC_VI without AC_VO A-MPDU/ACM penalties; fall back to IPTOS_LOWDELAY.
                    createdSocket.trafficClass = 0x88
                } catch (_: Exception) {
                    try {
                        createdSocket.trafficClass = 0x10
                    } catch (_: Exception) {}
                }
                createdSocket.soTimeout = PROBE_TIMEOUT_MS
                val accepted = synchronized(lifecycleLock) {
                    localSocket = createdSocket
                    if (closed) {
                        false
                    } else {
                        socket = createdSocket
                        true
                    }
                }
                if (!accepted) {
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP socket init failed for $host:$port: ${e.message}")
                return
            }
            val activeSocket = localSocket ?: return

            val cipher = try {
                Cipher.getInstance("AES/GCM/NoPadding")
            } catch (e: Exception) {
                Log.w(TAG, "AES-GCM unavailable: ${e.message}")
                return
            }

            // Phase 1: Reachability probe (detect firewall drops/ICMP reject and fall back cleanly to TCP)
            val keepaliveRandom = SecureRandom()
            val probeTag = keepaliveRandom.nextInt()
            val probePayload = UdpMouseCodec.encodeProbePayload(probeTag)
            val recvBuf = ByteArray(128)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            var probeOk = false

            for (attempt in 1..PROBE_ATTEMPTS) {
                if (closed) break
                try {
                    val seq = seqCounter.incrementAndGet()
                    val wire = UdpMouseCodec.sealPacket(
                        secretKey = secretKey,
                        pktType = UdpMouseCodec.PKT_PROBE,
                        sessionId = sessionId,
                        dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                        seq = seq,
                        plaintext = probePayload,
                        cipher = cipher
                    )
                    activeSocket.send(DatagramPacket(wire, wire.size))

                    val deadlineMs = System.currentTimeMillis() + PROBE_TIMEOUT_MS
                    while (!closed) {
                        val remaining = (deadlineMs - System.currentTimeMillis()).toInt()
                        if (remaining <= 0) break
                        activeSocket.soTimeout = remaining
                        try {
                            recvPacket.setData(recvBuf, 0, recvBuf.size)
                            activeSocket.receive(recvPacket)
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

            if (!probeOk) {
                if (!closed) {
                    Log.i(TAG, "UDP probe timed out for $host:$port; keeping WebSocket TCP fallback")
                }
                return
            }

            val activated = synchronized(lifecycleLock) {
                if (closed) {
                    false
                } else {
                    isReady = true
                    true
                }
            }
            if (!activated) return
            onReadyChanged(true)
            Log.i(TAG, "UDP low-latency mouse channel active for $host:$port")

            // Phase 2: Dedicated zero-allocation send loop over connected UDP socket with bounded-age 2-frame FEC tail + blackhole probe
            val payloadScratch = ByteArray(maxOf(UdpMouseCodec.MOVE_FEC_PAYLOAD_LEN, UdpMouseCodec.TOUCHPAD_MAX_PAYLOAD_LEN))
            val nonceScratch = ByteArray(UdpMouseCodec.NONCE_LEN)
            val wireBuf = ByteArray(maxOf(UdpMouseCodec.MOVE_FEC_WIRE_LEN, UdpMouseCodec.TOUCHPAD_MAX_WIRE_LEN))
            val sendPacket = DatagramPacket(wireBuf, wireBuf.size)

            val keepaliveIntervalNs = keepaliveIntervalMs.coerceAtLeast(20L) * 1_000_000L
            val keepaliveTimeoutNs = keepaliveTimeoutMs.coerceAtLeast(20L) * 1_000_000L
            val idleParkNs = if (keepaliveIntervalMs < 200L) {
                (keepaliveIntervalMs * 1_000_000L).coerceIn(5_000_000L, 50_000_000L)
            } else {
                200_000_000L
            }

            var hasPrev1 = false
            var prev1Dx = 0
            var prev1Dy = 0
            var prev1Wheel = 0
            var prev1Flags = 0
            var hasPrev2 = false
            var prev2Dx = 0
            var prev2Dy = 0
            var prev2Wheel = 0
            var prev2Flags = 0
            var lastSentNs = 0L

            var lastAckNs = System.nanoTime()
            var lastHadActivity = false
            var keepaliveInFlight = false
            var keepaliveSentNs = 0L
            var keepaliveTag = 0
            var missedKeepalives = 0
            var liftoffRepeatDeadlineNs = 0L

            while (!closed) {
                val nowLoopNs = System.nanoTime()
                if (keepaliveInFlight) {
                    var acked = false
                    try {
                        activeSocket.soTimeout = 1
                        while (!closed) {
                            recvPacket.setData(recvBuf, 0, recvBuf.size)
                            activeSocket.receive(recvPacket)
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
                                if (echoedTag == keepaliveTag) {
                                    acked = true
                                    break
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // No pending ACK yet
                    } catch (e: PortUnreachableException) {
                        Log.i(TAG, "UDP port became unreachable for $host:$port; falling back to WebSocket")
                        break
                    } catch (_: Exception) {
                        // Ignore transient receive errors while keepalive timer tracks blackhole
                    }

                    if (acked) {
                        keepaliveInFlight = false
                        missedKeepalives = 0
                        lastAckNs = System.nanoTime()
                    } else if ((nowLoopNs - keepaliveSentNs) >= keepaliveTimeoutNs) {
                        keepaliveInFlight = false
                        missedKeepalives++
                        if (missedKeepalives >= MAX_MISSED_KEEPALIVES) {
                            Log.w(TAG, "UDP channel blackholed for $host:$port; falling back to WebSocket")
                            break
                        }
                    }
                }

                if (!keepaliveInFlight && lastHadActivity && (nowLoopNs - lastAckNs) >= keepaliveIntervalNs) {
                    try {
                        keepaliveTag = keepaliveRandom.nextInt()
                        val kaPayload = UdpMouseCodec.encodeProbePayload(keepaliveTag)
                        val kaSeq = seqCounter.incrementAndGet()
                        val kaWire = UdpMouseCodec.sealPacket(
                            secretKey = secretKey,
                            pktType = UdpMouseCodec.PKT_PROBE,
                            sessionId = sessionId,
                            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                            seq = kaSeq,
                            plaintext = kaPayload,
                            cipher = cipher
                        )
                        activeSocket.send(DatagramPacket(kaWire, kaWire.size))
                        keepaliveInFlight = true
                        keepaliveSentNs = nowLoopNs
                    } catch (e: Exception) {
                        if (!closed) {
                            Log.w(TAG, "UDP keepalive send failed; falling back to WebSocket: ${e.message}")
                        }
                        break
                    }
                }

                var touchFrame: List<TouchpadContact>? = null
                synchronized(pendingLock) {
                    val transition = pendingTouchTransitions.removeFirstOrNull()
                    if (transition != null) {
                        touchFrame = transition
                    } else if (pendingTouchMove != null) {
                        touchFrame = pendingTouchMove
                        pendingTouchMove = null
                    }
                }
                val frame = touchFrame
                if (frame != null) {
                    lastHadActivity = true
                    try {
                        val seq = seqCounter.incrementAndGet()
                        val totalLen = UdpMouseCodec.sealTouchpadInto(
                            secretKey = secretKey,
                            sessionId = sessionId,
                            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                            seq = seq,
                            contacts = frame,
                            payloadScratch = payloadScratch,
                            nonceScratch = nonceScratch,
                            wireOut = wireBuf,
                            cipher = cipher
                        )
                        sendPacket.setLength(totalLen)
                        activeSocket.send(sendPacket)
                        synchronized(pendingLock) {
                            if (seq > latestMoveSeq) {
                                latestMoveSeq = seq
                            }
                        }
                        liftoffRepeatDeadlineNs = if (frame.isEmpty()) {
                            System.nanoTime() + LIFTOFF_REPEAT_DELAY_NS
                        } else {
                            0L
                        }
                    } catch (e: Exception) {
                        if (!closed) {
                            Log.w(TAG, "UDP touchpad send error; falling back to WebSocket: ${e.message}")
                        }
                        break
                    }
                    continue
                }

                if (liftoffRepeatDeadlineNs != 0L && nowLoopNs >= liftoffRepeatDeadlineNs) {
                    liftoffRepeatDeadlineNs = 0L
                    lastHadActivity = true
                    try {
                        val seq = seqCounter.incrementAndGet()
                        val totalLen = UdpMouseCodec.sealTouchpadInto(
                            secretKey = secretKey,
                            sessionId = sessionId,
                            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                            seq = seq,
                            contacts = emptyList(),
                            payloadScratch = payloadScratch,
                            nonceScratch = nonceScratch,
                            wireOut = wireBuf,
                            cipher = cipher
                        )
                        sendPacket.setLength(totalLen)
                        activeSocket.send(sendPacket)
                        synchronized(pendingLock) {
                            if (seq > latestMoveSeq) {
                                latestMoveSeq = seq
                            }
                        }
                    } catch (e: Exception) {
                        if (!closed) {
                            Log.w(TAG, "UDP liftoff repeat error; falling back to WebSocket: ${e.message}")
                        }
                        break
                    }
                    continue
                }

                var dx = 0
                var dy = 0
                var wheel = 0
                var isHWheel = false
                var isHiRes = false
                synchronized(pendingLock) {
                    dx = pendingDx
                    dy = pendingDy
                    pendingDx = 0
                    pendingDy = 0
                    isHiRes = pendingWheelIsHiRes
                    when {
                        pendingWheel != 0 -> {
                            wheel = pendingWheel
                            pendingWheel = 0
                            isHWheel = false
                        }
                        pendingHWheel != 0 -> {
                            wheel = pendingHWheel
                            pendingHWheel = 0
                            isHWheel = true
                        }
                    }
                }
                if (dx == 0 && dy == 0 && wheel == 0) {
                    var sleepNs = if (keepaliveInFlight) {
                        minOf(idleParkNs, 15_000_000L)
                    } else {
                        idleParkNs
                    }
                    if (liftoffRepeatDeadlineNs != 0L) {
                        val remNs = (liftoffRepeatDeadlineNs - System.nanoTime()).coerceAtLeast(1_000_000L)
                        sleepNs = minOf(sleepNs, remNs)
                    }
                    LockSupport.parkNanos(sleepNs)
                    continue
                }

                lastHadActivity = true
                var flags = 0
                if (wheel != 0) {
                    if (isHiRes) flags = flags or UdpMouseCodec.FLAG_HI_RES_WHEEL
                    if (isHWheel) flags = flags or UdpMouseCodec.FLAG_HWHEEL
                }

                try {
                    val nowNs = System.nanoTime()
                    if (lastSentNs != 0L && (nowNs - lastSentNs) > FEC_MAX_AGE_NS) {
                        hasPrev1 = false
                        hasPrev2 = false
                    }
                    val seq = seqCounter.incrementAndGet()
                    val totalLen = UdpMouseCodec.sealMoveWithFecInto(
                        secretKey = secretKey,
                        sessionId = sessionId,
                        dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                        seq = seq,
                        dx = dx,
                        dy = dy,
                        wheel = wheel,
                        flags = flags,
                        hasPrev1 = hasPrev1,
                        prev1Dx = prev1Dx,
                        prev1Dy = prev1Dy,
                        prev1Wheel = prev1Wheel,
                        prev1Flags = prev1Flags,
                        hasPrev2 = hasPrev2,
                        prev2Dx = prev2Dx,
                        prev2Dy = prev2Dy,
                        prev2Wheel = prev2Wheel,
                        prev2Flags = prev2Flags,
                        payloadScratch = payloadScratch,
                        nonceScratch = nonceScratch,
                        wireOut = wireBuf,
                        cipher = cipher
                    )
                    sendPacket.setLength(totalLen)
                    activeSocket.send(sendPacket)
                    if (dx != 0 || dy != 0) {
                        synchronized(pendingLock) {
                            if (seq > latestMoveSeq) {
                                latestMoveSeq = seq
                            }
                        }
                    }
                    lastSentNs = nowNs

                    if (hasPrev1) {
                        hasPrev2 = true
                        prev2Dx = prev1Dx
                        prev2Dy = prev1Dy
                        prev2Wheel = prev1Wheel
                        prev2Flags = prev1Flags
                    }
                    hasPrev1 = true
                    prev1Dx = dx
                    prev1Dy = dy
                    prev1Wheel = wheel
                    prev1Flags = flags
                } catch (e: Exception) {
                    if (!closed) {
                        Log.w(TAG, "UDP send error; falling back to WebSocket: ${e.message}")
                    }
                    break
                }
            }
        } finally {
            try {
                localSocket?.close()
            } catch (_: Exception) {}
            closeInternal(notifyUnexpected = true)
        }
    }
}
