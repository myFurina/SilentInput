package com.silentinput.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.AppStrings
import com.silentinput.app.model.BaseInputTransport
import com.silentinput.app.model.InputTransport
import com.silentinput.app.model.ProtocolPayloads
import com.silentinput.app.model.ServerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothCompanionClient(
    private val context: Context,
    initialLanguage: AppLanguage = AppLanguage.ZH
) : BaseInputTransport(initialLanguage) {

    companion object {
        private const val TAG = "BtCompanionClient"
        // Standard Bluetooth Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val PING_INITIAL_DELAY_MS = 1000L
        private const val PING_INTERVAL_FOREGROUND_MS = 5000L
        private const val PING_TIMEOUT_MS = 5000L

        @SuppressLint("MissingPermission")
        fun safeDeviceName(device: BluetoothDevice): String {
            return try {
                device.name ?: device.address
            } catch (_: SecurityException) {
                device.address
            }
        }

        @SuppressLint("MissingPermission")
        fun isComputerDevice(device: BluetoothDevice): Boolean {
            return try {
                device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    override val isTransportReady: StateFlow<Boolean> = isConnected

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress = _connectedDeviceAddress.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs = _latencyMs.asStateFlow()

    private var lastStatusFormatter: ((AppStrings) -> String) = { it.btStatusDisconnected }

    private fun setStatusMessage(formatter: (AppStrings) -> String) {
        lastStatusFormatter = formatter
        _statusMessage.value = formatter(currentStrings())
    }

    override fun updateLanguage(language: AppLanguage) {
        synchronized(stateLock) {
            appLanguage = language
            _statusMessage.value = lastStatusFormatter(currentStrings())
        }
    }

    private val _statusMessage = MutableStateFlow<String?>(AppStrings.forLanguage(initialLanguage).btStatusDisconnected)
    val statusMessage = _statusMessage.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabledFlow = _isBluetoothEnabled.asStateFlow()

    @Volatile
    var onAdapterStateChanged: ((Boolean) -> Unit)? = null

    private val stateLock = Any()
    @Volatile
    private var connectionGeneration: Long = 0L
    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var connectingSocket: BluetoothSocket? = null
    @Volatile
    private var outputStream: OutputStream? = null
    @Volatile
    private var reader: BufferedReader? = null

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var writeJob: Job? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private val outboundQueue = BluetoothOutboundQueue()
    @Volatile
    private var wakeSignal: Channel<Unit>? = null
    @Volatile
    private var isInBackground = false
    @Volatile
    private var pingResponseSignal: CompletableDeferred<Unit>? = null

    private var receiverRegistered = false
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            _isBluetoothEnabled.value = true
                            onAdapterStateChanged?.invoke(true)
                        }
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                            Log.d(TAG, "Bluetooth adapter turned off, closing connection")
                            _isBluetoothEnabled.value = false
                            onConnectionLost()
                            onAdapterStateChanged?.invoke(false)
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    onAdapterStateChanged?.invoke(bluetoothAdapter?.isEnabled == true)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val matchedGen = synchronized(stateLock) {
                        val activeAddr = _connectedDeviceAddress.value
                        if (device != null && activeAddr != null && device.address == activeAddr) {
                            connectionGeneration
                        } else {
                            null
                        }
                    }
                    if (matchedGen != null) {
                        Log.d(TAG, "ACL disconnected for active device ${device?.address}")
                        onConnectionLost(expectedGen = matchedGen)
                    }
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(context, btStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Bluetooth state receiver: ${e.message}")
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun refreshBluetoothEnabled(): Boolean {
        val enabled = bluetoothAdapter?.isEnabled == true
        _isBluetoothEnabled.value = enabled
        return enabled
    }

    @Suppress("DEPRECATION")
    fun setBluetoothEnabled(enable: Boolean): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return try {
            val ok = if (enable) adapter.enable() else adapter.disable()
            if (ok) {
                _isBluetoothEnabled.value = enable
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setBluetoothEnabled($enable) failed: ${e.message}")
            false
        }
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        return try {
            val devices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            // Prioritize computers/laptops to the top
            devices.sortedByDescending { dev -> isComputerDevice(dev) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        synchronized(stateLock) {
            disconnectLocked(resetStatus = false)
            val connGen = ++connectionGeneration
            _isConnecting.value = true
            val devLabel = safeDeviceName(device)
            setStatusMessage { it.btStatusConnecting(devLabel) }

            connectJob = clientScope.launch {
                var activeSocket: BluetoothSocket? = null

                // 1. Try standard SPP UUID connection
                var primarySocket: BluetoothSocket? = null
                try {
                    Log.d(TAG, "Attempting SPP connection to ${device.address} via UUID")
                    val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    primarySocket = s
                    val shouldProceed = synchronized(stateLock) {
                        if (isActive && connectionGeneration == connGen) {
                            connectingSocket = s
                            true
                        } else {
                            false
                        }
                    }
                    if (!shouldProceed) {
                        try { s.close() } catch (_: Exception) {}
                        return@launch
                    }
                    s.connect()
                    activeSocket = s
                } catch (e: Exception) {
                    try { primarySocket?.close() } catch (_: Exception) {}
                    synchronized(stateLock) {
                        if (connectingSocket === primarySocket) {
                            connectingSocket = null
                        }
                    }
                    if (isActive && connectionGeneration == connGen) {
                        Log.w(TAG, "SPP UUID connection failed, attempting fallback to Channel 1", e)
                        var fallbackSocket: BluetoothSocket? = null
                        try {
                            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            val s = m.invoke(device, 1) as BluetoothSocket
                            fallbackSocket = s
                            val shouldProceed = synchronized(stateLock) {
                                if (isActive && connectionGeneration == connGen) {
                                    connectingSocket = s
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!shouldProceed) {
                                try { s.close() } catch (_: Exception) {}
                                return@launch
                            }
                            s.connect()
                            activeSocket = s
                        } catch (e2: Exception) {
                            try { fallbackSocket?.close() } catch (_: Exception) {}
                            synchronized(stateLock) {
                                if (connectingSocket === fallbackSocket) {
                                    connectingSocket = null
                                }
                            }
                            Log.e(TAG, "All RFCOMM connection attempts failed", e2)
                        }
                    }
                }

                if (!isActive || connectionGeneration != connGen) {
                    try { activeSocket?.close() } catch (_: Exception) {}
                    return@launch
                }

                synchronized(stateLock) {
                    if (connectionGeneration != connGen || !isActive) {
                        try { activeSocket?.close() } catch (_: Exception) {}
                        return@launch
                    }
                    connectingSocket = null
                    if (activeSocket != null && activeSocket.isConnected) {
                        socket = activeSocket
                        val out = activeSocket.outputStream
                        val bufReader = BufferedReader(InputStreamReader(activeSocket.inputStream, Charsets.UTF_8))
                        outputStream = out
                        reader = bufReader

                        outboundQueue.clear()
                        val signal = Channel<Unit>(capacity = Channel.CONFLATED)
                        wakeSignal = signal

                        _isConnected.value = true
                        _isConnecting.value = false
                        _supportsUnicodeDirectTyping.value = true
                        val connectedLabel = safeDeviceName(device)
                        _connectedDeviceName.value = connectedLabel
                        _connectedDeviceAddress.value = device.address
                        setStatusMessage { it.btStatusConnected(connectedLabel) }
                        Log.d(TAG, "Bluetooth RFCOMM connected successfully to $connectedLabel")

                        startWriterLocked(signal, out, connGen)
                        startReaderLocked(bufReader, connGen)
                        if (!isInBackground) {
                            startPingLoopLocked(connGen)
                        }
                        sendRaw(ProtocolPayloads.buildGetStatusJson())
                    } else {
                        try { activeSocket?.close() } catch (_: Exception) {}
                        _isConnected.value = false
                        _isConnecting.value = false
                        _connectedDeviceName.value = null
                        _connectedDeviceAddress.value = null
                        setStatusMessage { it.btStatusConnectFailed }
                    }
                }
            }
        }
    }

    private fun startWriterLocked(signal: Channel<Unit>, out: OutputStream, connGen: Long) {
        writeJob?.cancel()
        writeJob = clientScope.launch {
            try {
                for (ignored in signal) {
                    while (true) {
                        val nextPayload = outboundQueue.pollNext() ?: break
                        val bytes = (nextPayload.trim() + "\n").toByteArray(Charsets.UTF_8)
                        out.write(bytes)
                        out.flush()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error writing data: ${e.message}")
                onConnectionLost(expectedGen = connGen)
            }
        }
    }

    private fun startReaderLocked(bufReader: BufferedReader, connGen: Long) {
        readJob?.cancel()
        readJob = clientScope.launch {
            try {
                while (isActive && _isConnected.value && connectionGeneration == connGen) {
                    val line = bufReader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    handleMessage(trimmed, connGen)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Bluetooth read loop ended: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    onConnectionLost(expectedGen = connGen)
                }
            }
        }
    }

    private fun handleMessage(jsonStr: String, connGen: Long) {
        when (val msg = ProtocolPayloads.parseServerMessage(jsonStr)) {
            is ServerMessage.Pong -> {
                val sentTime = msg.timestampMs ?: 0L
                val now = SystemClock.elapsedRealtime()
                synchronized(stateLock) {
                    if (connectionGeneration != connGen) return
                    if (sentTime > 0) {
                        val rtt = (now - sentTime).coerceAtLeast(0L)
                        _latencyMs.value = rtt
                    }
                    pingResponseSignal?.complete(Unit)
                    pingResponseSignal = null
                }
            }
            is ServerMessage.AuthResult -> {
                synchronized(stateLock) {
                    if (connectionGeneration != connGen) return
                    if (msg.status == "ok") {
                        msg.unicodeDirectTyping?.let {
                            _supportsUnicodeDirectTyping.value = it
                        }
                    } else {
                        val serverMsg = msg.message.takeIf { it.isNotBlank() }
                        disconnectLocked(resetStatus = false)
                        setStatusMessage { it.btStatusAuthFailed(serverMsg) }
                    }
                }
            }
            is ServerMessage.Status -> {
                synchronized(stateLock) {
                    if (connectionGeneration != connGen) return
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
                synchronized(stateLock) {
                    if (connectionGeneration != connGen) return
                    disconnectLocked(resetStatus = false)
                    setStatusMessage { it.btStatusAuthFailed(null) }
                }
            }
            null -> Unit
        }
    }

    private fun startPingLoopLocked(connGen: Long) {
        stopPingLoopLocked()

        pingJob = clientScope.launch {
            var nextDelay = PING_INITIAL_DELAY_MS
            while (isActive && !isInBackground && _isConnected.value && connectionGeneration == connGen) {
                delay(nextDelay)
                nextDelay = PING_INTERVAL_FOREGROUND_MS
                if (!isActive || isInBackground || !_isConnected.value || connectionGeneration != connGen) {
                    break
                }

                val deferred = CompletableDeferred<Unit>()
                val sentAt = SystemClock.elapsedRealtime()
                val canSend = synchronized(stateLock) {
                    if (isInBackground || !_isConnected.value || connectionGeneration != connGen) {
                        false
                    } else {
                        pingResponseSignal = deferred
                        true
                    }
                }
                if (!canSend) {
                    deferred.cancel()
                    break
                }
                sendRaw(ProtocolPayloads.buildPingJson(sentAt))

                val received = withTimeoutOrNull(PING_TIMEOUT_MS) {
                    deferred.await()
                }
                if (received == null && isActive && !isInBackground && _isConnected.value && connectionGeneration == connGen) {
                    Log.w(TAG, "Bluetooth heartbeat timeout (>${PING_TIMEOUT_MS}ms), closing dead connection")
                    synchronized(stateLock) {
                        if (pingResponseSignal === deferred) {
                            pingResponseSignal = null
                        }
                    }
                    onConnectionLost(expectedGen = connGen)
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

    private fun closeResourcesLocked() {
        wakeSignal?.close()
        wakeSignal = null
        outboundQueue.clear()
        try { outputStream?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { connectingSocket?.close() } catch (_: Exception) {}
        socket = null
        connectingSocket = null
        outputStream = null
        reader = null
    }

    private fun onConnectionLost(expectedGen: Long? = null) {
        synchronized(stateLock) {
            if (expectedGen != null && connectionGeneration != expectedGen) {
                return
            }
            connectionGeneration++
            val wasConnectedOrConnecting = _isConnected.value || _isConnecting.value
            _isConnected.value = false
            _isConnecting.value = false
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
            _latencyMs.value = null
            _supportsUnicodeDirectTyping.value = true
            if (wasConnectedOrConnecting) {
                setStatusMessage { it.btStatusConnectionLost }
            }
            stopPingLoopLocked()
            writeJob?.cancel()
            writeJob = null
            readJob?.cancel()
            readJob = null
            closeResourcesLocked()
        }
    }

    private fun disconnectLocked(resetStatus: Boolean = true) {
        connectionGeneration++
        connectJob?.cancel()
        connectJob = null
        _isConnected.value = false
        _isConnecting.value = false
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        _latencyMs.value = null
        _supportsUnicodeDirectTyping.value = true
        _daemonVersion.value = null
        if (resetStatus) {
            setStatusMessage { it.btStatusDisconnected }
        }
        stopPingLoopLocked()
        writeJob?.cancel()
        writeJob = null
        readJob?.cancel()
        readJob = null
        closeResourcesLocked()
    }

    override fun enterBackground() {
        synchronized(stateLock) {
            isInBackground = true
            stopPingLoopLocked()
        }
    }

    override fun suspendForBackground() {
        synchronized(stateLock) {
            if (!isInBackground) return
            disconnectLocked(resetStatus = true)
        }
    }

    override fun exitBackground() {
        synchronized(stateLock) {
            isInBackground = false
            if (_isConnected.value) {
                startPingLoopLocked(connectionGeneration)
            }
        }
    }

    fun disconnect() {
        synchronized(stateLock) {
            disconnectLocked(resetStatus = true)
        }
    }

    override fun destroy() {
        disconnect()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(btStateReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        clientScope.cancel()
    }

    fun sendRaw(data: String): Boolean {
        synchronized(stateLock) {
            val signal = wakeSignal
            if (!_isConnected.value || signal == null) return false
            outboundQueue.enqueueControl(data)
            signal.trySend(Unit)
            return true
        }
    }

    override fun sendKey(key: String, action: String): Boolean {
        return sendRaw(ProtocolPayloads.buildKeyJson(key, action = action))
    }

    override fun sendMouse(dx: Float, dy: Float, action: String, wheel: Float): Boolean {
        synchronized(stateLock) {
            val signal = wakeSignal
            if (!_isConnected.value || signal == null) return false
            if (action == "move" && wheel == 0f) {
                outboundQueue.enqueueMove(dx, dy)
                signal.trySend(Unit)
                return true
            }
            outboundQueue.enqueueControl(ProtocolPayloads.buildMouseJson(dx, dy, action = action, wheel = wheel))
            signal.trySend(Unit)
            return true
        }
    }

    override fun sendPaste(text: String, enter: Boolean, mode: String): Boolean {
        return sendRaw(ProtocolPayloads.buildPasteJson(text, enter = enter, mode = mode))
    }
}
