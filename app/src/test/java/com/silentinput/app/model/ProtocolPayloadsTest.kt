package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `buildGetStatusJson builds get_status request`() {
        assertEquals(
            """{"type":"get_status"}""",
            ProtocolPayloads.buildGetStatusJson()
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
        assertEquals(
            """{"type":"mouse","dx":0.0,"dy":0.0,"action":"scroll","wheel_hi":60,"hwheel_hi":-120}""",
            ProtocolPayloads.buildMouseJson(0f, 0f, action = "scroll", wheel = 60f, hiResWheel = true, hwheel = -120f)
        )
        assertEquals(
            """{"type":"mouse","dx":0.0,"dy":0.0,"action":"scroll","hwheel":2.0}""",
            ProtocolPayloads.buildMouseJson(0f, 0f, action = "scroll", wheel = 0f, hiResWheel = false, hwheel = 2f)
        )
        assertEquals(
            """{"type":"mouse","dx":0.0,"dy":0.0,"action":"click_left","udp_seq":42}""",
            ProtocolPayloads.buildMouseJson(0f, 0f, action = "click_left", udpSeq = 42L)
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

        val directKeys = ProtocolPayloads.buildPasteJson("pwd", enter = true, mode = "keys")
        assertEquals(
            """{"type":"paste","content":"pwd","mode":"keys","enter":true}""",
            directKeys
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

    @Test
    fun `parseServerMessage parses pong auth_result status and auth_required`() {
        assertEquals(
            ServerMessage.Pong(12345L),
            ProtocolPayloads.parseServerMessage("""{"type":"pong","t":12345}""")
        )
        assertEquals(
            ServerMessage.AuthResult("ok", "", false),
            ProtocolPayloads.parseServerMessage("""{"type":"auth_result","status":"ok","unicode_direct_typing":false}""")
        )
        assertEquals(
            ServerMessage.AuthResult(
                status = "ok",
                message = "Authenticated",
                unicodeDirectTyping = true,
                udpPort = 53318,
                udpSessionId = "0123456789abcdef",
                udpKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            ),
            ProtocolPayloads.parseServerMessage(
                """{"type":"auth_result","status":"ok","message":"Authenticated","unicode_direct_typing":true,"udp_port":53318,"udp_session_id":"0123456789abcdef","udp_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}"""
            )
        )
        assertEquals(
            ServerMessage.AuthResult("error", "Too many \"failed\" attempts", null),
            ProtocolPayloads.parseServerMessage("""{"type":"auth_result","status":"error","message":"Too many \"failed\" attempts"}""")
        )
        assertEquals(
            ServerMessage.Status(true),
            ProtocolPayloads.parseServerMessage("""{"type": "status", "unicode_direct_typing": true, "extra": [1, 2]}""")
        )
        assertEquals(
            ServerMessage.Status(true, "1.0.20", hiResWheel = true, nativeTouchpad = true, touchpadMax = 16000),
            ProtocolPayloads.parseServerMessage("""{"type":"status","unicode_direct_typing":true,"version":"1.0.20","hi_res_wheel":true,"native_touchpad":true,"touchpad_max":16000}""")
        )
        assertEquals(
            """{"type":"touchpad","c":[[0,4000,14100],[2,14500,4300]]}""",
            ProtocolPayloads.buildTouchpadJson(
                listOf(
                    TouchpadContact(0, 4000, 14100),
                    TouchpadContact(2, 14500, 4300)
                )
            )
        )
        assertEquals(
            """{"type":"touchpad","c":[]}""",
            ProtocolPayloads.buildTouchpadJson(emptyList())
        )
        assertEquals(
            ServerMessage.PasteResult("busy", "Input queue full, please slow down."),
            ProtocolPayloads.parseServerMessage("""{"type":"paste_result","status":"busy","message":"Input queue full, please slow down."}""")
        )
        assertEquals(
            ServerMessage.AuthRequired,
            ProtocolPayloads.parseServerMessage("""{"type":"auth_required"}""")
        )
        assertNull(ProtocolPayloads.parseServerMessage("not json"))
    }
}
