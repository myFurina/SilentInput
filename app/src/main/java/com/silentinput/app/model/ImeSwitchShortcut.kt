package com.silentinput.app.model

/**
 * Normalizes and formats customizable PC IME toggle shortcuts shared across
 * settings, text sender quick action, and landscape keyboard utility bar.
 */
object ImeSwitchShortcut {
    const val DEFAULT = "shift"

    val PRESETS: List<String> = listOf(
        "shift",
        "ctrl+space",
        "super+space",
        "ctrl+shift",
        "alt+shift"
    )

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("off", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) {
            return ""
        }
        val tokens = trimmed.split('+')
            .mapNotNull { part ->
                val token = part.trim().lowercase()
                when {
                    token.isEmpty() -> null
                    token.any { it.isWhitespace() || it.isISOControl() } -> return ""
                    token == "win" || token == "meta" -> "super"
                    else -> token
                }
            }
        return tokens.joinToString("+")
    }

    fun isPreset(shortcut: String): Boolean {
        val normalized = normalize(shortcut)
        return normalized.isNotEmpty() && normalized in PRESETS
    }

    fun formatLabel(shortcut: String): String {
        val normalized = normalize(shortcut)
        if (normalized.isEmpty()) return ""
        return normalized.split('+').joinToString("+") { token ->
            when (token) {
                "ctrl" -> "Ctrl"
                "shift" -> "Shift"
                "alt" -> "Alt"
                "super" -> "Win"
                "space" -> "Space"
                "tab" -> "Tab"
                "enter" -> "Enter"
                "esc" -> "Esc"
                "backspace" -> "Backspace"
                "delete", "del" -> "Del"
                else -> if (token.length == 1) token.uppercase() else token.replaceFirstChar { it.uppercase() }
            }
        }
    }
}
