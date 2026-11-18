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
import java.net.InetAddress
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedMessages.offer(text)
                    if (text.contains("\"get_status\"")) {
                        webSocket.send("""{"type":"status","unicode_direct_typing":false}""")
                    }
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
            client.destroy()
            server.shutdown()
        }
    }

    @Test
    fun `lan auth success enables transport ready and sends paste payload`() {
        val server = MockWebServer()
        val receivedMessages: BlockingQueue<String> = LinkedBlockingQueue()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedMessages.offer(text)
                    if (text.contains("\"type\":\"auth\"")) {
                        webSocket.send("""{"type":"auth_result","status":"ok","unicode_direct_typing":true}""")
                    }
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
        } finally {
            client.destroy()
            server.shutdown()
        }
    }

    @Test
    fun `lan auth failure sets wrong token error and stops reconnecting`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"type\":\"auth\"")) {
                        webSocket.send("""{"type":"auth_result","status":"error","message":"Invalid token"}""")
                    }
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
            client.destroy()
            server.shutdown()
        }
    }
}
