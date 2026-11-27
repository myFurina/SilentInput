package com.silentinput.app.network

import com.silentinput.app.model.TouchpadContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher

class UdpMouseChannelTest {

    private fun awaitCondition(timeoutMs: Long = 3000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(15)
        }
        return predicate()
    }

    @Test
    fun `udp mouse channel completes probe handshake sends move and hi-res wheel and detects blackhole`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x1122334455667788L
        val serverSock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        serverSock.soTimeout = 500
        val dropKeepalive = AtomicBoolean(false)
        val receivedMoves = CopyOnWriteArrayList<ByteArray>()
        val running = AtomicBoolean(true)
        var serverAckSeq = 0L

        val srvThread = Thread {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    serverSock.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    when (data.getOrNull(3)) {
                        UdpMouseCodec.PKT_PROBE -> {
                            val pt = UdpMouseCodec.openPacket(
                                secretKey = key,
                                expectedSessionId = sessionId,
                                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                                expectedPktType = UdpMouseCodec.PKT_PROBE,
                                packet = data,
                                cipher = cipher
                            )
                            if (pt != null && !dropKeepalive.get()) {
                                serverAckSeq++
                                val ack = UdpMouseCodec.sealPacket(
                                    secretKey = key,
                                    pktType = UdpMouseCodec.PKT_PROBE_ACK,
                                    sessionId = sessionId,
                                    dir = UdpMouseCodec.DIR_SERVER_TO_CLIENT,
                                    seq = serverAckSeq,
                                    plaintext = pt,
                                    cipher = cipher
                                )
                                serverSock.send(DatagramPacket(ack, ack.size, pkt.address, pkt.port))
                            }
                        }
                        UdpMouseCodec.PKT_MOUSE_MOVE -> {
                            val pt = UdpMouseCodec.openPacket(
                                secretKey = key,
                                expectedSessionId = sessionId,
                                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                                expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
                                packet = data,
                                cipher = cipher
                            )
                            if (pt != null) {
                                receivedMoves.add(pt)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // timeout or closed
                }
            }
        }.apply { isDaemon = true; start() }

        val readyEvents = CopyOnWriteArrayList<Boolean>()
        val channel = UdpMouseChannel(
            host = "127.0.0.1",
            port = serverSock.localPort,
            sessionId = sessionId,
            secretKey = key,
            onReadyChanged = { readyEvents.add(it) },
            keepaliveIntervalMs = 60L,
            keepaliveTimeoutMs = 80L
        )
        try {
            channel.start()
            assertTrue(awaitCondition { channel.isReady })

            channel.enqueueMove(12, -5)
            channel.enqueueScroll(60, hiRes = true)

            assertTrue(awaitCondition { receivedMoves.size >= 1 })
            val combinedDx = receivedMoves.sumOf {
                ByteBuffer.wrap(it, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
            }
            val combinedDy = receivedMoves.sumOf {
                ByteBuffer.wrap(it, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
            }
            assertEquals(12, combinedDx)
            assertEquals(-5, combinedDy)

            // Simulate UDP blackhole: stop replying to keepalive probes
            dropKeepalive.set(true)
            assertTrue(awaitCondition(timeoutMs = 2000L) { !channel.isReady })
            assertFalse(channel.isReady)
            assertNotNull(readyEvents.lastOrNull())
            assertEquals(false, readyEvents.last())
        } finally {
            running.set(false)
            channel.close()
            serverSock.close()
            srvThread.join(500)
        }
    }

    @Test
    fun `concurrent start and close never leaks ready state`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val readyFalseLatch = CountDownLatch(1)
        val channel = UdpMouseChannel(
            host = "127.0.0.1",
            port = 59999,
            sessionId = 0x1234L,
            secretKey = key,
            onReadyChanged = { ready ->
                if (!ready) readyFalseLatch.countDown()
            }
        )
        channel.start()
        channel.close()

        assertTrue(readyFalseLatch.await(2, TimeUnit.SECONDS))
        assertFalse(channel.isReady)
    }

    @Test
    fun `udp mouse channel sends FLAG_HWHEEL exposes move barrier and preserves seq across restarts`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x2233445566778899L
        val serverSock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        serverSock.soTimeout = 500
        val receivedPayloads = CopyOnWriteArrayList<ByteArray>()
        val seenSeqs = CopyOnWriteArrayList<Long>()
        val running = AtomicBoolean(true)
        var serverAckSeq = 0L

        val srvThread = Thread {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    serverSock.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    val seq = ByteBuffer.wrap(data, 12, 8).order(ByteOrder.BIG_ENDIAN).long
                    seenSeqs.add(seq)
                    when (data.getOrNull(3)) {
                        UdpMouseCodec.PKT_PROBE -> {
                            val pt = UdpMouseCodec.openPacket(
                                secretKey = key,
                                expectedSessionId = sessionId,
                                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                                expectedPktType = UdpMouseCodec.PKT_PROBE,
                                packet = data,
                                cipher = cipher
                            )
                            if (pt != null) {
                                serverAckSeq++
                                val ack = UdpMouseCodec.sealPacket(
                                    secretKey = key,
                                    pktType = UdpMouseCodec.PKT_PROBE_ACK,
                                    sessionId = sessionId,
                                    dir = UdpMouseCodec.DIR_SERVER_TO_CLIENT,
                                    seq = serverAckSeq,
                                    plaintext = pt,
                                    cipher = cipher
                                )
                                serverSock.send(DatagramPacket(ack, ack.size, pkt.address, pkt.port))
                            }
                        }
                        UdpMouseCodec.PKT_MOUSE_MOVE -> {
                            val pt = UdpMouseCodec.openPacket(
                                secretKey = key,
                                expectedSessionId = sessionId,
                                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                                expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
                                packet = data,
                                cipher = cipher
                            )
                            if (pt != null) {
                                receivedPayloads.add(pt)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }

        val sharedSeq = AtomicLong(0L)
        val ch1 = UdpMouseChannel(
            host = "127.0.0.1",
            port = serverSock.localPort,
            sessionId = sessionId,
            secretKey = key,
            seqCounter = sharedSeq
        )
        var ch2: UdpMouseChannel? = null
        try {
            ch1.start()
            assertTrue(awaitCondition { ch1.isReady })

            ch1.enqueueMove(5, -2)
            assertTrue(awaitCondition { receivedPayloads.size >= 1 })
            ch1.enqueueScroll(wheel = 0, hiRes = true, hwheel = -90)
            assertTrue(awaitCondition { receivedPayloads.size >= 2 })

            val hwheelBb = ByteBuffer.wrap(receivedPayloads[1], 4, 4).order(ByteOrder.BIG_ENDIAN)
            assertEquals((-90).toShort(), hwheelBb.short)
            val flags = hwheelBb.short.toInt() and 0xFFFF
            assertTrue((flags and UdpMouseCodec.FLAG_HWHEEL) != 0)
            assertTrue((flags and UdpMouseCodec.FLAG_HI_RES_WHEEL) != 0)

            val barrier = ch1.drainUnsentMoveAndLastSeq()
            assertTrue(barrier.lastSentSeq >= 2L)
            ch1.close()

            // Restart a second channel with the same shared seqCounter: sequence must strictly advance
            val maxSeqBefore = seenSeqs.maxOrNull() ?: 0L
            ch2 = UdpMouseChannel(
                host = "127.0.0.1",
                port = serverSock.localPort,
                sessionId = sessionId,
                secretKey = key,
                seqCounter = sharedSeq
            )
            ch2.start()
            assertTrue(awaitCondition { ch2.isReady })
            assertTrue((seenSeqs.lastOrNull() ?: 0L) > maxSeqBefore)
        } finally {
            running.set(false)
            ch1.close()
            ch2?.close()
            serverSock.close()
            srvThread.join(500)
        }
    }

    @Test
    fun `udp mouse channel preserves touchpad transitions and sends liftoff safety repeat`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x3344556677889900L
        val serverSock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        serverSock.soTimeout = 500
        val receivedTouchPayloads = CopyOnWriteArrayList<ByteArray>()
        val running = AtomicBoolean(true)
        var serverAckSeq = 0L

        val srvThread = Thread {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    serverSock.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    when (data.getOrNull(3)) {
                        UdpMouseCodec.PKT_PROBE -> {
                            val pt = UdpMouseCodec.openPacket(
                                secretKey = key,
                                expectedSessionId = sessionId,
                                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                                expectedPktType = UdpMouseCodec.PKT_PROBE,
                                packet = data,
                                cipher = cipher
                            )
                            if (pt != null) {
                                serverAckSeq++
                                val ack = UdpMouseCodec.sealPacket(
                                    secretKey = key,
                                    pktType = UdpMouseCodec.PKT_PROBE_ACK,
                                    sessionId = sessionId,
                                    dir = UdpMouseCodec.DIR_SERVER_TO_CLIENT,
                                    seq = serverAckSeq,
                                    plaintext = pt,
                                    cipher = cipher
                                )
                                serverSock.send(DatagramPacket(ack, ack.size, pkt.address, pkt.port))
                            }
                        }
                        UdpMouseCodec.PKT_TOUCHPAD -> {
                            val pt = UdpMouseCodec.openPacket(
                                secretKey = key,
                                expectedSessionId = sessionId,
                                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                                expectedPktType = UdpMouseCodec.PKT_TOUCHPAD,
                                packet = data,
                                cipher = cipher
                            )
                            if (pt != null) {
                                receivedTouchPayloads.add(pt)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }

        val channel = UdpMouseChannel(
            host = "127.0.0.1",
            port = serverSock.localPort,
            sessionId = sessionId,
            secretKey = key
        )
        try {
            channel.start()
            assertTrue(awaitCondition { channel.isReady })

            // Rapid tap: down + empty liftoff queued in immediate succession must preserve both + 15ms safety repeat
            channel.enqueueTouchpadFrame(listOf(TouchpadContact(0, 4000, 4000)), isTransition = true)
            channel.enqueueTouchpadFrame(emptyList(), isTransition = true)

            assertTrue(awaitCondition { receivedTouchPayloads.size >= 3 })
            assertEquals(1, receivedTouchPayloads[0][0].toInt() and 0xFF)
            assertEquals(0, receivedTouchPayloads[1][0].toInt() and 0xFF)
            assertEquals(0, receivedTouchPayloads[2][0].toInt() and 0xFF)
        } finally {
            running.set(false)
            channel.close()
            serverSock.close()
            srvThread.join(500)
        }
    }
}
