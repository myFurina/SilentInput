package com.silentinput.app.model

/**
 * Curated quick-edit buttons available on the Text tab horizontal snap bar,
 * customizable in More Settings.
 */
data class QuickEditKeySpec(
    val id: String,
    val repeatable: Boolean = false
)

object QuickEditKeys {
    const val IME_SWITCH_ID = "ime_switch"

    val DEFAULT_IDS: List<String> = listOf(
        "left",
        "right",
        "backspace",
        "ctrl+z",
        IME_SWITCH_ID
    )

    val ALL: List<QuickEditKeySpec> = listOf(
        QuickEditKeySpec("left", repeatable = true),
        QuickEditKeySpec("right", repeatable = true),
        QuickEditKeySpec("up", repeatable = true),
        QuickEditKeySpec("down", repeatable = true),
        QuickEditKeySpec("backspace", repeatable = true),
        QuickEditKeySpec("delete", repeatable = true),
        QuickEditKeySpec("ctrl+z", repeatable = false),
        QuickEditKeySpec(IME_SWITCH_ID, repeatable = false),
        QuickEditKeySpec("ctrl+a", repeatable = false),
        QuickEditKeySpec("ctrl+c", repeatable = false),
        QuickEditKeySpec("ctrl+v", repeatable = false),
        QuickEditKeySpec("tab", repeatable = false),
        QuickEditKeySpec("esc", repeatable = false),
        QuickEditKeySpec("home", repeatable = false),
        QuickEditKeySpec("end", repeatable = false)
    )

    private val byId: Map<String, QuickEditKeySpec> = ALL.associateBy { it.id }

    fun findSpec(id: String): QuickEditKeySpec? = byId[id]

    fun normalizeSelection(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>(raw.size)
        for (id in raw) {
            if (byId.containsKey(id)) {
                seen.add(id)
            }
        }
        return seen.toList()
    }

    fun toggleKey(current: List<String>, keyId: String): List<String> {
        if (!byId.containsKey(keyId)) return normalizeSelection(current)
        val normalized = normalizeSelection(current)
        return if (keyId in normalized) {
            normalized.filterNot { it == keyId }
        } else {
            normalized + keyId
        }
    }

    fun chipLabel(id: String, strings: AppStrings, imeSwitchKey: String): String {
        return when (id) {
            "left" -> "←"
            "right" -> "→"
            "up" -> "↑"
            "down" -> "↓"
            "backspace" -> strings.txtCursorBackspace
            "delete" -> "Del"
            "ctrl+z" -> strings.txtUndoBtn
            IME_SWITCH_ID -> {
                val active = imeSwitchKey.ifBlank { ImeSwitchShortcut.DEFAULT }
                strings.imeSwitchQuickChip(ImeSwitchShortcut.formatLabel(active))
            }
            "ctrl+a" -> strings.quickEdit.selectAll
            "ctrl+c" -> strings.quickEdit.copy
            "ctrl+v" -> strings.quickEdit.paste
            "tab" -> "Tab"
            "esc" -> "Esc"
            "home" -> strings.quickEdit.home
            "end" -> strings.quickEdit.end
            else -> id
        }
    }

    fun settingLabel(id: String, strings: AppStrings): String {
        val qe = strings.quickEdit
        return when (id) {
            "left" -> qe.optLeft
            "right" -> qe.optRight
            "up" -> qe.optUp
            "down" -> qe.optDown
            "backspace" -> qe.optBackspace
            "delete" -> qe.optDelete
            "ctrl+z" -> qe.optUndo
            IME_SWITCH_ID -> qe.optImeSwitch
            "ctrl+a" -> qe.optSelectAll
            "ctrl+c" -> qe.optCopy
            "ctrl+v" -> qe.optPaste
            "tab" -> "Tab"
            "esc" -> "Esc"
            "home" -> qe.optHome
            "end" -> qe.optEnd
            else -> id
        }
    }
}
