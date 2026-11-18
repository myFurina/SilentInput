package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InputTypesTest {

    @Test
    fun `ConnectionMode parses known values and defaults to USB`() {
        assertEquals(ConnectionMode.USB, ConnectionMode.fromWire("usb"))
        assertEquals(ConnectionMode.LAN, ConnectionMode.fromWire("lan"))
        assertEquals(ConnectionMode.BLUETOOTH, ConnectionMode.fromWire("bluetooth"))
        assertEquals(ConnectionMode.USB, ConnectionMode.fromWire(null))
        assertEquals(ConnectionMode.USB, ConnectionMode.fromWire("invalid"))
    }

    @Test
    fun `SendTargetMode parses known values and defaults to TERMINAL`() {
        assertEquals(SendTargetMode.TERMINAL, SendTargetMode.fromWire("terminal"))
        assertEquals(SendTargetMode.GUI, SendTargetMode.fromWire("gui"))
        assertEquals(SendTargetMode.KEYS, SendTargetMode.fromWire("keys"))
        assertEquals(SendTargetMode.TERMINAL, SendTargetMode.fromWire(null))
        assertEquals(SendTargetMode.TERMINAL, SendTargetMode.fromWire("other"))
    }

    @Test
    fun `MouseAction click maps MouseButton accurately`() {
        assertEquals(MouseAction.CLICK_LEFT, MouseAction.click(MouseButton.LEFT))
        assertEquals(MouseAction.CLICK_RIGHT, MouseAction.click(MouseButton.RIGHT))
    }
}
