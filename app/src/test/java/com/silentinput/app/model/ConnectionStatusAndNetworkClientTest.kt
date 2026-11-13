package com.silentinput.app.model

import com.silentinput.app.network.NetworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusAndNetworkClientTest {

    @Test
    fun `formatShortFingerprint handles null blank and full hex`() {
        assertEquals("", NetworkClient.formatShortFingerprint(null))
        assertEquals("", NetworkClient.formatShortFingerprint("   "))
        assertEquals(
            "A1B2:C3D4:E5F6:0011",
            NetworkClient.formatShortFingerprint("a1b2c3d4e5f600112233445566778899")
        )
    }

    @Test
    fun `lan status requires both connected and authenticated flags`() {
        val verifying = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "lan",
            isBtConnected = false,
            isBtConnecting = false,
            btDeviceName = null,
            btLatencyMs = null,
            isNetConnected = true,
            isNetAuthenticated = false,
            isNetReconnecting = false,
            isNetEncrypted = true,
            netConnectionError = null,
            netLatencyMs = null,
            activeServerIp = "192.168.1.20"
        )
        assertEquals("局域网: 正在验证密钥...", verifying.text)
        assertEquals(StatusTone.PENDING, verifying.tone)

        val dialogVerifying = ConnectionStatusFormatter.formatLanDialogStatus(
            isLanConnected = true,
            isLanAuthenticated = false,
            isNetEncrypted = true,
            isNetReconnecting = false,
            connError = null,
            latencyMs = null
        )
        assertEquals("状态: 正在验证配对密钥...", dialogVerifying.text)
        assertEquals(StatusTone.PENDING, dialogVerifying.tone)

        val ready = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "lan",
            isBtConnected = false,
            isBtConnecting = false,
            btDeviceName = null,
            btLatencyMs = null,
            isNetConnected = true,
            isNetAuthenticated = true,
            isNetReconnecting = false,
            isNetEncrypted = true,
            netConnectionError = null,
            netLatencyMs = 15L,
            activeServerIp = "192.168.1.20"
        )
        assertEquals("局域网: 已连接 (15ms) 🔒", ready.text)
        assertEquals(StatusTone.NORMAL, ready.tone)

        val highLatency = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "lan",
            isBtConnected = false,
            isBtConnecting = false,
            btDeviceName = null,
            btLatencyMs = null,
            isNetConnected = true,
            isNetAuthenticated = true,
            isNetReconnecting = false,
            isNetEncrypted = false,
            netConnectionError = null,
            netLatencyMs = 1200L,
            activeServerIp = "192.168.1.20"
        )
        assertEquals("局域网: 已连接 (1200ms 卡顿/高延迟) (明文)", highLatency.text)
        assertEquals(StatusTone.HIGH_LATENCY, highLatency.tone)
    }

    @Test
    fun `usb and bluetooth topbar status reflect state accurately`() {
        val usbReady = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "usb",
            isBtConnected = false,
            isBtConnecting = false,
            btDeviceName = null,
            btLatencyMs = null,
            isNetConnected = true,
            isNetAuthenticated = true,
            isNetReconnecting = false,
            isNetEncrypted = true,
            netConnectionError = null,
            netLatencyMs = 1L,
            activeServerIp = "127.0.0.1"
        )
        assertEquals("USB: 已连接 (<1ms)", usbReady.text)
        assertEquals(StatusTone.NORMAL, usbReady.tone)

        val btConnected = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "bluetooth",
            isBtConnected = true,
            isBtConnecting = false,
            btDeviceName = "ThinkPad",
            btLatencyMs = 18L,
            isNetConnected = false,
            isNetAuthenticated = false,
            isNetReconnecting = false,
            isNetEncrypted = false,
            netConnectionError = null,
            netLatencyMs = null,
            activeServerIp = "127.0.0.1"
        )
        assertEquals("蓝牙: 已连接至 ThinkPad (18ms)", btConnected.text)
        assertEquals(StatusTone.NORMAL, btConnected.tone)
    }

    @Test
    fun `networkClient rejects blank lan token or blank ip without connecting`() {
        val client = NetworkClient(context = null)
        try {
            client.serverIp = "192.168.1.50"
            client.authToken = "   "
            client.connect()

            assertEquals("请输入配对密钥", client.connectionError.value)
            assertFalse(client.isConnected.value)
            assertFalse(client.isConnecting.value)
            assertFalse(client.isAuthenticated.value)
            assertFalse(client.isReconnecting.value)

            client.serverIp = ""
            client.authToken = "secret"
            client.connect()
            assertEquals("请输入电脑 IP 地址", client.connectionError.value)
            assertFalse(client.isConnecting.value)

            client.disconnect()
            assertNull(client.connectionError.value)
            assertFalse(client.isConnecting.value)
            assertFalse(client.isReconnecting.value)
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `reconnecting tone uses PENDING when no error and ERROR when error present`() {
        val reconnectingTop = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "usb",
            isBtConnected = false,
            isBtConnecting = false,
            btDeviceName = null,
            btLatencyMs = null,
            isNetConnected = false,
            isNetAuthenticated = false,
            isNetReconnecting = true,
            isNetEncrypted = true,
            netConnectionError = null,
            netLatencyMs = null,
            activeServerIp = "127.0.0.1"
        )
        assertEquals("USB: 正在重连...", reconnectingTop.text)
        assertEquals(StatusTone.PENDING, reconnectingTop.tone)

        val usbErrDialog = ConnectionStatusFormatter.formatUsbDialogStatus(
            isUsbConnected = false,
            isUsbAuthenticated = false,
            isNetEncrypted = true,
            isNetReconnecting = true,
            connError = "连接超时",
            latencyMs = null
        )
        assertEquals("状态: 连接超时", usbErrDialog.text)
        assertEquals(StatusTone.ERROR, usbErrDialog.tone)

        val lanReconnectingDialog = ConnectionStatusFormatter.formatLanDialogStatus(
            isLanConnected = false,
            isLanAuthenticated = false,
            isNetEncrypted = true,
            isNetReconnecting = true,
            connError = null,
            latencyMs = null
        )
        assertEquals("状态: 正在重连...", lanReconnectingDialog.text)
        assertEquals(StatusTone.PENDING, lanReconnectingDialog.tone)
    }

    @Test
    fun `connecting state displays 正在连接 immediately on top bar and dialogs`() {
        val lanTop = ConnectionStatusFormatter.formatTopBarStatus(
            mode = "lan",
            isBtConnected = false,
            isBtConnecting = false,
            btDeviceName = null,
            btLatencyMs = null,
            isNetConnected = false,
            isNetAuthenticated = false,
            isNetReconnecting = false,
            isNetEncrypted = true,
            netConnectionError = null,
            netLatencyMs = null,
            activeServerIp = "192.168.1.20",
            isNetConnecting = true
        )
        assertEquals("局域网: 正在连接...", lanTop.text)
        assertEquals(StatusTone.PENDING, lanTop.tone)

        val lanDialog = ConnectionStatusFormatter.formatLanDialogStatus(
            isLanConnected = false,
            isLanAuthenticated = false,
            isNetEncrypted = true,
            isNetReconnecting = false,
            connError = null,
            latencyMs = null,
            isLanConnecting = true
        )
        assertEquals("状态: 正在连接...", lanDialog.text)
        assertEquals(StatusTone.PENDING, lanDialog.tone)

        val usbDialog = ConnectionStatusFormatter.formatUsbDialogStatus(
            isUsbConnected = false,
            isUsbAuthenticated = false,
            isNetEncrypted = true,
            isNetReconnecting = false,
            connError = null,
            latencyMs = null,
            isUsbConnecting = true
        )
        assertEquals("状态: 正在连接...", usbDialog.text)
        assertEquals(StatusTone.PENDING, usbDialog.tone)
    }

    @Test
    fun `bluetooth auto reconnect respects manual disconnect flag`() {
        assertFalse(
            ConnectionStatusFormatter.shouldAutoReconnectBluetooth(
                mode = "bluetooth",
                isBtConnected = false,
                isBtConnecting = false,
                manualDisconnected = true,
                isBluetoothEnabled = true,
                lastBtDeviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertFalse(
            ConnectionStatusFormatter.shouldAutoReconnectBluetooth(
                mode = "lan",
                isBtConnected = false,
                isBtConnecting = false,
                manualDisconnected = false,
                isBluetoothEnabled = true,
                lastBtDeviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertFalse(
            ConnectionStatusFormatter.shouldAutoReconnectBluetooth(
                mode = "bluetooth",
                isBtConnected = false,
                isBtConnecting = false,
                manualDisconnected = false,
                isBluetoothEnabled = false,
                lastBtDeviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertTrue(
            ConnectionStatusFormatter.shouldAutoReconnectBluetooth(
                mode = "bluetooth",
                isBtConnected = false,
                isBtConnecting = false,
                manualDisconnected = false,
                isBluetoothEnabled = true,
                lastBtDeviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
    }

    @Test
    fun `network client connect with malformed host sets error and clears isConnecting`() {
        val client = NetworkClient()
        try {
            client.serverIp = "192.168.1. 10"
            client.authToken = "secret-token"
            client.isTlsEnabled = true
            client.connect()

            assertFalse(client.isConnected.value)
            assertFalse(client.isConnecting.value)
            assertFalse(client.isReconnecting.value)
            assertEquals("连接地址格式无效，请检查 IP 输入", client.connectionError.value)

            client.serverIp = "http://192.168.1.10"
            client.connect()

            assertFalse(client.isConnected.value)
            assertFalse(client.isConnecting.value)
            assertEquals("连接地址格式无效，请检查 IP 输入", client.connectionError.value)
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `network client disconnect clears isConnecting and connectionError`() {
        val client = NetworkClient()
        try {
            client.serverIp = ""
            client.connect()
            assertEquals("请输入电脑 IP 地址", client.connectionError.value)
            assertFalse(client.isConnecting.value)

            client.disconnect()
            assertFalse(client.isConnecting.value)
            assertFalse(client.isReconnecting.value)
            assertNull(client.connectionError.value)
        } finally {
            client.destroy()
        }
    }
}
