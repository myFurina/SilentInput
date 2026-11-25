package com.silentinput.app.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.AppStrings
import com.silentinput.app.model.BaseInputTransport
import com.silentinput.app.model.InputTransport
import com.silentinput.app.model.MouseAction
import com.silentinput.app.model.ProtocolPayloads
import com.silentinput.app.model.ServerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

class NetworkClient(
    context: Context? = null,
    initialLanguage: AppLanguage = AppLanguage.ZH
) : BaseInputTransport(initialLanguage) {

    data class ParsedEndpoint(val host: String, val port: Int) {
        val isIpv6: Boolean get() = host.contains(':')
        val hostKey: String get() = if (isIpv6) "[$host]:$port" else "$host:$port"

        fun toWebSocketUrl(useTls: Boolean): String {
            val proto = if (useTls) "wss" else "ws"
            val formattedHost = if (isIpv6) "[$host]" else host
            return "$proto://$formattedHost:$port/"
        }
    }

    companion object {
        private const val TAG = "NetworkClient"
        const val DEFAULT_PORT = 53318
        private const val TOFU_PREFS_NAME = "silentinput_tofu_certs"
        private const val PING_INITIAL_DELAY_MS = 1000L
        private const val PING_INTERVAL_FOREGROUND_MS = 5000L
        private const val PING_TIMEOUT_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 8

        fun isLoopbackHost(rawHost: String): Boolean {
            val host = rawHost.trim().removePrefix("[").removeSuffix("]")
            return host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)
        }

        fun parseServerEndpoint(rawInput: String, defaultPort: Int = DEFAULT_PORT): ParsedEndpoint? {
            val clean = rawInput.trim()
            if (clean.isEmpty() ||
                clean.contains("://") ||
                clean.any { it.isWhitespace() } ||
                clean.contains("/") ||
                clean.contains("?") ||
                clean.contains("#") ||
                clean.contains("@")
            ) {
                return null
            }
            if (clean.startsWith("[")) {
                val closeIdx = clean.indexOf(']')
                if (closeIdx <= 1) return null
                val host = clean.substring(1, closeIdx)
                if (host.isEmpty() || !host.contains(':')) return null
                val rest = clean.substring(closeIdx + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(":") -> {
                        val parsed = rest.substring(1).toIntOrNull() ?: return null
                        if (parsed !in 1..65535) return null
                        parsed
                    }
                    else -> return null
                }
                return ParsedEndpoint(host, port)
            }
            val firstColon = clean.indexOf(':')
            if (firstColon >= 0) {
                val lastColon = clean.lastIndexOf(':')
                return if (firstColon == lastColon) {
                    val host = clean.substring(0, firstColon)
                    val portStr = clean.substring(firstColon + 1)
                    if (host.isEmpty()) return null
                    val port = portStr.toIntOrNull() ?: return null
                    if (port !in 1..65535) return null
                    ParsedEndpoint(host, port)
                } else {
                    // Bare IPv6 address (e.g. ::1 or fd7a:115c:a1e0::1)
                    ParsedEndpoint(clean, defaultPort)
                }
            }
            return ParsedEndpoint(clean, defaultPort)
        }

        fun formatShortFingerprint(hex: String?): String {
            if (hex.isNullOrBlank()) return ""
            return hex.take(16).uppercase().chunked(4).joinToString(":")
        }
    }

    private val tofuPrefs = context?.applicationContext?.getSharedPreferences(TOFU_PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var udpMouseChannel: UdpMouseChannel? = null

    private val _isUdpMouseActive = MutableStateFlow(false)
    val isUdpMouseActive = _isUdpMouseActive.asStateFlow()

    @Volatile
    private var baseClientInitialized = false

    private class TcpNoDelaySocketFactory : SocketFactory() {
        private val delegate = getDefault()

        private fun configure(socket: Socket): Socket {
            try {
                socket.tcpNoDelay = true
            } catch (_: Exception) {}
            return socket
        }

        override fun createSocket(): Socket = configure(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket = configure(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = configure(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(address, port, localAddress, localPort))
    }

    // Base OkHttpClient owning the shared Dispatcher and ConnectionPool
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(TcpNoDelaySocketFactory())
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // Fallback keepalive; foreground JSON ping measures RTT
            .retryOnConnectionFailure(true)
            .build()
            .also { baseClientInitialized = true }
    }

    private fun sha256Hex(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildClientForHost(endpoint: ParsedEndpoint, useTls: Boolean, connGen: Long): OkHttpClient {
        if (!useTls) return baseClient

        val isLocalhost = isLoopbackHost(endpoint.host)
        val hostKey = endpoint.hostKey

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (isLocalhost) return
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty server certificate chain")
                }
                val fingerprint = sha256Hex(chain[0])
                val prefs = tofuPrefs
                    ?: throw CertificateException("TOFU certificate store unavailable")
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
    private var reconnectAttempts: Int = 0

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

    private val _isTransportReady = MutableStateFlow(false)
    override val isTransportReady = _isTransportReady.asStateFlow()

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

    private var lastErrorFormatter: ((AppStrings) -> String)? = null

    private fun setConnectionError(formatter: ((AppStrings) -> String)?) {
        lastErrorFormatter = formatter
        _connectionError.value = formatter?.invoke(currentStrings())
    }

    override fun updateLanguage(language: AppLanguage) {
        synchronized(stateLock) {
            appLanguage = language
            lastErrorFormatter?.let { formatter ->
                _connectionError.value = formatter(currentStrings())
            }
        }
    }

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

    @Volatile
    private var isInBackground = false

    // Heartbeat tracking (event-driven CompletableDeferred instead of polling)
    @Volatile
    private var pingResponseSignal: CompletableDeferred<Unit>? = null

    fun connect() {
        synchronized(stateLock) {
            val cleanIp = serverIp.trim()
            if (cleanIp.isEmpty()) {
                shouldBeConnected = false
                _isConnecting.value = false
                _isReconnecting.value = false
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectSocketOnlyLocked()
                setConnectionError { it.netErrEmptyIp }
                return
            }
            val endpoint = parseServerEndpoint(cleanIp, serverPort)
            if (endpoint == null) {
                shouldBeConnected = false
                _isConnecting.value = false
                _isReconnecting.value = false
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectSocketOnlyLocked()
                setConnectionError { it.netErrInvalidIp }
                return
            }
            if (!isLoopbackHost(endpoint.host) && authToken.trim().isBlank()) {
                shouldBeConnected = false
                _isConnecting.value = false
                _isReconnecting.value = false
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectSocketOnlyLocked()
                setConnectionError { it.netErrEmptyToken }
                return
            }
            shouldBeConnected = true
            reconnectAttempts = 0
            _isConnecting.value = true
            _isReconnecting.value = false
            setConnectionError(null)
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
            reconnectAttempts = 0
            _certMismatchHost.value = null
            _pendingCertFingerprint.value = null
            _savedCertFingerprint.value = null
            setConnectionError(null)
        }
        connect()
    }

    private fun doConnectLocked() {
        disconnectSocketOnlyLocked()

        val endpoint = parseServerEndpoint(serverIp, serverPort) ?: return
        val useTls = isTlsEnabled
        val proto = if (useTls) "wss" else "ws"
        val url = endpoint.toWebSocketUrl(useTls)
        Log.d(TAG, "Connecting to $url (Strict TLS: $useTls)...")

        val connGen = ++connectionGeneration
        _isConnecting.value = true
        val scopedClient = try {
            buildClientForHost(endpoint, useTls, connGen)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TLS client: ${e.message}", e)
            setConnectionError { it.netErrTlsInitFailed(e.message) }
            _isConnecting.value = false
            shouldBeConnected = false
            return
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val tokenSnapshot: String
                val isUsb = isLoopbackHost(endpoint.host)
                synchronized(stateLock) {
                    if (this@NetworkClient.webSocket !== webSocket) return
                    tokenSnapshot = authToken.trim()

                    if (!isUsb && tokenSnapshot.isBlank()) {
                        Log.w(TAG, "LAN connection aborted: empty pairing token")
                        shouldBeConnected = false
                        _isConnecting.value = false
                        _isReconnecting.value = false
                        reconnectAttempts = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                        disconnectSocketOnlyLocked()
                        setConnectionError { it.netErrEmptyToken }
                        return
                    }

                    Log.d(TAG, "WebSocket connected to $url (TLS: $useTls)")
                    _isConnected.value = true
                    _isConnecting.value = false
                    _isReconnecting.value = false
                    setConnectionError(null)
                    _certMismatchHost.value = null
                    _pendingCertFingerprint.value = null
                    _savedCertFingerprint.value = null
                    _isEncrypted.value = useTls
                    _supportsUnicodeDirectTyping.value = true

                    if (isUsb) {
                        _isAuthenticated.value = true
                        _isTransportReady.value = true
                        _latencyMs.value = 1L
                        reconnectAttempts = 0
                    } else {
                        _isAuthenticated.value = false
                        _isTransportReady.value = false
                    }
                    if (!isInBackground) {
                        startPingLoopLocked(webSocket)
                    }
                }

                // Send auth payload over established WebSocket channel rather than URL query.
                // Never forward LAN tokens to loopback/USB endpoints without server cert pinning.
                if (!isUsb && tokenSnapshot.isNotBlank()) {
                    webSocket.send(ProtocolPayloads.buildAuthJson(tokenSnapshot))
                } else if (isUsb) {
                    webSocket.send(ProtocolPayloads.buildGetStatusJson())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@NetworkClient.webSocket !== webSocket) return
                when (val msg = ProtocolPayloads.parseServerMessage(text)) {
                    is ServerMessage.Pong -> {
                        val sentTime = msg.timestampMs ?: 0L
                        val now = SystemClock.elapsedRealtime()
                        synchronized(stateLock) {
                            if (this@NetworkClient.webSocket !== webSocket) return
                            if (sentTime > 0) {
                                val rtt = now - sentTime
                                _latencyMs.value = rtt.coerceAtLeast(0L)
                            }
                            pingResponseSignal?.complete(Unit)
                            pingResponseSignal = null
                        }
                    }
                    is ServerMessage.AuthResult -> {
                        if (msg.status == "ok") {
                            var udpToStart: UdpMouseChannel? = null
                            synchronized(stateLock) {
                                if (this@NetworkClient.webSocket !== webSocket) return
                                _isAuthenticated.value = true
                                _isTransportReady.value = _isConnected.value
                                reconnectAttempts = 0
                                msg.unicodeDirectTyping?.let {
                                    _supportsUnicodeDirectTyping.value = it
                                }
                                setConnectionError(null)
                                closeUdpChannelLocked()
                                if (!isLoopbackHost(endpoint.host) && msg.udpPort != null && msg.udpSessionId != null && msg.udpKey != null) {
                                    val sid = UdpMouseCodec.parseSessionIdHex(msg.udpSessionId)
                                    val key = UdpMouseCodec.decodeAes256Key(msg.udpKey)
                                    if (sid != null && key != null && msg.udpPort in 1..65535) {
                                        val ch = UdpMouseChannel(
                                            host = endpoint.host,
                                            port = msg.udpPort,
                                            sessionId = sid,
                                            secretKey = key,
                                            onReadyChanged = { ready ->
                                                if (!closedMatches(this@NetworkClient.webSocket, webSocket)) {
                                                    _isUdpMouseActive.value = ready
                                                }
                                            }
                                        )
                                        udpMouseChannel = ch
                                        udpToStart = ch
                                    }
                                }
                            }
                            udpToStart?.start()
                        } else {
                            val serverMsg = msg.message
                            val tooManyAttempts = serverMsg.contains("Too many", ignoreCase = true)
                            Log.w(TAG, "Authentication failed: $serverMsg")
                            synchronized(stateLock) {
                                if (this@NetworkClient.webSocket !== webSocket) return
                                shouldBeConnected = false
                                _isReconnecting.value = false
                                reconnectJob?.cancel()
                                reconnectJob = null
                                disconnectSocketOnlyLocked()
                                setConnectionError { s ->
                                    if (tooManyAttempts) s.netErrTooManyAttempts else s.netErrWrongToken
                                }
                            }
                        }
                    }
                    is ServerMessage.Status -> {
                        synchronized(stateLock) {
                            if (this@NetworkClient.webSocket !== webSocket) return
                            msg.unicodeDirectTyping?.let {
                                _supportsUnicodeDirectTyping.value = it
                            }
                            msg.version?.let {
                                _daemonVersion.value = it
                            }
                        }
                    }
                    is ServerMessage.PasteResult -> {
                        if (msg.status == "busy") {
                            _pasteQueueBusyEvent.tryEmit(msg.message)
                        }
                    }
                    ServerMessage.AuthRequired -> {
                        Log.w(TAG, "Server rejected unauthenticated command")
                        synchronized(stateLock) {
                            if (this@NetworkClient.webSocket !== webSocket) return
                            shouldBeConnected = false
                            _isReconnecting.value = false
                            reconnectJob?.cancel()
                            reconnectJob = null
                            disconnectSocketOnlyLocked()
                            setConnectionError { it.netErrInvalidOrUnauth }
                        }
                    }
                    null -> {
                        Log.w(TAG, "Ignored unrecognized or malformed incoming message")
                    }
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
                    if (isTofuMismatch) {
                        shouldBeConnected = false
                    }
                    setConnectionError { s ->
                        when {
                            isTofuMismatch -> s.netErrTofuMismatch
                            useTls && (t is javax.net.ssl.SSLException || t.message?.contains("SSL", ignoreCase = true) == true) ->
                                s.netErrTlsHandshakeFailed
                            !useTls && (t.message?.contains("CLEARTEXT", ignoreCase = true) == true) ->
                                s.netErrCleartextBlocked
                            t is java.net.ConnectException -> s.netErrConnectFailedHost
                            t is java.net.SocketTimeoutException -> s.netErrTimeout
                            else -> t.message ?: s.netErrGenericFailed
                        }
                    }
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
            setConnectionError { s ->
                if (e is IllegalArgumentException) s.netErrInvalidIp else s.netErrOpenFailed(e.message)
            }
            _isConnecting.value = false
            _isReconnecting.value = false
            shouldBeConnected = false
            return
        }
        webSocket = newSocket
    }

    private fun closedMatches(current: WebSocket?, expected: WebSocket): Boolean {
        return current !== expected
    }

    private fun closeUdpChannelLocked() {
        val ch = udpMouseChannel
        udpMouseChannel = null
        _isUdpMouseActive.value = false
        ch?.close()
    }

    private fun handleDisconnectedLocked() {
        closeUdpChannelLocked()
        webSocket = null
        _isConnected.value = false
        _isConnecting.value = false
        _isAuthenticated.value = false
        _isTransportReady.value = false
        _latencyMs.value = null
        _isEncrypted.value = false
        _supportsUnicodeDirectTyping.value = true
        _daemonVersion.value = null
        stopPingLoopLocked()

        // Auto reconnect with capped exponential backoff when in foreground and expected to stay connected
        if (!isInBackground && shouldBeConnected && reconnectJob == null) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                shouldBeConnected = false
                _isReconnecting.value = false
                if (_connectionError.value == null) {
                    setConnectionError { it.netErrReconnectExhausted }
                }
                return
            }
            val attemptIndex = reconnectAttempts
            reconnectAttempts++
            var retryDelay = 2000L
            repeat(attemptIndex) {
                retryDelay = (retryDelay * 1.5).toLong().coerceAtMost(20000L)
            }
            _isReconnecting.value = true
            reconnectJob = scope.launch {
                val selfJob = coroutineContext[Job]
                try {
                    delay(retryDelay)
                    synchronized(stateLock) {
                        if (reconnectJob === selfJob) {
                            reconnectJob = null
                        }
                        if (!isInBackground && shouldBeConnected && !_isConnected.value && !_isConnecting.value) {
                            Log.d(
                                TAG,
                                "Attempting auto-reconnect to $serverIp (attempt ${attemptIndex + 1}/$MAX_RECONNECT_ATTEMPTS)..."
                            )
                            doConnectLocked()
                        } else if (isInBackground || !shouldBeConnected) {
                            _isReconnecting.value = false
                        }
                    }
                } finally {
                    synchronized(stateLock) {
                        if (reconnectJob === selfJob) {
                            reconnectJob = null
                            if (!_isConnecting.value && !_isConnected.value) {
                                _isReconnecting.value = false
                            }
                        }
                    }
                }
            }
        } else if (isInBackground || !shouldBeConnected) {
            _isReconnecting.value = false
        }
    }

    /**
     * Immediately pause background reconnect retries and RTT polling when app moves to background,
     * keeping the open socket alive during the short background grace period.
     */
    override fun enterBackground() {
        synchronized(stateLock) {
            isInBackground = true
            reconnectJob?.cancel()
            reconnectJob = null
            _isReconnecting.value = false
            stopPingLoopLocked()
        }
    }

    /**
     * Suspend active socket after background grace period expires without clearing [shouldBeConnected],
     * so returning to foreground can automatically reconnect if the link was previously wanted.
     */
    override fun suspendForBackground() {
        synchronized(stateLock) {
            if (!isInBackground) return
            reconnectJob?.cancel()
            reconnectJob = null
            _isReconnecting.value = false
            disconnectSocketOnlyLocked()
        }
    }

    /**
     * Resume foreground heartbeat if still connected, or immediately reconnect if suspended/dropped.
     */
    override fun exitBackground() {
        synchronized(stateLock) {
            isInBackground = false
            val ws = webSocket
            if (_isConnected.value && ws != null) {
                startPingLoopLocked(ws)
            } else if (shouldBeConnected && !_isConnecting.value) {
                Log.d(TAG, "App resumed into foreground, immediately reconnecting...")
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = null
                _isReconnecting.value = false
                doConnectLocked()
            }
        }
    }

    /**
     * Check connection state and immediately reconnect if disconnected.
     * Called when MainActivity is resumed (Lifecycle.Event.ON_RESUME).
     */
    fun checkAndReconnect() {
        synchronized(stateLock) {
            if (!isInBackground && shouldBeConnected && !_isConnected.value && !_isConnecting.value) {
                Log.d(TAG, "Checking connection state, immediately reconnecting...")
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = null
                _isReconnecting.value = false
                doConnectLocked()
            }
        }
    }

    private fun startPingLoopLocked(activeSocket: WebSocket) {
        stopPingLoopLocked()

        pingJob = scope.launch {
            var nextDelay = PING_INITIAL_DELAY_MS
            while (isActive && !isInBackground && _isConnected.value && webSocket === activeSocket) {
                delay(nextDelay)
                nextDelay = PING_INTERVAL_FOREGROUND_MS
                if (!isActive || isInBackground || !_isConnected.value || webSocket !== activeSocket) {
                    break
                }

                val deferred = CompletableDeferred<Unit>()
                val sentAt = SystemClock.elapsedRealtime()
                val enqueued = synchronized(stateLock) {
                    if (isInBackground || !_isConnected.value || webSocket !== activeSocket) {
                        return@synchronized false
                    }
                    pingResponseSignal = deferred
                    activeSocket.send(ProtocolPayloads.buildPingJson(sentAt))
                }
                if (!enqueued) {
                    deferred.cancel()
                    Log.w(TAG, "Failed to send ping; buffer full or socket closed")
                    synchronized(stateLock) {
                        if (pingResponseSignal === deferred) {
                            pingResponseSignal = null
                        }
                        if (webSocket === activeSocket && _isConnected.value) {
                            _latencyMs.value = null
                            activeSocket.cancel()
                            handleDisconnectedLocked()
                        }
                    }
                    break
                }

                val received = withTimeoutOrNull(PING_TIMEOUT_MS) {
                    deferred.await()
                }
                if (received == null && isActive && !isInBackground) {
                    Log.w(TAG, "Heartbeat timeout (>${PING_TIMEOUT_MS}ms), closing dead connection.")
                    synchronized(stateLock) {
                        if (pingResponseSignal === deferred) {
                            pingResponseSignal = null
                        }
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
    }

    private fun stopPingLoopLocked() {
        pingResponseSignal?.cancel()
        pingResponseSignal = null
        pingJob?.cancel()
        pingJob = null
    }

    override fun sendPaste(text: String, enter: Boolean, mode: String): Boolean {
        return sendJson(ProtocolPayloads.buildPasteJson(text, enter = enter, mode = mode))
    }

    override fun sendKey(key: String, action: String): Boolean {
        return sendJson(ProtocolPayloads.buildKeyJson(key, action = action))
    }

    override fun sendMouse(dx: Float, dy: Float, action: String, wheel: Float): Boolean {
        if (action == MouseAction.MOVE.wireValue) {
            val udp = udpMouseChannel
            if (udp != null && udp.isReady) {
                val ix = if (dx.isFinite()) dx.roundToInt() else 0
                val iy = if (dy.isFinite()) dy.roundToInt() else 0
                if (ix != 0 || iy != 0) {
                    udp.enqueueMove(ix, iy)
                }
                return true
            }
        }
        return sendJson(ProtocolPayloads.buildMouseJson(dx, dy, action = action, wheel = wheel))
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
        closeUdpChannelLocked()
        val wasConnected = _isConnected.value
        val old = webSocket
        webSocket = null
        _isConnected.value = false
        _isAuthenticated.value = false
        _isTransportReady.value = false
        _latencyMs.value = null
        _isEncrypted.value = false
        _supportsUnicodeDirectTyping.value = true
        _daemonVersion.value = null
        try {
            if (wasConnected) {
                old?.close(1000, "Normal closure")
            } else {
                old?.cancel()
            }
        } catch (_: Exception) {}
    }

    fun disconnect() {
        synchronized(stateLock) {
            shouldBeConnected = false
            reconnectAttempts = 0
            _isConnecting.value = false
            _isReconnecting.value = false
            setConnectionError(null)
            reconnectJob?.cancel()
            reconnectJob = null
            disconnectSocketOnlyLocked()
        }
    }

    fun getPinnedFingerprint(rawIp: String = serverIp, defaultPort: Int = serverPort): String? {
        val endpoint = parseServerEndpoint(rawIp, defaultPort) ?: return null
        if (isLoopbackHost(endpoint.host)) return null
        val prefs = tofuPrefs ?: return null
        return synchronized(stateLock) {
            prefs.getString(endpoint.hostKey, null)
        }
    }

    fun clearPinnedCertificate(rawIp: String = serverIp, defaultPort: Int = serverPort): Boolean {
        val endpoint = parseServerEndpoint(rawIp, defaultPort) ?: return false
        val prefs = tofuPrefs ?: return false
        synchronized(stateLock) {
            prefs.edit().remove(endpoint.hostKey).apply()
            if (_certMismatchHost.value == endpoint.hostKey) {
                _certMismatchHost.value = null
                _pendingCertFingerprint.value = null
                _savedCertFingerprint.value = null
            }
        }
        return true
    }

    override fun destroy() {
        disconnect()
        scope.cancel()
        if (baseClientInitialized) {
            try {
                baseClient.dispatcher.executorService.shutdown()
            } catch (_: Exception) {}
            try {
                baseClient.connectionPool.evictAll()
            } catch (_: Exception) {}
        }
    }
}
