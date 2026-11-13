package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtocolPayloadsTest {

    @Test
    fun `buildAuthJson formats token safely`() {
        assertEquals(
            """{"type":"auth","token":"abc\"123\\def"}""",
            ProtocolPayloads.buildAuthJson("""abc"123\def""")
        )
    }

    @Test
    fun `buildPingJson formats timestamp`() {
        assertEquals(
            """{"type":"ping","t":123456789}""",
            ProtocolPayloads.buildPingJson(123456789L)
        )
    }

    @Test
    fun `buildKeyJson formats key and action`() {
        assertEquals(
            """{"type":"key","key":"ctrl+c","action":"tap"}""",
            ProtocolPayloads.buildKeyJson("ctrl+c")
        )
    }

    @Test
    fun `buildMouseJson omits zero wheel and sanitizes NaN`() {
        assertEquals(
            """{"type":"mouse","dx":3.5,"dy":-2.0,"action":"move"}""",
            ProtocolPayloads.buildMouseJson(3.5f, -2.0f, action = "move", wheel = 0f)
        )
        assertEquals(
            """{"type":"mouse","dx":0.0,"dy":0.0,"action":"scroll","wheel":-1.0}""",
            ProtocolPayloads.buildMouseJson(Float.NaN, Float.POSITIVE_INFINITY, action = "scroll", wheel = -1.0f)
        )
    }

    @Test
    fun `buildPasteJson escapes multiline CJK text and conditional enter`() {
        val noEnter = ProtocolPayloads.buildPasteJson("echo \"你好\"\nline2", enter = false, mode = "terminal")
        assertEquals(
            """{"type":"paste","content":"echo \"你好\"\nline2","mode":"terminal"}""",
            noEnter
        )
        assertFalse(noEnter.contains("\"enter\""))

        val withEnter = ProtocolPayloads.buildPasteJson("ls -la", enter = true, mode = "gui")
        assertEquals(
            """{"type":"paste","content":"ls -la","mode":"gui","enter":true}""",
            withEnter
        )
    }

    @Test
    fun `formatKeyWithModifiers orders modifiers consistently`() {
        assertEquals("left", ProtocolPayloads.formatKeyWithModifiers("left"))
        assertEquals(
            "ctrl+alt+shift+super+left",
            ProtocolPayloads.formatKeyWithModifiers("left", ctrl = true, alt = true, shift = true, superKey = true)
        )
    }
}
