package com.silentinput.app.model

/**
 * Pure-Kotlin JSON payload builders shared by WebSocket and Bluetooth SPP transports,
 * keeping serialization deterministic and directly unit-testable on the JVM.
 */
object ProtocolPayloads {

    fun buildAuthJson(token: String): String {
        return """{"type":"auth","token":${quoteJson(token)}}"""
    }

    fun buildGetStatusJson(): String {
        return """{"type":"get_status"}"""
    }

    fun buildPingJson(timestampMs: Long): String {
        return """{"type":"ping","t":$timestampMs}"""
    }

    fun buildKeyJson(key: String, action: String = "tap"): String {
        return """{"type":"key","key":${quoteJson(key)},"action":${quoteJson(action)}}"""
    }

    fun buildMouseJson(dx: Float, dy: Float, action: String = "move", wheel: Float = 0f): String {
        val safeDx = sanitizeFinite(dx)
        val safeDy = sanitizeFinite(dy)
        val safeWheel = sanitizeFinite(wheel)
        val wheelField = if (safeWheel != 0f) ",\"wheel\":$safeWheel" else ""
        return """{"type":"mouse","dx":$safeDx,"dy":$safeDy,"action":${quoteJson(action)}$wheelField}"""
    }

    fun buildPasteJson(text: String, enter: Boolean = false, mode: String = "terminal"): String {
        val enterField = if (enter) ",\"enter\":true" else ""
        return """{"type":"paste","content":${quoteJson(text)},"mode":${quoteJson(mode)}$enterField}"""
    }

    fun formatKeyWithModifiers(
        keyName: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        superKey: Boolean = false
    ): String {
        return buildString {
            if (ctrl) append("ctrl+")
            if (alt) append("alt+")
            if (shift) append("shift+")
            if (superKey) append("super+")
            append(keyName)
        }
    }

    private fun sanitizeFinite(value: Float): Float {
        return if (value.isFinite()) {
            if (value == -0.0f) 0.0f else value
        } else {
            0.0f
        }
    }

    internal fun quoteJson(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
