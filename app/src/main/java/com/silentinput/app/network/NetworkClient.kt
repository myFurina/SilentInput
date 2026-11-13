package com.silentinput.app.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.silentinput.app.model.ProtocolPayloads
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class NetworkClient(context: Context? = null) {

    companion object {
        private const val TAG = "NetworkClient"
        const val DEFAULT_PORT = 53318
        private const val TOFU_PREFS_NAME = "silentinput_tofu_certs"

        fun formatShortFingerprint(hex: String?): String {
            if (hex.isNullOrBlank()) return ""
            return hex.take(16).uppercase().chunked(4).joinToString(":")
        }
    }

    private val tofuPrefs = context?.applicationContext?.getSharedPreferences(TOFU_PREFS_NAME, Context.MODE_PRIVATE)

    // Base OkHttpClient owning the shared Dispatcher and ConnectionPool
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS) // Keep NAT and Wi-Fi radio alive
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun sha256Hex(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildClientForHost(cleanIp: String, port: Int, useTls: Boolean, connGen: Long): OkHttpClient {
        if (!useTls) return baseClient

        val isLocalhost = cleanIp == "127.0.0.1" || cleanIp == "::1" || cleanIp == "localhost"
        val hostKey = "$cleanIp:$port"

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (isLocalhost) return
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty server certificate chain")
                }
                val fingerprint = sha256Hex(chain[0])
                val prefs = tofuPrefs ?: return
                synchronized(stateLock) {
                    val saved = prefs.getString(hostKey, null)
                    if (saved == null) {
                        // Zero-friction TOFU: silently pin leaf cert SHA-256 on first connect
                        prefs.edit().putString(hostKey, fingerprint).apply()
                        Log.i(TAG, "TOFU pinned new certificate for $hostKey (${formatShortFingerprint(fingerprint)})")
                    } else if (!saved.equals(fingerprint, ignoreCase = true)) {
                        if (connectionGeneration == connGen) {
                            _certMismatchHost.value = hostKey
                            _savedCertFingerprint.value = saved
                            _pendingCertFingerprint.value = fingerprint
                        }
                        Log.w(TAG, "TOFU certificate mismatch for $hostKey!")
                        throw CertificateException("TOFU_CERT_MISMATCH:$hostKey")
                    }
                    Unit
                }
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // LAN/Tailscale connections authenticate via pinned cert fingerprint
            .build()
    }

    @Volatile
    private var webSocket: WebSocket? = null
    private val stateLock = Any()
    private var connectionGeneration: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting = _isReconnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated = _isAuthenticated.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs = _latencyMs.asStateFlow()

    private val _isEncrypted = MutableStateFlow(false)
    val isEncrypted = _isEncrypted.asStateFlow()

    private val _certMismatchHost = MutableStateFlow<String?>(null)
    val certMismatchHost = _certMismatchHost.asStateFlow()

    private val _pendingCertFingerprint = MutableStateFlow<String?>(null)
    val pendingCertFingerprint = _pendingCertFingerprint.asStateFlow()

    private val _savedCertFingerprint = MutableStateFlow<String?>(null)
    val savedCertFingerprint = _savedCertFingerprint.asStateFlow()

    @Volatile
    var serverIp: String = "127.0.0.1"
    @Volatile
    var serverPort: Int = DEFAULT_PORT
    @Volatile
    var authToken: String = ""

    // User preference for TLS encryption (toggleable in Settings)
    // Synchronous strict encryption: when true, only WSS is permitted with zero plaintext fallback
    @Volatile
    var isTlsEnabled: Boolean = true

    // Flag indicating whether user intentionally disconnected or wants to maintain connection
    @Volatile
    private var shouldBeConnected = false

    // Heartbeat tracking
    @Volatile
    private var pendingPingSentTime: Long = 0L
    @Volatile
    private var lastPongReceivedTime: Long = 0L

    fun connect() {
        synchronized(stateLock) {
            val cleanIp = serverIp.trim()
            if (cleanIp.isEmpty()) {
                shouldBeConnected = false
                _isConnecting.value = false
                _isReconnecting.value = false
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectSocketOnlyLocked()
                _connectionError.value = "请输入电脑 IP 地址"
                return
            }
            if (cleanIp != "127.0.0.1" && authToken.trim().isBlank()) {
                shouldBeConnected = false
                _isConnecting.value = false
                _isReconnecting.value = false
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectSocketOnlyLocked()
                _connectionError.value = "请输入配对密钥"
                return
            }
            shouldBeConnected = true
            _isConnecting.value = true
            _isReconnecting.value = false
            _connectionError.value = null
            _certMismatchHost.value = null
            _pendingCertFingerprint.value = null
            _savedCertFingerprint.value = null
            reconnectJob?.cancel()
            reconnectJob = null
            doConnectLocked()
        }
    }

    fun trustNewCertificateAndReconnect() {
        synchronized(stateLock) {
            val hostKey = _certMismatchHost.value
            val newFp = _pendingCertFingerprint.value
            if (hostKey != null && newFp != null) {
                tofuPrefs?.edit()?.putString(hostKey, newFp)?.apply()
                Log.i(TAG, "User accepted new TOFU certificate for $hostKey")
            }
            _certMismatchHost.value = null
            _pendingCertFingerprint.value = null
            _savedCertFingerprint.value = null
            _connectionError.value = null
        }
        connect()
    }

    private fun doConnectLocked() {
        disconnectSocketOnlyLocked()

        val cleanIp = serverIp.trim()
        if (cleanIp.isEmpty()) return

        val useTls = isTlsEnabled
        val proto = if (useTls) "wss" else "ws"
        val url = "$proto://$cleanIp:$serverPort/"
        Log.d(TAG, "Connecting to $url (Strict TLS: $useTls)...")

        val connGen = ++connectionGeneration
        _isConnecting.value = true
        val scopedClient = try {
            buildClientForHost(cleanIp, serverPort, useTls, connGen)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TLS client: ${e.message}", e)
            _connectionError.value = "TLS 初始化失败: ${e.message ?: "未知错误"}"
            _isConnecting.value = false
            shouldBeConnected = false
            return
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val tokenSnapshot: String
                val isUsb = cleanIp == "127.0.0.1"
                synchronized(stateLock) {
                    if (this@NetworkClient.webSocket !== webSocket) return
                    tokenSnapshot = authToken.trim()

                    if (!isUsb && tokenSnapshot.isBlank()) {
                        Log.w(TAG, "LAN connection aborted: empty pairing token")
                        shouldBeConnected = false
                        _isConnecting.value = false
                        _isReconnecting.value = false
                        reconnectJob?.cancel()
                        reconnectJob = null
                        _connectionError.value = "请输入配对密钥"
                        disconnectSocketOnlyLocked()
                        return
                    }

                    Log.d(TAG, "WebSocket connected to $url (TLS: $useTls)")
                    _isConnected.value = true
                    _isConnecting.value = false
                    _isReconnecting.value = false
                    _connectionError.value = null
                    _certMismatchHost.value = null
                    _pendingCertFingerprint.value = null
                    _savedCertFingerprint.value = null
                    _isEncrypted.value = useTls

                    if (isUsb) {
                        _isAuthenticated.value = true
                        _latencyMs.value = 1L
                    } else {
                        _isAuthenticated.value = false
                    }
                    startPingLoopLocked(webSocket)
                }

                // Send auth payload over established WebSocket channel rather than URL query.
                // On 127.0.0.1, also send token if configured so allow_usb_unauthenticated=false still succeeds.
                if (tokenSnapshot.isNotBlank()) {
                    webSocket.send(ProtocolPayloads.buildAuthJson(tokenSnapshot))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@NetworkClient.webSocket !== webSocket) return
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "pong" -> {
                            val sentTime = json.optLong("t", 0L)
                            val now = SystemClock.elapsedRealtime()
                            if (sentTime > 0) {
                                synchronized(stateLock) {
                                    if (this@NetworkClient.webSocket !== webSocket) return
                                    val rtt = now - sentTime
                                    _latencyMs.value = rtt.coerceAtLeast(0L)
                                    pendingPingSentTime = 0L
                                    lastPongReceivedTime = now
                                }
                            }
                        }
                        "auth_result" -> {
                            val status = json.optString("status")
                            if (status == "ok") {
                                synchronized(stateLock) {
                                    if (this@NetworkClient.webSocket !== webSocket) return
                                    _isAuthenticated.value = true
                                    _connectionError.value = null
                                }
                            } else {
                                val serverMsg = json.optString("message", "")
                                val errText = if (serverMsg.contains("Too many", ignoreCase = true)) {
                                    "配对密钥错误次数过多，请稍后再试"
                                } else {
                                    "配对密钥错误"
                                }
                                Log.w(TAG, "Authentication failed: $serverMsg")
                                synchronized(stateLock) {
                                    if (this@NetworkClient.webSocket !== webSocket) return
                                    shouldBeConnected = false
                                    _isReconnecting.value = false
                                    reconnectJob?.cancel()
                                    reconnectJob = null
                                    _connectionError.value = errText
                                    disconnectSocketOnlyLocked()
                                }
                            }
                        }
                        "auth_required" -> {
                            Log.w(TAG, "Server rejected unauthenticated command")
                            synchronized(stateLock) {
                                if (this@NetworkClient.webSocket !== webSocket) return
                                shouldBeConnected = false
                                _isReconnecting.value = false
                                reconnectJob?.cancel()
                                reconnectJob = null
                                _connectionError.value = "配对密钥无效或未认证"
                                disconnectSocketOnlyLocked()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse incoming message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(stateLock) {
                    if (this@NetworkClient.webSocket !== webSocket) return
                }
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(stateLock) {
                    if (this@NetworkClient.webSocket !== webSocket) return
                    Log.w(TAG, "WebSocket connection failed on $proto: ${t.message}")
                    val isTofuMismatch = _certMismatchHost.value != null ||
                        t.message?.contains("TOFU_CERT_MISMATCH") == true ||
                        t.cause?.message?.contains("TOFU_CERT_MISMATCH") == true
                    val errorMsg = when {
                        isTofuMismatch -> {
                            shouldBeConnected = false
                            "电脑证书指纹已变更，已拦截连接"
                        }
                        useTls && (t is javax.net.ssl.SSLException || t.message?.contains("SSL", ignoreCase = true) == true) ->
                            "TLS 握手失败 (电脑端未开启加密或证书异常)"
                        !useTls && (t.message?.contains("CLEARTEXT", ignoreCase = true) == true) ->
                            "系统禁止明文传输"
                        t is java.net.ConnectException ->
                            "无法连接电脑 (请检查 IP、端口及服务状态)"
                        t is java.net.SocketTimeoutException ->
                            "连接超时 (请检查局域网连接)"
                        else ->
                            t.message ?: "连接失败"
                    }
                    _connectionError.value = errorMsg
                    handleDisconnectedLocked()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(stateLock) {
                    if (this@NetworkClient.webSocket !== webSocket) return
                    handleDisconnectedLocked()
                }
            }
        }
        val newSocket = try {
            val request = Request.Builder().url(url).build()
            scopedClient.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WebSocket ($url): ${e.message}", e)
            _connectionError.value = if (e is IllegalArgumentException) {
                "连接地址格式无效，请检查 IP 输入"
            } else {
                "建立连接失败: ${e.message ?: "未知错误"}"
            }
            _isConnecting.value = false
            _isReconnecting.value = false
            shouldBeConnected = false
            return
        }
        webSocket = newSocket
    }

    private fun handleDisconnectedLocked() {
        webSocket = null
        _isConnected.value = false
        _isConnecting.value = false
        _isAuthenticated.value = false
        _latencyMs.value = null
        _isEncrypted.value = false
        stopPingLoopLocked()

        // Auto reconnect with exponential backoff if app is expected to stay connected
        if (shouldBeConnected && reconnectJob == null) {
            _isReconnecting.value = true
            reconnectJob = scope.launch {
                val selfJob = coroutineContext[Job]
                try {
                    var retryDelay = 2000L
                    while (shouldBeConnected && !_isConnected.value) {
                        delay(retryDelay)
                        synchronized(stateLock) {
                            if (shouldBeConnected && !_isConnected.value) {
                                Log.d(TAG, "Attempting auto-reconnect to $serverIp...")
                                doConnectLocked()
                            }
                        }
                        // Give TLS/WebSocket handshake a moment before evaluating retry backoff
                        delay(1500L)
                        retryDelay = (retryDelay * 1.5).toLong().coerceAtMost(10000L)
                    }
                } finally {
                    synchronized(stateLock) {
                        if (reconnectJob === selfJob) {
                            _isReconnecting.value = false
                            reconnectJob = null
                        }
                    }
                }
            }
        } else if (!shouldBeConnected) {
            _isReconnecting.value = false
        }
    }

    /**
     * Check connection state and immediately reconnect if disconnected.
     * Called when MainActivity is resumed (Lifecycle.Event.ON_RESUME).
     */
    fun checkAndReconnect() {
        synchronized(stateLock) {
            if (shouldBeConnected && !_isConnected.value) {
                Log.d(TAG, "App resumed into foreground, immediately reconnecting...")
                reconnectJob?.cancel()
                reconnectJob = null
                _isReconnecting.value = false
                doConnectLocked()
            }
        }
    }

    private fun startPingLoopLocked(activeSocket: WebSocket) {
        stopPingLoopLocked()
        pendingPingSentTime = 0L
        lastPongReceivedTime = SystemClock.elapsedRealtime()

        pingJob = scope.launch {
            while (isActive && _isConnected.value && webSocket === activeSocket) {
                val now = SystemClock.elapsedRealtime()

                // 1. Check if an existing ping is awaiting a pong
                if (pendingPingSentTime > 0L) {
                    val waitTime = now - pendingPingSentTime
                    if (waitTime > 5000L) {
                        // Heartbeat timeout! 5 seconds without response from server -> dead connection
                        Log.w(TAG, "Heartbeat timeout! Server did not respond for ${waitTime}ms, closing dead connection.")
                        synchronized(stateLock) {
                            if (webSocket === activeSocket) {
                                _latencyMs.value = null
                                activeSocket.cancel()
                                handleDisconnectedLocked()
                            }
                        }
                        break
                    } else if (waitTime > 300L) {
                        // Actively experiencing latency: update UI with current real waiting time
                        _latencyMs.value = waitTime
                    }
                } else {
                    // 2. Time to send a new ping (every 1500ms)
                    if (now - lastPongReceivedTime >= 1500L) {
                        pendingPingSentTime = now
                        val enqueued = activeSocket.send(ProtocolPayloads.buildPingJson(now))
                        if (!enqueued) {
                            Log.w(TAG, "Failed to send ping; buffer full or socket closed")
                            synchronized(stateLock) {
                                if (webSocket === activeSocket) {
                                    _latencyMs.value = null
                                    activeSocket.cancel()
                                    handleDisconnectedLocked()
                                }
                            }
                            break
                        }
                    }
                }

                delay(300L)
            }
        }
    }

    private fun stopPingLoopLocked() {
        pendingPingSentTime = 0L
        pingJob?.cancel()
        pingJob = null
    }

    fun sendPaste(text: String, enter: Boolean = false, mode: String = "terminal") {
        sendJson(ProtocolPayloads.buildPasteJson(text, enter = enter, mode = mode))
    }

    fun sendKey(key: String, action: String = "tap") {
        sendJson(ProtocolPayloads.buildKeyJson(key, action = action))
    }

    fun sendMouse(dx: Float, dy: Float, action: String = "move", wheel: Float = 0f) {
        sendJson(ProtocolPayloads.buildMouseJson(dx, dy, action = action, wheel = wheel))
    }

    private fun sendJson(payload: String): Boolean {
        val ws = webSocket ?: return false
        val sent = ws.send(payload)
        if (!sent) {
            synchronized(stateLock) {
                if (webSocket === ws && _isConnected.value) {
                    Log.w(TAG, "Failed to enqueue WebSocket frame; socket dead")
                    _latencyMs.value = null
                    ws.cancel()
                    handleDisconnectedLocked()
                }
            }
        }
        return sent
    }

    private fun disconnectSocketOnlyLocked() {
        stopPingLoopLocked()
        val old = webSocket
        webSocket = null
        try {
            if (_isConnected.value) {
                old?.close(1000, "Normal closure")
            } else {
                old?.cancel()
            }
        } catch (_: Exception) {}
        _isConnected.value = false
        _isAuthenticated.value = false
        _latencyMs.value = null
        _isEncrypted.value = false
    }

    fun disconnect() {
        synchronized(stateLock) {
            shouldBeConnected = false
            _isConnecting.value = false
            _isReconnecting.value = false
            _connectionError.value = null
            reconnectJob?.cancel()
            reconnectJob = null
            disconnectSocketOnlyLocked()
        }
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
