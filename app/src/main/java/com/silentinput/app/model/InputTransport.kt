package com.silentinput.app.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Common transport abstraction implemented by WebSocket ([com.silentinput.app.network.NetworkClient])
 * and Bluetooth SPP ([com.silentinput.app.bluetooth.BluetoothCompanionClient]).
 */
interface InputTransport {
    val isTransportReady: StateFlow<Boolean>
    val supportsUnicodeDirectTyping: StateFlow<Boolean>
    val supportsHiResWheel: StateFlow<Boolean>
    val daemonVersion: StateFlow<String?>
    val pasteQueueBusyEvent: SharedFlow<String>

    fun sendKey(key: String, action: String = "tap"): Boolean
    fun sendMouse(dx: Float, dy: Float, action: String = "move", wheel: Float = 0f): Boolean
    fun sendPaste(text: String, enter: Boolean = false, mode: String = SendTargetMode.TERMINAL.wireValue): Boolean

    fun updateLanguage(language: AppLanguage)
    fun refreshLanguageIfSystem()
    fun enterBackground()
    fun suspendForBackground()
    fun exitBackground()
    fun destroy()
}

/**
 * Common base class providing shared StateFlows, event channels, and language state
 * for both WebSocket and Bluetooth SPP transports.
 */
abstract class BaseInputTransport(
    initialLanguage: AppLanguage
) : InputTransport {

    protected val _supportsUnicodeDirectTyping = MutableStateFlow(true)
    override val supportsUnicodeDirectTyping: StateFlow<Boolean> = _supportsUnicodeDirectTyping.asStateFlow()

    protected val _supportsHiResWheel = MutableStateFlow(false)
    override val supportsHiResWheel: StateFlow<Boolean> = _supportsHiResWheel.asStateFlow()

    protected val _daemonVersion = MutableStateFlow<String?>(null)
    override val daemonVersion: StateFlow<String?> = _daemonVersion.asStateFlow()

    protected val _pasteQueueBusyEvent = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val pasteQueueBusyEvent: SharedFlow<String> = _pasteQueueBusyEvent.asSharedFlow()

    @Volatile
    var appLanguage: AppLanguage = initialLanguage

    protected fun currentStrings(): AppStrings = AppStrings.forLanguage(appLanguage)

    override fun refreshLanguageIfSystem() {
        if (appLanguage == AppLanguage.SYSTEM) {
            updateLanguage(AppLanguage.SYSTEM)
        }
    }
}
