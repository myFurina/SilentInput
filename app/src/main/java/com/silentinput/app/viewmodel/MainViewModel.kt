package com.silentinput.app.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silentinput.app.bluetooth.BluetoothCompanionClient
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.AppStrings
import com.silentinput.app.model.ConnectionMode
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.ImeSwitchShortcut
import com.silentinput.app.model.InputTransport
import com.silentinput.app.model.MouseAction
import com.silentinput.app.model.MouseButton
import com.silentinput.app.model.SendTargetMode
import com.silentinput.app.network.NetworkClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val BACKGROUND_DISCONNECT_DELAY_MS = 30_000L
        private const val PREFS_NAME = "silentinput_prefs"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_LAN_IP = "lan_ip"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_TLS_ENCRYPTION = "tls_encryption"
        private const val KEY_LAST_BT_ADDRESS = "last_bt_device_address"
        private const val KEY_BT_MANUAL_DISCONNECTED = "bt_manual_disconnected"
        private const val KEY_FAVORITE_BT_ADDRESSES = "favorite_bt_addresses"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_TEXT_INLINE_TRACKPAD = "text_inline_trackpad"
        private const val KEY_NATURAL_SCROLLING = "natural_scrolling"
        private const val KEY_IME_SWITCH_SHORTCUT = "ime_switch_shortcut"
        private const val KEY_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"
        private const val KEY_SEND_TARGET_MODE = "send_target_mode"
        private const val KEY_CLEAR_ON_SEND = "clear_on_send"
        private const val KEY_ENTER_ON_SEND = "enter_on_send"
        private const val KEY_CUSTOM_SNIPPETS_JSON = "custom_snippets_json"
        private const val KEY_LONG_TEXT_DRAFT = "long_text_draft"

        private val DEFAULT_SNIPPETS = listOf("ls -la", "git status", "ujust update", "clear", "python3 ")
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _appLanguage = MutableStateFlow(
        AppLanguage.fromPref(prefs.getString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.prefValue))
    )
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    private val networkClient = NetworkClient(application, initialLanguage = _appLanguage.value)
    private val btClient = BluetoothCompanionClient(application, initialLanguage = _appLanguage.value)
    private var backgroundGraceJob: Job? = null

    private val _connectionMode = MutableStateFlow(prefs.getString(KEY_CONNECTION_MODE, "usb") ?: "usb")
    val connectionMode: StateFlow<String> = _connectionMode.asStateFlow()

    private val _activeServerIp = MutableStateFlow(prefs.getString(KEY_SERVER_IP, "127.0.0.1") ?: "127.0.0.1")
    val activeServerIp: StateFlow<String> = _activeServerIp.asStateFlow()

    private val _lanIp = MutableStateFlow(
        prefs.getString(
            KEY_LAN_IP,
            _activeServerIp.value.takeIf { it != "127.0.0.1" } ?: ""
        ) ?: ""
    )
    val lanIp: StateFlow<String> = _lanIp.asStateFlow()

    private val _savedAuthToken = MutableStateFlow(prefs.getString(KEY_AUTH_TOKEN, "") ?: "")
    val savedAuthToken: StateFlow<String> = _savedAuthToken.asStateFlow()

    private val _isTlsEnabled = MutableStateFlow(prefs.getBoolean(KEY_TLS_ENCRYPTION, true))
    val isTlsEnabled: StateFlow<Boolean> = _isTlsEnabled.asStateFlow()

    private val _hasBtPermission = MutableStateFlow(false)
    val hasBtPermission: StateFlow<Boolean> = _hasBtPermission.asStateFlow()

    private val _bondedBtDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedBtDevices: StateFlow<List<BluetoothDevice>> = _bondedBtDevices.asStateFlow()

    private val _lastBtDeviceAddress = MutableStateFlow(prefs.getString(KEY_LAST_BT_ADDRESS, null))
    val lastBtDeviceAddress: StateFlow<String?> = _lastBtDeviceAddress.asStateFlow()

    private val _isBtManualDisconnected = MutableStateFlow(
        prefs.getBoolean(KEY_BT_MANUAL_DISCONNECTED, false)
    )

    private val _favoriteBtAddresses = MutableStateFlow(
        prefs.getStringSet(KEY_FAVORITE_BT_ADDRESSES, emptySet())?.toSet() ?: emptySet()
    )
    val favoriteBtAddresses: StateFlow<Set<String>> = _favoriteBtAddresses.asStateFlow()

    private val _isHapticEnabled = MutableStateFlow(prefs.getBoolean(KEY_HAPTIC_ENABLED, false))
    val isHapticEnabled: StateFlow<Boolean> = _isHapticEnabled.asStateFlow()

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean(KEY_DARK_MODE, true))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _showInlineTrackpadInText = MutableStateFlow(prefs.getBoolean(KEY_TEXT_INLINE_TRACKPAD, true))
    val showInlineTrackpadInText: StateFlow<Boolean> = _showInlineTrackpadInText.asStateFlow()

    private val _isNaturalScrolling = MutableStateFlow(prefs.getBoolean(KEY_NATURAL_SCROLLING, true))
    val isNaturalScrolling: StateFlow<Boolean> = _isNaturalScrolling.asStateFlow()

    private val _imeSwitchKey = MutableStateFlow(
        ImeSwitchShortcut.normalize(
            prefs.getString(KEY_IME_SWITCH_SHORTCUT, ImeSwitchShortcut.DEFAULT)
                ?: ImeSwitchShortcut.DEFAULT
        )
    )
    val imeSwitchKey: StateFlow<String> = _imeSwitchKey.asStateFlow()

    private val _trackpadSensitivity = MutableStateFlow(
        prefs.getFloat(KEY_TRACKPAD_SENSITIVITY, 0.6f).coerceIn(0.1f, 2.0f)
    )
    val trackpadSensitivity: StateFlow<Float> = _trackpadSensitivity.asStateFlow()

    private val _sendTargetMode = MutableStateFlow(
        SendTargetMode.fromWire(prefs.getString(KEY_SEND_TARGET_MODE, SendTargetMode.TERMINAL.wireValue)).wireValue
    )
    val sendTargetMode: StateFlow<String> = _sendTargetMode.asStateFlow()

    private val _clearOnSend = MutableStateFlow(prefs.getBoolean(KEY_CLEAR_ON_SEND, true))
    val clearOnSend: StateFlow<Boolean> = _clearOnSend.asStateFlow()

    private val _enterOnSend = MutableStateFlow(prefs.getBoolean(KEY_ENTER_ON_SEND, false))
    val enterOnSend: StateFlow<Boolean> = _enterOnSend.asStateFlow()

    private val _snippets = MutableStateFlow(loadSnippets())
    val snippets: StateFlow<List<String>> = _snippets.asStateFlow()

    private val _longTextInput = MutableStateFlow(prefs.getString(KEY_LONG_TEXT_DRAFT, "") ?: "")
    val longTextInput: StateFlow<String> = _longTextInput.asStateFlow()

    private val _isLeftMouseHeld = MutableStateFlow(false)
    val isLeftMouseHeld: StateFlow<Boolean> = _isLeftMouseHeld.asStateFlow()

    private var lanErrorFormatter: ((AppStrings) -> String)? = null
    private val _lanInputError = MutableStateFlow<String?>(null)
    val lanInputError: StateFlow<String?> = _lanInputError.asStateFlow()

    // Delegated network state
    val isNetConnected: StateFlow<Boolean> = networkClient.isConnected
    val isNetAuthenticated: StateFlow<Boolean> = networkClient.isAuthenticated
    val isNetConnecting: StateFlow<Boolean> = networkClient.isConnecting
    val isNetReconnecting: StateFlow<Boolean> = networkClient.isReconnecting
    val isNetEncrypted: StateFlow<Boolean> = networkClient.isEncrypted
    val netConnectionError: StateFlow<String?> = networkClient.connectionError
    val netLatencyMs: StateFlow<Long?> = networkClient.latencyMs
    val certMismatchHost: StateFlow<String?> = networkClient.certMismatchHost
    val savedCertFingerprint: StateFlow<String?> = networkClient.savedCertFingerprint
    val pendingCertFingerprint: StateFlow<String?> = networkClient.pendingCertFingerprint

    // Delegated Bluetooth SPP companion state
    val isBtConnected: StateFlow<Boolean> = btClient.isConnected
    val isBtConnecting: StateFlow<Boolean> = btClient.isConnecting
    val isBtAdapterEnabled: StateFlow<Boolean> = btClient.isBluetoothEnabledFlow
    val btConnectedDeviceName: StateFlow<String?> = btClient.connectedDeviceName
    val btLatencyMs: StateFlow<Long?> = btClient.latencyMs
    val btStatusMessage: StateFlow<String?> = btClient.statusMessage

    val isNetReady: StateFlow<Boolean> = combine(isNetConnected, isNetAuthenticated) { conn, auth ->
        conn && auth
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isAnyConnected: StateFlow<Boolean> = combine(isNetReady, isBtConnected) { netReady, btConn ->
        netReady || btConn
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isCurrentModeConnected: StateFlow<Boolean> = combine(
        connectionMode,
        isBtConnected,
        isNetReady
    ) { mode, btConn, netReady ->
        if (mode == "bluetooth") btConn else netReady
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val supportsUnicodeDirectTyping: StateFlow<Boolean> = combine(
        connectionMode,
        isBtConnected,
        btClient.supportsUnicodeDirectTyping,
        isNetReady,
        networkClient.supportsUnicodeDirectTyping
    ) { mode, btConn, btSupports, netReady, netSupports ->
        when {
            mode == "bluetooth" && btConn -> btSupports
            mode != "bluetooth" && netReady -> netSupports
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        networkClient.serverIp = _activeServerIp.value
        networkClient.serverPort = NetworkClient.DEFAULT_PORT
        networkClient.authToken = if (_connectionMode.value == ConnectionMode.USB.wireValue) "" else _savedAuthToken.value
        networkClient.isTlsEnabled = _isTlsEnabled.value
        btClient.onAdapterStateChanged = {
            refreshBondedDevices()
        }
        viewModelScope.launch {
            isAnyConnected.collectLatest { connected ->
                if (!connected && _isLeftMouseHeld.value) {
                    _isLeftMouseHeld.value = false
                }
            }
        }
    }

    fun updateBluetoothPermission(granted: Boolean) {
        refreshBluetoothState(granted)
    }

    fun refreshBluetoothState(btPermissionGranted: Boolean? = null) {
        if (btPermissionGranted != null) {
            _hasBtPermission.value = btPermissionGranted
        }
        btClient.refreshBluetoothEnabled()
        refreshBondedDevices()
    }

    fun refreshBondedDevices() {
        _bondedBtDevices.value = if (_hasBtPermission.value && btClient.refreshBluetoothEnabled()) {
            btClient.getBondedDevices()
        } else {
            emptyList()
        }
    }

    fun tryLegacySetBluetoothEnabled(enable: Boolean): Boolean {
        val handled = btClient.setBluetoothEnabled(enable)
        if (handled) {
            _bondedBtDevices.value = if (enable && _hasBtPermission.value) {
                btClient.getBondedDevices()
            } else {
                emptyList()
            }
        }
        return handled
    }

    fun toggleFavoriteBtDevice(address: String) {
        val updated = _favoriteBtAddresses.value.toMutableSet().apply {
            if (contains(address)) remove(address) else add(address)
        }.toSet()
        _favoriteBtAddresses.value = updated
        prefs.edit().putStringSet(KEY_FAVORITE_BT_ADDRESSES, updated).apply()
    }

    fun isBluetoothEnabled(): Boolean = btClient.refreshBluetoothEnabled()

    fun onResume(btPermissionGranted: Boolean) {
        backgroundGraceJob?.cancel()
        backgroundGraceJob = null
        networkClient.refreshLanguageIfSystem()
        btClient.refreshLanguageIfSystem()
        refreshBluetoothState(btPermissionGranted)
        networkClient.exitBackground()
        btClient.exitBackground()
        if (_connectionMode.value == "bluetooth") {
            val lastAddress = _lastBtDeviceAddress.value
            if (ConnectionStatusFormatter.shouldAutoReconnectBluetooth(
                    mode = _connectionMode.value,
                    isBtConnected = btClient.isConnected.value,
                    isBtConnecting = btClient.isConnecting.value,
                    manualDisconnected = _isBtManualDisconnected.value,
                    isBluetoothEnabled = btClient.refreshBluetoothEnabled(),
                    lastBtDeviceAddress = lastAddress
                )
            ) {
                val dev = _bondedBtDevices.value.firstOrNull { it.address == lastAddress }
                if (dev != null) {
                    btClient.connect(dev)
                }
            }
        }
    }

    fun onStop() {
        prefs.edit().putString(KEY_LONG_TEXT_DRAFT, _longTextInput.value).apply()
        backgroundGraceJob?.cancel()
        networkClient.enterBackground()
        btClient.enterBackground()
        backgroundGraceJob = viewModelScope.launch {
            delay(BACKGROUND_DISCONNECT_DELAY_MS)
            networkClient.suspendForBackground()
            btClient.suspendForBackground()
        }
    }

    private fun setLanInputError(formatter: ((AppStrings) -> String)?) {
        lanErrorFormatter = formatter
        _lanInputError.value = formatter?.invoke(AppStrings.forLanguage(_appLanguage.value))
    }

    fun clearLanInputError() {
        setLanInputError(null)
    }

    fun connectUsb() {
        setLanInputError(null)
        btClient.disconnect()
        networkClient.serverIp = "127.0.0.1"
        networkClient.serverPort = NetworkClient.DEFAULT_PORT
        networkClient.authToken = ""
        networkClient.isTlsEnabled = _isTlsEnabled.value
        prefs.edit()
            .putString(KEY_CONNECTION_MODE, ConnectionMode.USB.wireValue)
            .putString(KEY_SERVER_IP, "127.0.0.1")
            .apply()
        _connectionMode.value = ConnectionMode.USB.wireValue
        _activeServerIp.value = "127.0.0.1"
        networkClient.connect()
    }

    fun connectLan(ip: String, token: String, tlsEnabled: Boolean) {
        val cleanIp = ip.trim()
        val cleanToken = token.trim()
        if (cleanIp.isEmpty()) {
            setLanInputError { it.netErrEmptyIp }
            return
        }
        val endpoint = NetworkClient.parseServerEndpoint(cleanIp, NetworkClient.DEFAULT_PORT)
        if (endpoint == null) {
            setLanInputError { it.netErrInvalidIp }
            return
        }
        if (!NetworkClient.isLoopbackHost(endpoint.host) && cleanToken.isBlank()) {
            setLanInputError { it.netErrEmptyToken }
            return
        }

        setLanInputError(null)
        btClient.disconnect()
        networkClient.serverIp = cleanIp
        networkClient.serverPort = NetworkClient.DEFAULT_PORT
        networkClient.authToken = cleanToken
        networkClient.isTlsEnabled = tlsEnabled
        prefs.edit()
            .putString(KEY_CONNECTION_MODE, ConnectionMode.LAN.wireValue)
            .putString(KEY_SERVER_IP, cleanIp)
            .putString(KEY_LAN_IP, cleanIp)
            .putString(KEY_AUTH_TOKEN, cleanToken)
            .putBoolean(KEY_TLS_ENCRYPTION, tlsEnabled)
            .apply()
        _connectionMode.value = ConnectionMode.LAN.wireValue
        _activeServerIp.value = cleanIp
        _lanIp.value = cleanIp
        _savedAuthToken.value = cleanToken
        _isTlsEnabled.value = tlsEnabled
        networkClient.connect()
    }

    fun disconnectNetwork() {
        setLanInputError(null)
        networkClient.disconnect()
    }

    fun toggleTlsEncryption(enabled: Boolean) {
        _isTlsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_TLS_ENCRYPTION, enabled).apply()
        networkClient.isTlsEnabled = enabled
        if (_connectionMode.value == ConnectionMode.LAN.wireValue && (isNetConnected.value || isNetReconnecting.value)) {
            networkClient.connect()
        }
    }

    fun trustNewCertificateAndReconnect() {
        networkClient.trustNewCertificateAndReconnect()
    }

    fun getPinnedCertFingerprint(ip: String): String? {
        return networkClient.getPinnedFingerprint(ip, NetworkClient.DEFAULT_PORT)
    }

    fun clearPinnedCertificate(ip: String): Boolean {
        return networkClient.clearPinnedCertificate(ip, NetworkClient.DEFAULT_PORT)
    }

    fun connectBluetooth(device: BluetoothDevice) {
        networkClient.disconnect()
        _isBtManualDisconnected.value = false
        prefs.edit()
            .putString(KEY_CONNECTION_MODE, ConnectionMode.BLUETOOTH.wireValue)
            .putString(KEY_LAST_BT_ADDRESS, device.address)
            .putBoolean(KEY_BT_MANUAL_DISCONNECTED, false)
            .apply()
        _connectionMode.value = ConnectionMode.BLUETOOTH.wireValue
        _lastBtDeviceAddress.value = device.address
        btClient.connect(device)
    }

    fun disconnectBluetooth() {
        _isBtManualDisconnected.value = true
        prefs.edit().putBoolean(KEY_BT_MANUAL_DISCONNECTED, true).apply()
        btClient.disconnect()
    }

    private fun activeTransport(): InputTransport? {
        return when {
            btClient.isTransportReady.value -> btClient
            networkClient.isTransportReady.value -> networkClient
            else -> null
        }
    }

    // Unified input dispatchers
    fun sendKey(key: String, action: String = "tap") {
        activeTransport()?.sendKey(key, action)
    }

    fun sendMouseMove(dx: Float, dy: Float) {
        activeTransport()?.sendMouse(dx, dy, MouseAction.MOVE.wireValue)
    }

    fun sendMouseClick(button: String) {
        if (button == MouseButton.LEFT.wireValue) {
            _isLeftMouseHeld.value = false
        }
        sendMouseAction("click_$button")
    }

    fun setLeftMouseHold(pressed: Boolean) {
        if (pressed && !isAnyConnected.value) {
            _isLeftMouseHeld.value = false
            return
        }
        if (_isLeftMouseHeld.value == pressed) return
        _isLeftMouseHeld.value = pressed
        if (isAnyConnected.value) {
            val action = if (pressed) MouseAction.DOWN_LEFT else MouseAction.UP_LEFT
            sendMouseAction(action.wireValue)
        }
    }

    fun sendMouseAction(action: String) {
        activeTransport()?.sendMouse(0f, 0f, action)
    }

    fun sendMouseScroll(wheel: Float) {
        activeTransport()?.sendMouse(0f, 0f, MouseAction.SCROLL.wireValue, wheel = wheel)
    }

    fun sendTextPaste(text: String) {
        if (text.isBlank()) return
        val enter = _enterOnSend.value
        val mode = _sendTargetMode.value
        val sent = activeTransport()?.sendPaste(text, enter = enter, mode = mode) == true
        if (sent && _clearOnSend.value) {
            _longTextInput.value = ""
            prefs.edit().remove(KEY_LONG_TEXT_DRAFT).apply()
        }
    }

    // UI preferences & draft state updates
    fun updateLongTextInput(text: String) {
        _longTextInput.value = text
        if (text.isEmpty()) {
            prefs.edit().remove(KEY_LONG_TEXT_DRAFT).apply()
        }
    }

    fun updateTrackpadSensitivity(value: Float, persist: Boolean = true) {
        val clamped = value.coerceIn(0.1f, 2.0f)
        _trackpadSensitivity.value = clamped
        if (persist) {
            prefs.edit().putFloat(KEY_TRACKPAD_SENSITIVITY, clamped).apply()
        }
    }

    fun commitTrackpadSensitivity() {
        prefs.edit().putFloat(KEY_TRACKPAD_SENSITIVITY, _trackpadSensitivity.value).apply()
    }

    fun setSendTargetMode(mode: String) {
        _sendTargetMode.value = mode
        prefs.edit().putString(KEY_SEND_TARGET_MODE, mode).apply()
    }

    fun setClearOnSend(enabled: Boolean) {
        _clearOnSend.value = enabled
        prefs.edit().putBoolean(KEY_CLEAR_ON_SEND, enabled).apply()
    }

    fun setEnterOnSend(enabled: Boolean) {
        _enterOnSend.value = enabled
        prefs.edit().putBoolean(KEY_ENTER_ON_SEND, enabled).apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        _isHapticEnabled.value = enabled
        prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun toggleDarkMode() {
        val next = !_isDarkMode.value
        _isDarkMode.value = next
        prefs.edit().putBoolean(KEY_DARK_MODE, next).apply()
    }

    fun setShowInlineTrackpadInText(enabled: Boolean) {
        _showInlineTrackpadInText.value = enabled
        prefs.edit().putBoolean(KEY_TEXT_INLINE_TRACKPAD, enabled).apply()
    }

    fun setNaturalScrolling(enabled: Boolean) {
        _isNaturalScrolling.value = enabled
        prefs.edit().putBoolean(KEY_NATURAL_SCROLLING, enabled).apply()
    }

    fun setImeSwitchKey(rawShortcut: String) {
        val normalized = ImeSwitchShortcut.normalize(rawShortcut)
        _imeSwitchKey.value = normalized
        prefs.edit().putString(KEY_IME_SWITCH_SHORTCUT, normalized).apply()
    }

    fun setAppLanguage(language: AppLanguage) {
        if (_appLanguage.value == language) return
        _appLanguage.value = language
        prefs.edit().putString(KEY_APP_LANGUAGE, language.prefValue).apply()
        lanErrorFormatter?.let { formatter ->
            _lanInputError.value = formatter(AppStrings.forLanguage(language))
        }
        networkClient.updateLanguage(language)
        btClient.updateLanguage(language)
    }

    fun addSnippet(rawSnippet: String) {
        val trimmed = rawSnippet.trim()
        if (trimmed.isEmpty()) return
        val updated = _snippets.value + trimmed
        _snippets.value = updated
        saveSnippets(updated)
    }

    fun deleteSnippet(index: Int) {
        val current = _snippets.value
        if (index !in current.indices) return
        val updated = current.toMutableList().also { it.removeAt(index) }
        _snippets.value = updated
        saveSnippets(updated)
    }

    private fun loadSnippets(): List<String> {
        val jsonStr = prefs.getString(KEY_CUSTOM_SNIPPETS_JSON, null) ?: return DEFAULT_SNIPPETS
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            if (list.isEmpty()) DEFAULT_SNIPPETS else list
        } catch (_: Exception) {
            DEFAULT_SNIPPETS
        }
    }

    private fun saveSnippets(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_CUSTOM_SNIPPETS_JSON, arr.toString()).apply()
    }

    override fun onCleared() {
        super.onCleared()
        backgroundGraceJob?.cancel()
        backgroundGraceJob = null
        btClient.destroy()
        networkClient.destroy()
    }
}
