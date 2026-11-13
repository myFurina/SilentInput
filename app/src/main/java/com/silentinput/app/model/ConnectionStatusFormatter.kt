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
        activeServerIp: String
    ): StatusDisplay {
        val isNetReady = isNetConnected && isNetAuthenticated
        return when (mode) {
            "bluetooth" -> {
                val devName = btDeviceName ?: "电脑"
                val latText = if (btLatencyMs != null) " (${btLatencyMs}ms)" else ""
                when {
                    isBtConnected -> StatusDisplay("蓝牙: 已连接至 $devName$latText", StatusTone.NORMAL)
                    isBtConnecting -> StatusDisplay("蓝牙: 正在连接...", StatusTone.PENDING)
                    else -> StatusDisplay("蓝牙: 未连接", StatusTone.ERROR)
                }
            }
            "lan" -> {
                val text = when {
                    isNetReady -> {
                        val encTag = if (isNetEncrypted) " 🔒" else " (明文)"
                        val latText = formatLatencySuffix(netLatencyMs, markHighLatency = true)
                        "局域网: 已连接$latText$encTag"
                    }
                    isNetConnected && !isNetAuthenticated -> "局域网: 正在验证密钥..."
                    isNetReconnecting -> if (netConnectionError != null) "局域网: $netConnectionError" else "局域网: 正在重连..."
                    netConnectionError != null -> "局域网: $netConnectionError"
                    else -> "局域网: 未连接"
                }
                val tone = when {
                    isNetReady && (netLatencyMs ?: 0L) >= 800L -> StatusTone.HIGH_LATENCY
                    isNetReady -> StatusTone.NORMAL
                    isNetConnected -> StatusTone.PENDING
                    isNetReconnecting && netConnectionError == null -> StatusTone.PENDING
                    else -> StatusTone.ERROR
                }
                StatusDisplay(text, tone)
            }
            else -> {
                val isLoopback = activeServerIp == "127.0.0.1"
                val text = when {
                    isNetReady && isLoopback -> {
                        val latText = formatLatencySuffix(netLatencyMs, markHighLatency = false)
                        "USB: 已连接$latText"
                    }
                    isNetConnected && !isNetAuthenticated && isLoopback -> "USB: 正在验证密钥..."
                    isNetReconnecting -> if (netConnectionError != null) "USB: $netConnectionError" else "USB: 正在重连..."
                    netConnectionError != null -> "USB: $netConnectionError"
                    else -> "USB: 未连接"
                }
                val tone = when {
                    isNetReady && isLoopback && (netLatencyMs ?: 0L) >= 800L -> StatusTone.HIGH_LATENCY
                    isNetReady && isLoopback -> StatusTone.NORMAL
                    isNetConnected && isLoopback -> StatusTone.PENDING
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
        latencyMs: Long?
    ): StatusDisplay {
        return when {
            isUsbConnected && isUsbAuthenticated -> {
                val latStr = formatLatencySuffix(latencyMs, markHighLatency = false)
                val encStr = if (isNetEncrypted) " [TLS加密]" else " [明文传输]"
                StatusDisplay("状态: 已连接$latStr$encStr", StatusTone.NORMAL)
            }
            isUsbConnected && !isUsbAuthenticated ->
                StatusDisplay("状态: 正在验证密钥...", StatusTone.PENDING)
            connError != null ->
                StatusDisplay("状态: $connError", StatusTone.ERROR)
            isNetReconnecting ->
                StatusDisplay("状态: 正在重连...", StatusTone.PENDING)
            else ->
                StatusDisplay("状态: 未连接", StatusTone.NORMAL)
        }
    }

    fun formatLanDialogStatus(
        isLanConnected: Boolean,
        isLanAuthenticated: Boolean,
        isNetEncrypted: Boolean,
        isNetReconnecting: Boolean,
        connError: String?,
        latencyMs: Long?
    ): StatusDisplay {
        return when {
            isLanConnected && isLanAuthenticated -> {
                val latStr = formatLatencySuffix(latencyMs, markHighLatency = true)
                val encStr = if (isNetEncrypted) " [TLS加密]" else " [明文传输]"
                StatusDisplay("状态: 已连接$latStr$encStr", StatusTone.NORMAL)
            }
            isLanConnected && !isLanAuthenticated ->
                StatusDisplay("状态: 正在验证配对密钥...", StatusTone.PENDING)
            connError != null ->
                StatusDisplay("状态: $connError", StatusTone.ERROR)
            isNetReconnecting ->
                StatusDisplay("状态: 正在重连...", StatusTone.PENDING)
            else ->
                StatusDisplay("状态: 未连接", StatusTone.NORMAL)
        }
    }

    private fun formatLatencySuffix(latencyMs: Long?, markHighLatency: Boolean): String {
        return when {
            latencyMs == null -> ""
            latencyMs <= 1L -> " (<1ms)"
            markHighLatency && latencyMs >= 1000L -> " (${latencyMs}ms 卡顿/高延迟)"
            else -> " (${latencyMs}ms)"
        }
    }
}
