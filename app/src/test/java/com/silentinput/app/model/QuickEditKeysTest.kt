package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEditKeysTest {

    @Test
    fun defaultsExcludeDeleteAndIncludeCoreKeys() {
        assertEquals(
            listOf("left", "right", "backspace", "ctrl+z", QuickEditKeys.IME_SWITCH_ID),
            QuickEditKeys.DEFAULT_IDS
        )
        assertFalse("delete" in QuickEditKeys.DEFAULT_IDS)
        assertNotNull(QuickEditKeys.findSpec("delete"))
        assertNull(QuickEditKeys.findSpec("nonexistent"))
    }

    @Test
    fun normalizeSelectionFiltersUnknownAndDeduplicates() {
        val normalized = QuickEditKeys.normalizeSelection(
            listOf("left", "unknown_key", "ctrl+c", "left", "backspace", "enter")
        )
        assertEquals(listOf("left", "ctrl+c", "backspace"), normalized)
    }

    @Test
    fun toggleKeyAddsRemovesAndIgnoresUnknown() {
        val withCopy = QuickEditKeys.toggleKey(QuickEditKeys.DEFAULT_IDS, "ctrl+c")
        assertTrue("ctrl+c" in withCopy)

        val withoutUndo = QuickEditKeys.toggleKey(withCopy, "ctrl+z")
        assertFalse("ctrl+z" in withoutUndo)

        val untouched = QuickEditKeys.toggleKey(withoutUndo, "bogus")
        assertEquals(withoutUndo, untouched)

        // Removing all keys produces empty list safely
        var list = QuickEditKeys.DEFAULT_IDS
        for (id in QuickEditKeys.DEFAULT_IDS) {
            list = QuickEditKeys.toggleKey(list, id)
        }
        assertTrue(list.isEmpty())
    }

    @Test
    fun labelsFormatCleanlyInZhAndEn() {
        assertEquals("←", QuickEditKeys.chipLabel("left", AppStrings.ZH, "shift"))
        assertEquals("退格", QuickEditKeys.chipLabel("backspace", AppStrings.ZH, "shift"))
        assertEquals("Bksp", QuickEditKeys.chipLabel("backspace", AppStrings.EN, "shift"))
        assertEquals(
            "Ctrl+Space",
            QuickEditKeys.chipLabel(QuickEditKeys.IME_SWITCH_ID, AppStrings.ZH, "ctrl+space")
        )
        assertTrue(
            QuickEditKeys.chipLabel(QuickEditKeys.IME_SWITCH_ID, AppStrings.EN, "")
                .contains("Shift")
        )
        assertEquals("自动回车", AppStrings.ZH.txtOptionEnter)
        assertEquals("Auto Enter", AppStrings.EN.txtOptionEnter)
    }
}
