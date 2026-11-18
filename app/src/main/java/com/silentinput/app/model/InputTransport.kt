package com.silentinput.app.model

import kotlinx.coroutines.flow.StateFlow

/**
 * Common transport abstraction implemented by WebSocket ([com.silentinput.app.network.NetworkClient])
 * and Bluetooth SPP ([com.silentinput.app.bluetooth.BluetoothCompanionClient]).
 */
interface InputTransport {
    val isTransportReady: StateFlow<Boolean>
    val supportsUnicodeDirectTyping: StateFlow<Boolean>

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
