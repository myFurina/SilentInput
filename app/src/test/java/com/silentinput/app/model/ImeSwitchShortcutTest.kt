package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSwitchShortcutTest {

    @Test
    fun `normalize cleans spacing, aliases win and meta to super, and treats off or empty as blank`() {
        assertEquals("ctrl+space", ImeSwitchShortcut.normalize("  Ctrl + Space  "))
        assertEquals("super+space", ImeSwitchShortcut.normalize("win+space"))
        assertEquals("super+shift", ImeSwitchShortcut.normalize("META + Shift"))
        assertEquals("", ImeSwitchShortcut.normalize(""))
        assertEquals("", ImeSwitchShortcut.normalize("   "))
        assertEquals("", ImeSwitchShortcut.normalize("off"))
        assertEquals("", ImeSwitchShortcut.normalize("NONE"))
        assertEquals("", ImeSwitchShortcut.normalize("ctrl + my key"))
    }

    @Test
    fun `isPreset recognizes normalized presets and rejects custom or hidden values`() {
        assertTrue(ImeSwitchShortcut.isPreset("shift"))
        assertTrue(ImeSwitchShortcut.isPreset("Ctrl + Space"))
        assertTrue(ImeSwitchShortcut.isPreset("win+space"))
        assertFalse(ImeSwitchShortcut.isPreset("alt+grave"))
        assertFalse(ImeSwitchShortcut.isPreset("off"))
        assertFalse(ImeSwitchShortcut.isPreset(""))
    }

    @Test
    fun `formatLabel produces human-readable shortcut labels`() {
        assertEquals("Shift", ImeSwitchShortcut.formatLabel("shift"))
        assertEquals("Ctrl+Space", ImeSwitchShortcut.formatLabel("ctrl+space"))
        assertEquals("Win+Space", ImeSwitchShortcut.formatLabel("super+space"))
        assertEquals("Win+Space", ImeSwitchShortcut.formatLabel("win+space"))
        assertEquals("Alt+Grave", ImeSwitchShortcut.formatLabel("alt+grave"))
        assertEquals("", ImeSwitchShortcut.formatLabel("off"))
    }
}
