package com.silentinput.app.model

sealed class ServerMessage {
    data class Pong(val timestampMs: Long?) : ServerMessage()
    data class AuthResult(
        val status: String,
        val message: String,
        val unicodeDirectTyping: Boolean?,
        val udpPort: Int? = null,
        val udpSessionId: String? = null,
        val udpKey: String? = null,
        val hiResWheel: Boolean? = null
    ) : ServerMessage()
    data class Status(
        val unicodeDirectTyping: Boolean?,
        val version: String? = null,
        val hiResWheel: Boolean? = null
    ) : ServerMessage()
    data class PasteResult(
        val status: String,
        val message: String
    ) : ServerMessage()
    object AuthRequired : ServerMessage()
}

/**
 * Pure-Kotlin JSON payload builders and parsers shared by WebSocket and Bluetooth SPP
 * transports, keeping serialization deterministic and directly unit-testable on the JVM.
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

    fun buildMouseJson(
        dx: Float,
        dy: Float,
        action: String = "move",
        wheel: Float = 0f,
        hiResWheel: Boolean = false
    ): String {
        val safeDx = sanitizeFinite(dx)
        val safeDy = sanitizeFinite(dy)
        val safeWheel = sanitizeFinite(wheel)
        val wheelField = when {
            safeWheel == 0f -> ""
            hiResWheel -> ",\"wheel_hi\":${safeWheel.toInt()}"
            else -> ",\"wheel\":$safeWheel"
        }
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

    fun parseServerMessage(rawJson: String): ServerMessage? {
        val obj = SimpleJsonObjectParser(rawJson).parseRootObject() ?: return null
        return when (obj["type"] as? String) {
            "pong" -> {
                val t = (obj["t"] as? Number)?.toLong()
                ServerMessage.Pong(t)
            }
            "auth_result" -> {
                val status = (obj["status"] as? String).orEmpty()
                val message = (obj["message"] as? String).orEmpty()
                val unicode = obj["unicode_direct_typing"] as? Boolean
                val udpPort = (obj["udp_port"] as? Number)?.toInt()
                val udpSessionId = obj["udp_session_id"] as? String
                val udpKey = obj["udp_key"] as? String
                val hiResWheel = obj["hi_res_wheel"] as? Boolean
                ServerMessage.AuthResult(
                    status = status,
                    message = message,
                    unicodeDirectTyping = unicode,
                    udpPort = udpPort,
                    udpSessionId = udpSessionId,
                    udpKey = udpKey,
                    hiResWheel = hiResWheel
                )
            }
            "status" -> {
                val unicode = obj["unicode_direct_typing"] as? Boolean
                val version = obj["version"] as? String
                val hiResWheel = obj["hi_res_wheel"] as? Boolean
                ServerMessage.Status(unicode, version, hiResWheel)
            }
            "paste_result" -> {
                val status = (obj["status"] as? String).orEmpty()
                val message = (obj["message"] as? String).orEmpty()
                ServerMessage.PasteResult(status, message)
            }
            "auth_required" -> ServerMessage.AuthRequired
            else -> null
        }
    }
}

private class SimpleJsonObjectParser(private val input: String) {
    private var pos = 0

    fun parseRootObject(): Map<String, Any?>? {
        skipWhitespace()
        if (pos >= input.length || input[pos] != '{') return null
        val obj = parseObject() ?: return null
        skipWhitespace()
        return if (pos == input.length) obj else null
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= input.length) throw IllegalArgumentException("Unexpected end")
        return when (val ch = input[pos]) {
            '{' -> parseObject() ?: throw IllegalArgumentException("Invalid object")
            '[' -> parseArray()
            '"' -> parseString()
            't' -> expectLiteral("true", true)
            'f' -> expectLiteral("false", false)
            'n' -> expectLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected char: $ch")
        }
    }

    private fun parseObject(): Map<String, Any?>? {
        if (pos >= input.length || input[pos] != '{') return null
        pos++
        skipWhitespace()
        val result = linkedMapOf<String, Any?>()
        if (pos < input.length && input[pos] == '}') {
            pos++
            return result
        }
        try {
            while (true) {
                skipWhitespace()
                if (pos >= input.length || input[pos] != '"') return null
                val key = parseString()
                skipWhitespace()
                if (pos >= input.length || input[pos] != ':') return null
                pos++
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                if (pos >= input.length) return null
                when (input[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> return null
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseArray(): List<Any?> {
        pos++ // '['
        skipWhitespace()
        val list = mutableListOf<Any?>()
        if (pos < input.length && input[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(parseValue())
            skipWhitespace()
            if (pos >= input.length) throw IllegalArgumentException("Unterminated array")
            when (input[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> throw IllegalArgumentException("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening '"'
        val sb = StringBuilder()
        while (pos < input.length) {
            val ch = input[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= input.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = input[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > input.length) throw IllegalArgumentException("Invalid unicode escape")
                            val code = input.substring(pos, pos + 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape: \\$esc")
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (input[pos] == '-') pos++
        while (pos < input.length && input[pos] in '0'..'9') pos++
        var isFloat = false
        if (pos < input.length && input[pos] == '.') {
            isFloat = true
            pos++
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        val raw = input.substring(start, pos)
        return if (isFloat) raw.toDouble() else raw.toLong()
    }

    private fun <T> expectLiteral(literal: String, value: T): T {
        if (!input.startsWith(literal, pos)) {
            throw IllegalArgumentException("Expected $literal")
        }
        pos += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < input.length && input[pos].isWhitespace()) {
            pos++
        }
    }
}
