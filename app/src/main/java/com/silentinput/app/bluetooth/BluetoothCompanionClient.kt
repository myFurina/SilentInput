package com.silentinput.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.silentinput.app.model.ProtocolPayloads
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothCompanionClient(private val context: Context) {

    companion object {
        private const val TAG = "BtCompanionClient"
        // Standard Bluetooth Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress = _connectedDeviceAddress.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs = _latencyMs.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>("未连接")
    val statusMessage = _statusMessage.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabledFlow = _isBluetoothEnabled.asStateFlow()

    @Volatile
    var onAdapterStateChanged: ((Boolean) -> Unit)? = null

    private val stateLock = Any()
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
    private val controlQueue = ConcurrentLinkedQueue<String>()
    private val moveLock = Any()
    private var pendingMoveDx = 0f
    private var pendingMoveDy = 0f
    private var hasPendingMove = false
    @Volatile
    private var wakeSignal: Channel<Unit>? = null
    @Volatile
    private var pingPendingTimestamp: Long? = null
    @Volatile
    private var lastPongTimestamp: Long = 0L

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
                    val activeAddr = _connectedDeviceAddress.value
                    if (device != null && activeAddr != null && device.address == activeAddr) {
                        Log.d(TAG, "ACL disconnected for active device $activeAddr")
                        onConnectionLost()
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
            devices.sortedByDescending { dev ->
                dev.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        synchronized(stateLock) {
            disconnectLocked(resetStatus = false)
            _isConnecting.value = true
            _statusMessage.value = "正在连接 ${device.name ?: device.address}..."

            connectJob = clientScope.launch {
                var activeSocket: BluetoothSocket? = null

                // 1. Try standard SPP UUID connection
                try {
                    Log.d(TAG, "Attempting SPP connection to ${device.address} via UUID")
                    val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    connectingSocket = s
                    bluetoothAdapter?.cancelDiscovery()
                    s.connect()
                    activeSocket = s
                } catch (e: Exception) {
                    try { connectingSocket?.close() } catch (_: Exception) {}
                    connectingSocket = null
                    if (isActive) {
                        Log.w(TAG, "SPP UUID connection failed, attempting fallback to Channel 1", e)
                        try {
                            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            val s = m.invoke(device, 1) as BluetoothSocket
                            connectingSocket = s
                            bluetoothAdapter?.cancelDiscovery()
                            s.connect()
                            activeSocket = s
                        } catch (e2: Exception) {
                            try { connectingSocket?.close() } catch (_: Exception) {}
                            connectingSocket = null
                            Log.e(TAG, "All RFCOMM connection attempts failed", e2)
                        }
                    }
                }

                if (!isActive) {
                    try { activeSocket?.close() } catch (_: Exception) {}
                    connectingSocket = null
                    return@launch
                }

                synchronized(stateLock) {
                    connectingSocket = null
                    if (activeSocket != null && activeSocket.isConnected && isActive) {
                        socket = activeSocket
                        val out = activeSocket.outputStream
                        outputStream = out
                        reader = BufferedReader(InputStreamReader(activeSocket.inputStream, Charsets.UTF_8))

                        clearPendingOutbound()
                        val signal = Channel<Unit>(capacity = Channel.CONFLATED)
                        wakeSignal = signal

                        _isConnected.value = true
                        _isConnecting.value = false
                        _connectedDeviceName.value = device.name ?: device.address
                        _connectedDeviceAddress.value = device.address
                        _statusMessage.value = "已连接到 ${device.name ?: device.address}"
                        Log.d(TAG, "Bluetooth RFCOMM connected successfully to ${device.name}")

                        startWriterLocked(signal, out)
                        startReaderLocked()
                        startPingLoopLocked()
                    } else {
                        try { activeSocket?.close() } catch (_: Exception) {}
                        _isConnected.value = false
                        _isConnecting.value = false
                        _connectedDeviceName.value = null
                        _connectedDeviceAddress.value = null
                        _statusMessage.value = "连接失败，请确认电脑端已启动服务并处于蓝牙已配对状态"
                    }
                }
            }
        }
    }

    private fun clearPendingOutbound() {
        controlQueue.clear()
        synchronized(moveLock) {
            pendingMoveDx = 0f
            pendingMoveDy = 0f
            hasPendingMove = false
        }
    }

    private fun startWriterLocked(signal: Channel<Unit>, out: OutputStream) {
        writeJob?.cancel()
        writeJob = clientScope.launch {
            try {
                for (ignored in signal) {
                    while (true) {
                        val nextControl = controlQueue.poll()
                        if (nextControl != null) {
                            val bytes = (nextControl.trim() + "\n").toByteArray(Charsets.UTF_8)
                            out.write(bytes)
                            out.flush()
                            continue
                        }
                        var moveDx = 0f
                        var moveDy = 0f
                        var sendMove = false
                        synchronized(moveLock) {
                            if (hasPendingMove) {
                                moveDx = pendingMoveDx
                                moveDy = pendingMoveDy
                                pendingMoveDx = 0f
                                pendingMoveDy = 0f
                                hasPendingMove = false
                                sendMove = true
                            }
                        }
                        if (sendMove) {
                            val bytes = (ProtocolPayloads.buildMouseJson(moveDx, moveDy, action = "move") + "\n")
                                .toByteArray(Charsets.UTF_8)
                            out.write(bytes)
                            out.flush()
                            continue
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error writing data: ${e.message}")
                onConnectionLost()
            }
        }
    }

    private fun startReaderLocked() {
        readJob?.cancel()
        val bufReader = reader ?: return
        readJob = clientScope.launch {
            try {
                while (isActive && _isConnected.value) {
                    val line = bufReader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    handleMessage(trimmed)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Bluetooth read loop ended: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    onConnectionLost()
                }
            }
        }
    }

    private fun handleMessage(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("type")) {
                "pong" -> {
                    val sentTime = json.optLong("t", 0L)
                    val now = SystemClock.elapsedRealtime()
                    if (sentTime > 0) {
                        val rtt = (now - sentTime).coerceAtLeast(0L)
                        _latencyMs.value = rtt
                    }
                    pingPendingTimestamp = null
                    lastPongTimestamp = now
                }
                "auth_result" -> {
                    val status = json.optString("status")
                    if (status != "ok") {
                        _statusMessage.value = "认证失败: ${json.optString("message", "密钥错误")}"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $jsonStr", e)
        }
    }

    private fun startPingLoopLocked() {
        pingJob?.cancel()
        pingPendingTimestamp = null
        lastPongTimestamp = SystemClock.elapsedRealtime()

        pingJob = clientScope.launch {
            while (isActive && _isConnected.value) {
                val now = SystemClock.elapsedRealtime()
                val pending = pingPendingTimestamp
                if (pending != null) {
                    val waitTime = now - pending
                    if (waitTime > 5000L) {
                        Log.w(TAG, "Bluetooth heartbeat timeout (${waitTime}ms), closing dead connection")
                        onConnectionLost()
                        break
                    } else if (waitTime > 400L) {
                        _latencyMs.value = waitTime
                    }
                } else if (now - lastPongTimestamp >= 2000L) {
                    pingPendingTimestamp = now
                    sendRaw(ProtocolPayloads.buildPingJson(now))
                }
                delay(400L)
            }
        }
    }

    private fun closeResourcesLocked() {
        wakeSignal?.close()
        wakeSignal = null
        clearPendingOutbound()
        try { outputStream?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { connectingSocket?.close() } catch (_: Exception) {}
        socket = null
        connectingSocket = null
        outputStream = null
        reader = null
    }

    private fun onConnectionLost() {
        synchronized(stateLock) {
            val wasConnectedOrConnecting = _isConnected.value || _isConnecting.value
            _isConnected.value = false
            _isConnecting.value = false
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
            _latencyMs.value = null
            pingPendingTimestamp = null
            if (wasConnectedOrConnecting) {
                _statusMessage.value = "连接已断开"
            }
            pingJob?.cancel()
            pingJob = null
            writeJob?.cancel()
            writeJob = null
            readJob?.cancel()
            readJob = null
            closeResourcesLocked()
        }
    }

    private fun disconnectLocked(resetStatus: Boolean = true) {
        connectJob?.cancel()
        connectJob = null
        _isConnected.value = false
        _isConnecting.value = false
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        _latencyMs.value = null
        pingPendingTimestamp = null
        if (resetStatus) {
            _statusMessage.value = "未连接"
        }
        pingJob?.cancel()
        pingJob = null
        writeJob?.cancel()
        writeJob = null
        readJob?.cancel()
        readJob = null
        closeResourcesLocked()
    }

    fun disconnect() {
        synchronized(stateLock) {
            disconnectLocked(resetStatus = true)
        }
    }

    fun destroy() {
        disconnect()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(btStateReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        clientScope.cancel()
    }

    fun sendRaw(data: String) {
        if (!_isConnected.value) return
        controlQueue.offer(data)
        wakeSignal?.trySend(Unit)
    }

    fun sendKey(key: String, action: String = "tap") {
        sendRaw(ProtocolPayloads.buildKeyJson(key, action = action))
    }

    fun sendMouse(dx: Float, dy: Float, action: String = "move", wheel: Float = 0f) {
        if (!_isConnected.value) return
        if (action == "move" && wheel == 0f) {
            synchronized(moveLock) {
                pendingMoveDx += dx
                pendingMoveDy += dy
                hasPendingMove = true
            }
            wakeSignal?.trySend(Unit)
            return
        }
        sendRaw(ProtocolPayloads.buildMouseJson(dx, dy, action = action, wheel = wheel))
    }

    fun sendPaste(text: String, enter: Boolean = false, mode: String = "terminal") {
        sendRaw(ProtocolPayloads.buildPasteJson(text, enter = enter, mode = mode))
    }
}
