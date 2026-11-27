package com.silentinput.app.network

import com.silentinput.app.model.AppStrings
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher

class NetworkClientMockWebServerTest {

    private fun awaitCondition(timeoutMs: Long = 5000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    private fun nonLoopbackTargetHost(): String {
        val local = runCatching { InetAddress.getLocalHost().hostAddress }.getOrNull()
        return if (!local.isNullOrBlank() && !local.contains(':') && !NetworkClient.isLoopbackHost(local)) {
            local
        } else {
            "127.0.0.2"
        }
    }

    @Test
    fun `usb loopback connects without sending auth token and updates unicode support`() {
        val server = MockWebServer()
        val receivedMessages: BlockingQueue<String> = LinkedBlockingQueue()
        val openLatch = CountDownLatch(1)
        val serverSocket = AtomicReference<WebSocket?>(null)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedMessages.offer(text)
                    if (text.contains("\"get_status\"")) {
                        webSocket.send("""{"type":"status","unicode_direct_typing":false}""")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
        server.start()

        val client = NetworkClient(context = null)
        try {
            client.serverIp = "127.0.0.1"
            client.serverPort = server.port
            client.authToken = "should-not-be-sent"
            client.isTlsEnabled = false
            client.connect()

            assertTrue(openLatch.await(5, TimeUnit.SECONDS))
            val firstMsg = receivedMessages.poll(5, TimeUnit.SECONDS)
            assertEquals("""{"type":"get_status"}""", firstMsg)
            assertTrue(awaitCondition { client.isTransportReady.value && !client.supportsUnicodeDirectTyping.value })
            assertTrue(client.isConnected.value)
            assertTrue(client.isAuthenticated.value)
            assertFalse(client.isEncrypted.value)
        } finally {
            serverSocket.get()?.close(1000, null)
            client.destroy()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `lan auth success enables transport ready and sends paste payload`() {
        val server = MockWebServer()
        val receivedMessages: BlockingQueue<String> = LinkedBlockingQueue()
        val serverSocket = AtomicReference<WebSocket?>(null)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedMessages.offer(text)
                    if (text.contains("\"type\":\"auth\"")) {
                        webSocket.send("""{"type":"auth_result","status":"ok","unicode_direct_typing":true}""")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
        server.start(InetAddress.getByName("0.0.0.0"), 0)

        val client = NetworkClient(context = null)
        try {
            client.serverIp = "${nonLoopbackTargetHost()}:${server.port}"
            client.authToken = "secret-123"
            client.isTlsEnabled = false
            client.connect()

            val authMsg = receivedMessages.poll(5, TimeUnit.SECONDS)
            assertEquals("""{"type":"auth","token":"secret-123"}""", authMsg)
            assertTrue(awaitCondition { client.isTransportReady.value })
            assertTrue(client.isAuthenticated.value)
            assertNull(client.connectionError.value)

            assertTrue(client.sendPaste("hello", enter = true, mode = "gui"))
            val pasteMsg = receivedMessages.poll(5, TimeUnit.SECONDS)
            assertEquals("""{"type":"paste","content":"hello","mode":"gui","enter":true}""", pasteMsg)

            assertTrue(client.sendMouse(4f, -3f, action = "move"))
            assertTrue(client.sendMouse(0f, 0f, action = "click_left"))
            val moveMsg = receivedMessages.poll(5, TimeUnit.SECONDS)
            val clickMsg = receivedMessages.poll(5, TimeUnit.SECONDS)
            assertEquals("""{"type":"mouse","dx":4.0,"dy":-3.0,"action":"move"}""", moveMsg)
            assertEquals("""{"type":"mouse","dx":0.0,"dy":0.0,"action":"click_left"}""", clickMsg)
        } finally {
            serverSocket.get()?.close(1000, null)
            client.destroy()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `lan auth failure sets wrong token error and stops reconnecting`() {
        val server = MockWebServer()
        val serverSocket = AtomicReference<WebSocket?>(null)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"type\":\"auth\"")) {
                        webSocket.send("""{"type":"auth_result","status":"error","message":"Invalid token"}""")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
        server.start(InetAddress.getByName("0.0.0.0"), 0)

        val client = NetworkClient(context = null)
        try {
            client.serverIp = nonLoopbackTargetHost()
            client.serverPort = server.port
            client.authToken = "wrong-token"
            client.isTlsEnabled = false
            client.connect()

            assertTrue(awaitCondition { client.connectionError.value == AppStrings.ZH.netErrWrongToken })
            assertFalse(client.isConnected.value)
            assertFalse(client.isAuthenticated.value)
            assertFalse(client.isTransportReady.value)
            assertFalse(client.isReconnecting.value)
        } finally {
            serverSocket.get()?.close(1000, null)
            client.destroy()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `udp reprobe upgrades late-opening udp path and click barrier includes udp_seq`() {
        val udpSock = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        val udpPort = udpSock.localPort
        udpSock.soTimeout = 300
        val udpResponding = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val udpKeyB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val udpKey = UdpMouseCodec.decodeAes256Key(udpKeyB64)!!
        val sessionIdHex = "0123456789abcdef"
        val sessionId = UdpMouseCodec.parseSessionIdHex(sessionIdHex)!!
        var srvAckSeq = 0L

        val udpThread = Thread {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    udpSock.receive(pkt)
                    if (!udpResponding.get()) continue
                    val data = buf.copyOf(pkt.length)
                    if (data.getOrNull(3) == UdpMouseCodec.PKT_PROBE) {
                        val pt = UdpMouseCodec.openPacket(
                            secretKey = udpKey,
                            expectedSessionId = sessionId,
                            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                            expectedPktType = UdpMouseCodec.PKT_PROBE,
                            packet = data,
                            cipher = cipher
                        )
                        if (pt != null) {
                            srvAckSeq++
                            val ack = UdpMouseCodec.sealPacket(
                                secretKey = udpKey,
                                pktType = UdpMouseCodec.PKT_PROBE_ACK,
                                sessionId = sessionId,
                                dir = UdpMouseCodec.DIR_SERVER_TO_CLIENT,
                                seq = srvAckSeq,
                                plaintext = pt,
                                cipher = cipher
                            )
                            udpSock.send(DatagramPacket(ack, ack.size, pkt.address, pkt.port))
                        }
                    }
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }

        val server = MockWebServer()
        val receivedMessages: BlockingQueue<String> = LinkedBlockingQueue()
        val serverSocket = AtomicReference<WebSocket?>(null)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedMessages.offer(text)
                    if (text.contains("\"type\":\"auth\"")) {
                        webSocket.send(
                            """{"type":"auth_result","status":"ok","unicode_direct_typing":true,"udp_port":$udpPort,"udp_session_id":"$sessionIdHex","udp_key":"$udpKeyB64"}"""
                        )
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
        server.start(InetAddress.getByName("0.0.0.0"), 0)

        val client = NetworkClient(context = null, udpReprobeIntervalMs = 150L)
        try {
            client.serverIp = "${nonLoopbackTargetHost()}:${server.port}"
            client.authToken = "secret-123"
            client.isTlsEnabled = false
            client.connect()

            assertTrue(awaitCondition { client.isTransportReady.value })
            // Initial probe fails because udpResponding == false -> remains on TCP fallback briefly
            Thread.sleep(200)
            assertFalse(client.isUdpMouseActive.value)

            // Enable UDP replies -> background reprobe upgrades channel to UDP automatically
            udpResponding.set(true)
            assertTrue(awaitCondition(timeoutMs = 5000L) { client.isUdpMouseActive.value })

            // Send a move followed by click_left -> click payload carries udp_seq >= 1
            assertTrue(client.sendMouse(10f, -4f, action = "move"))
            Thread.sleep(40)
            assertTrue(client.sendMouse(0f, 0f, action = "click_left"))

            var clickJson: String? = null
            val deadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < deadline) {
                val msg = receivedMessages.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (msg.contains("\"click_left\"")) {
                    clickJson = msg
                    break
                }
            }
            assertTrue("Expected click_left with udp_seq, got $clickJson", clickJson?.contains("\"udp_seq\":") == true)
        } finally {
            running.set(false)
            udpSock.close()
            udpThread.join(500)
            serverSocket.get()?.close(1000, null)
            client.destroy()
            runCatching { server.shutdown() }
        }
    }
}
