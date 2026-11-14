package com.silentinput.app.model

enum class StatusTone {
    NORMAL,
    PENDING,
    HIGH_LATENCY,
    ERROR
}

data class StatusDisplay(
    val text: String,
    val tone: StatusTone
)

/**
 * Pure formatting helpers for top-bar and settings-dialog connection status indicators.
 */
object ConnectionStatusFormatter {

    fun shouldAutoReconnectBluetooth(
        mode: String,
        isBtConnected: Boolean,
        isBtConnecting: Boolean,
        manualDisconnected: Boolean,
        isBluetoothEnabled: Boolean,
        lastBtDeviceAddress: String?
    ): Boolean {
        return mode == "bluetooth" &&
            !isBtConnected &&
            !isBtConnecting &&
            !manualDisconnected &&
            isBluetoothEnabled &&
            !lastBtDeviceAddress.isNullOrBlank()
    }

    fun formatTopBarStatus(
        mode: String,
        isBtConnected: Boolean,
        isBtConnecting: Boolean,
        btDeviceName: String?,
        btLatencyMs: Long?,
        isNetConnected: Boolean,
        isNetAuthenticated: Boolean,
        isNetReconnecting: Boolean,
        isNetEncrypted: Boolean,
        netConnectionError: String?,
        netLatencyMs: Long?,
        activeServerIp: String,
        isNetConnecting: Boolean = false,
        language: ResolvedLanguage = ResolvedLanguage.ZH
    ): StatusDisplay {
        val s = AppStrings.forResolved(language)
        val isNetReady = isNetConnected && isNetAuthenticated
        return when (mode) {
            "bluetooth" -> {
                val devName = btDeviceName ?: s.statusFallbackPcName
                val latText = if (btLatencyMs != null) " (${btLatencyMs}ms)" else ""
                when {
                    isBtConnected -> StatusDisplay(s.statusBtConnectedTo(devName, latText), StatusTone.NORMAL)
                    isBtConnecting -> StatusDisplay(s.statusBtConnecting, StatusTone.PENDING)
                    else -> StatusDisplay(s.statusBtDisconnected, StatusTone.ERROR)
                }
            }
            "lan" -> {
                val prefix = s.statusLanPrefix
                val text = when {
                    isNetReady -> {
                        val encTag = if (isNetEncrypted) " 🔒" else s.statusPlaintextShortTag
                        val latText = formatLatencySuffix(netLatencyMs, markHighLatency = true, strings = s)
                        s.statusNetConnected(prefix, latText, encTag)
                    }
                    isNetConnected && !isNetAuthenticated -> s.statusVerifyingKey(prefix)
                    isNetConnecting -> s.statusConnecting(prefix)
                    isNetReconnecting -> if (netConnectionError != null) "$prefix: $netConnectionError" else s.statusReconnecting(prefix)
                    netConnectionError != null -> "$prefix: $netConnectionError"
                    else -> s.statusDisconnected(prefix)
                }
                val tone = when {
                    isNetReady && (netLatencyMs ?: 0L) >= 800L -> StatusTone.HIGH_LATENCY
                    isNetReady -> StatusTone.NORMAL
                    isNetConnected || isNetConnecting -> StatusTone.PENDING
                    isNetReconnecting && netConnectionError == null -> StatusTone.PENDING
                    else -> StatusTone.ERROR
                }
                StatusDisplay(text, tone)
            }
            else -> {
                val prefix = s.statusUsbPrefix
                val isLoopback = activeServerIp == "127.0.0.1"
                val text = when {
                    isNetReady && isLoopback -> {
                        val latText = formatLatencySuffix(netLatencyMs, markHighLatency = false, strings = s)
                        s.statusNetConnected(prefix, latText, "")
                    }
                    isNetConnected && !isNetAuthenticated && isLoopback -> s.statusVerifyingKey(prefix)
                    isNetConnecting && isLoopback -> s.statusConnecting(prefix)
                    isNetReconnecting -> if (netConnectionError != null) "$prefix: $netConnectionError" else s.statusReconnecting(prefix)
                    netConnectionError != null -> "$prefix: $netConnectionError"
                    else -> s.statusDisconnected(prefix)
                }
                val tone = when {
                    isNetReady && isLoopback && (netLatencyMs ?: 0L) >= 800L -> StatusTone.HIGH_LATENCY
                    isNetReady && isLoopback -> StatusTone.NORMAL
                    (isNetConnected && isLoopback) || (isNetConnecting && isLoopback) -> StatusTone.PENDING
                    isNetReconnecting && netConnectionError == null -> StatusTone.PENDING
                    else -> StatusTone.ERROR
                }
                StatusDisplay(text, tone)
            }
        }
    }

    fun formatUsbDialogStatus(
        isUsbConnected: Boolean,
        isUsbAuthenticated: Boolean,
        isNetEncrypted: Boolean,
        isNetReconnecting: Boolean,
        connError: String?,
        latencyMs: Long?,
        isUsbConnecting: Boolean = false,
        language: ResolvedLanguage = ResolvedLanguage.ZH
    ): StatusDisplay {
        val s = AppStrings.forResolved(language)
        return when {
            isUsbConnected && isUsbAuthenticated -> {
                val latStr = formatLatencySuffix(latencyMs, markHighLatency = false, strings = s)
                val encStr = if (isNetEncrypted) s.statusDialogTlsTag else s.statusDialogPlaintextTag
                StatusDisplay(s.statusDialogConnected(latStr, encStr), StatusTone.NORMAL)
            }
            isUsbConnected && !isUsbAuthenticated ->
                StatusDisplay(s.statusDialogVerifyingKey, StatusTone.PENDING)
            isUsbConnecting ->
                StatusDisplay(s.statusDialogConnecting, StatusTone.PENDING)
            connError != null ->
                StatusDisplay(s.statusDialogError(connError), StatusTone.ERROR)
            isNetReconnecting ->
                StatusDisplay(s.statusDialogReconnecting, StatusTone.PENDING)
            else ->
                StatusDisplay(s.statusDialogDisconnected, StatusTone.NORMAL)
        }
    }

    fun formatLanDialogStatus(
        isLanConnected: Boolean,
        isLanAuthenticated: Boolean,
        isNetEncrypted: Boolean,
        isNetReconnecting: Boolean,
        connError: String?,
        latencyMs: Long?,
        isLanConnecting: Boolean = false,
        language: ResolvedLanguage = ResolvedLanguage.ZH
    ): StatusDisplay {
        val s = AppStrings.forResolved(language)
        return when {
            isLanConnected && isLanAuthenticated -> {
                val latStr = formatLatencySuffix(latencyMs, markHighLatency = true, strings = s)
                val encStr = if (isNetEncrypted) s.statusDialogTlsTag else s.statusDialogPlaintextTag
                StatusDisplay(s.statusDialogConnected(latStr, encStr), StatusTone.NORMAL)
            }
            isLanConnected && !isLanAuthenticated ->
                StatusDisplay(s.statusDialogVerifyingPairingKey, StatusTone.PENDING)
            isLanConnecting ->
                StatusDisplay(s.statusDialogConnecting, StatusTone.PENDING)
            connError != null ->
                StatusDisplay(s.statusDialogError(connError), StatusTone.ERROR)
            isNetReconnecting ->
                StatusDisplay(s.statusDialogReconnecting, StatusTone.PENDING)
            else ->
                StatusDisplay(s.statusDialogDisconnected, StatusTone.NORMAL)
        }
    }

    private fun formatLatencySuffix(
        latencyMs: Long?,
        markHighLatency: Boolean,
        strings: AppStrings
    ): String {
        return when {
            latencyMs == null -> ""
            latencyMs <= 1L -> " (<1ms)"
            markHighLatency && latencyMs >= 1000L -> strings.statusHighLatencyTag(latencyMs)
            else -> " (${latencyMs}ms)"
        }
    }
}
