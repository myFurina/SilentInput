package com.silentinput.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.StatusTone
import com.silentinput.app.network.NetworkClient
import com.silentinput.app.viewmodel.MainViewModel

@Composable
internal fun statusToneColor(tone: StatusTone) = when (tone) {
    StatusTone.ERROR, StatusTone.HIGH_LATENCY -> MaterialTheme.colorScheme.error
    StatusTone.PENDING -> MaterialTheme.colorScheme.primary
    StatusTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun UnifiedConnectionDialog(
    viewModel: MainViewModel,
    onRequestBtPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentMode by viewModel.connectionMode.collectAsState()
    val activeServerIp by viewModel.activeServerIp.collectAsState()
    val savedLanIp by viewModel.lanIp.collectAsState()
    val savedToken by viewModel.savedAuthToken.collectAsState()
    val isTlsEnabled by viewModel.isTlsEnabled.collectAsState()
    val hasBtPermission by viewModel.hasBtPermission.collectAsState()
    val bondedDevices by viewModel.bondedBtDevices.collectAsState()
    val lastBtAddress by viewModel.lastBtDeviceAddress.collectAsState()

    val isNetConnected by viewModel.isNetConnected.collectAsState()
    val isNetAuthenticated by viewModel.isNetAuthenticated.collectAsState()
    val isNetReconnecting by viewModel.isNetReconnecting.collectAsState()
    val isNetEncrypted by viewModel.isNetEncrypted.collectAsState()
    val connError by viewModel.netConnectionError.collectAsState()
    val latencyMs by viewModel.netLatencyMs.collectAsState()
    val certMismatchHost by viewModel.certMismatchHost.collectAsState()
    val savedCertFingerprint by viewModel.savedCertFingerprint.collectAsState()
    val pendingCertFingerprint by viewModel.pendingCertFingerprint.collectAsState()

    val isBtConnected by viewModel.isBtConnected.collectAsState()
    val isBtConnecting by viewModel.isBtConnecting.collectAsState()
    val btConnectedName by viewModel.btConnectedDeviceName.collectAsState()
    val btLatency by viewModel.btLatencyMs.collectAsState()
    val btStatusMsg by viewModel.btStatusMessage.collectAsState()

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val showInlineTrackpadInText by viewModel.showInlineTrackpadInText.collectAsState()
    val snippets by viewModel.snippets.collectAsState()

    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            when (currentMode) {
                "bluetooth" -> 2
                "lan" -> 1
                else -> 0
            }
        )
    }
    var ipInput by rememberSaveable { mutableStateOf(savedLanIp) }
    var tokenInput by rememberSaveable { mutableStateOf(savedToken) }

    val isUsbTarget = activeServerIp == "127.0.0.1"
    val isUsbConnected = isNetConnected && isUsbTarget
    val isLanConnected = isNetConnected && !isUsbTarget
    val isUsbReconnecting = isNetReconnecting && isUsbTarget
    val isLanReconnecting = isNetReconnecting && !isUsbTarget
    val usbConnError = if (isUsbTarget) connError else null
    val lanConnError = if (!isUsbTarget) connError else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("USB") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("局域网") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            onRequestBtPermission()
                            viewModel.refreshBondedDevices()
                        },
                        text = { Text("蓝牙") }
                    )
                }

                when (selectedTab) {
                    0 -> UsbConnectionTab(
                        isUsbConnected = isUsbConnected,
                        isUsbAuthenticated = isNetAuthenticated,
                        isNetEncrypted = isNetEncrypted,
                        isNetReconnecting = isUsbReconnecting,
                        isTlsEnabled = isTlsEnabled,
                        connError = usbConnError,
                        latencyMs = latencyMs,
                        onConnect = { viewModel.connectUsb() },
                        onDisconnect = { viewModel.disconnectNetwork() }
                    )
                    1 -> LanConnectionTab(
                        ip = ipInput,
                        onIpChange = { ipInput = it },
                        token = tokenInput,
                        onTokenChange = { tokenInput = it },
                        isTlsEnabled = isTlsEnabled,
                        onToggleTls = { viewModel.toggleTlsEncryption(it) },
                        isLanConnected = isLanConnected,
                        isLanAuthenticated = isNetAuthenticated,
                        isNetEncrypted = isNetEncrypted,
                        isNetReconnecting = isLanReconnecting,
                        connError = lanConnError,
                        latencyMs = latencyMs,
                        certMismatchHost = certMismatchHost,
                        savedCertFingerprint = savedCertFingerprint,
                        pendingCertFingerprint = pendingCertFingerprint,
                        onTrustNewCert = { viewModel.trustNewCertificateAndReconnect() },
                        onConnect = { viewModel.connectLan(ipInput, tokenInput, isTlsEnabled) },
                        onDisconnect = { viewModel.disconnectNetwork() }
                    )
                    2 -> BluetoothConnectionTab(
                        hasBtPermission = hasBtPermission,
                        isBluetoothEnabled = viewModel.isBluetoothEnabled(),
                        isBtConnected = isBtConnected,
                        isBtConnecting = isBtConnecting,
                        btConnectedName = btConnectedName,
                        btLatency = btLatency,
                        btStatusMsg = btStatusMsg,
                        bondedDevices = bondedDevices,
                        lastBtAddress = lastBtAddress,
                        onRequestBtPermission = onRequestBtPermission,
                        onRefreshDevices = { viewModel.refreshBondedDevices() },
                        onConnectDevice = { viewModel.connectBluetooth(it) },
                        onDisconnect = { viewModel.disconnectBluetooth() }
                    )
                }

                SectionDivider()

                ToggleSettingRow(
                    title = "静音模式",
                    subtitle = "关闭按键触感振动",
                    checked = !isHapticEnabled,
                    onCheckedChange = { viewModel.setHapticEnabled(!it) }
                )

                ToggleSettingRow(
                    title = "深色模式",
                    subtitle = "黑色与白色主题切换",
                    checked = isDarkMode,
                    onCheckedChange = { viewModel.toggleDarkMode() }
                )

                ToggleSettingRow(
                    title = "文本页内嵌触控板",
                    subtitle = "文本输入页下方显示触控板（输入法弹出时自动折叠）",
                    checked = showInlineTrackpadInText,
                    onCheckedChange = { viewModel.setShowInlineTrackpadInText(it) }
                )

                SectionDivider()

                CustomSnippetsSection(
                    snippets = snippets,
                    onAddSnippet = { viewModel.addSnippet(it) },
                    onDeleteSnippet = { viewModel.deleteSnippet(it) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

@Composable
private fun UsbConnectionTab(
    isUsbConnected: Boolean,
    isUsbAuthenticated: Boolean,
    isNetEncrypted: Boolean,
    isNetReconnecting: Boolean,
    isTlsEnabled: Boolean,
    connError: String?,
    latencyMs: Long?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val statusDisplay = ConnectionStatusFormatter.formatUsbDialogStatus(
            isUsbConnected = isUsbConnected,
            isUsbAuthenticated = isUsbAuthenticated,
            isNetEncrypted = isNetEncrypted,
            isNetReconnecting = isNetReconnecting,
            connError = connError,
            latencyMs = latencyMs
        )
        Text(
            text = statusDisplay.text,
            fontSize = 13.sp,
            color = statusToneColor(statusDisplay.tone)
        )
        Text(
            text = if (isTlsEnabled) {
                "当前协议: TLS 加密 (WSS) · 需与电脑端一致（可在“局域网”页切换）"
            } else {
                "当前协议: 明文 (WS) · 需与电脑端一致（可在“局域网”页开启）"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val isActiveOrReconnecting = isUsbConnected || isNetReconnecting
        Button(
            onClick = { if (isActiveOrReconnecting) onDisconnect() else onConnect() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    isUsbConnected -> "断开"
                    isNetReconnecting -> "停止重连"
                    else -> "连接"
                }
            )
        }
    }
}

@Composable
private fun LanConnectionTab(
    ip: String,
    onIpChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    isTlsEnabled: Boolean,
    onToggleTls: (Boolean) -> Unit,
    isLanConnected: Boolean,
    isLanAuthenticated: Boolean,
    isNetEncrypted: Boolean,
    isNetReconnecting: Boolean,
    connError: String?,
    latencyMs: Long?,
    certMismatchHost: String?,
    savedCertFingerprint: String?,
    pendingCertFingerprint: String?,
    onTrustNewCert: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = ip,
            onValueChange = onIpChange,
            label = { Text("电脑 IP") },
            placeholder = { Text("请输入电脑 IP 地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            label = { Text("配对密钥") },
            placeholder = { Text("请输入配对密钥") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TLS 传输加密 (WSS)", fontSize = 14.sp)
                Text(
                    if (isTlsEnabled) "严格端到端加密 (电脑端也需开启 TLS)" else "明文传输模式 (需电脑端同步关闭 TLS)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isTlsEnabled,
                onCheckedChange = onToggleTls
            )
        }

        val statusDisplay = ConnectionStatusFormatter.formatLanDialogStatus(
            isLanConnected = isLanConnected,
            isLanAuthenticated = isLanAuthenticated,
            isNetEncrypted = isNetEncrypted,
            isNetReconnecting = isNetReconnecting,
            connError = connError,
            latencyMs = latencyMs
        )
        Text(
            text = statusDisplay.text,
            fontSize = 13.sp,
            color = statusToneColor(statusDisplay.tone)
        )

        if (certMismatchHost != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "检测到 $certMismatchHost 证书指纹变更",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "已保存: ${NetworkClient.formatShortFingerprint(savedCertFingerprint)}\n新证书: ${NetworkClient.formatShortFingerprint(pendingCertFingerprint)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onTrustNewCert,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("信任新证书并重连")
                    }
                }
            }
        }

        val isActiveOrReconnecting = isLanConnected || isNetReconnecting
        Button(
            onClick = { if (isActiveOrReconnecting) onDisconnect() else onConnect() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    isLanConnected -> "断开"
                    isNetReconnecting -> "停止重连"
                    else -> "连接"
                }
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothConnectionTab(
    hasBtPermission: Boolean,
    isBluetoothEnabled: Boolean,
    isBtConnected: Boolean,
    isBtConnecting: Boolean,
    btConnectedName: String?,
    btLatency: Long?,
    btStatusMsg: String?,
    bondedDevices: List<BluetoothDevice>,
    lastBtAddress: String?,
    onRequestBtPermission: () -> Unit,
    onRefreshDevices: () -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    LaunchedEffect(hasBtPermission) {
        if (hasBtPermission) {
            onRefreshDevices()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hasBtPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("需要蓝牙连接权限以与电脑进行通信", fontSize = 12.sp)
                    Button(
                        onClick = onRequestBtPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开启蓝牙权限")
                    }
                }
            }
        } else if (!isBluetoothEnabled) {
            Text(
                text = "手机系统蓝牙已关闭，请先开启系统蓝牙",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        } else if (isBtConnected) {
            val latStr = if (btLatency != null) " (${btLatency}ms)" else ""
            Text(
                text = "状态: 已连接至 ${btConnectedName ?: "电脑"}$latStr",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("断开连接")
            }
        } else {
            if (isBtConnecting) {
                Text(
                    text = btStatusMsg ?: "正在连接...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "已配对设备",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (bondedDevices.isEmpty()) {
                Text(
                    text = "暂无已配对设备。\n请先在手机系统设置中与电脑完成蓝牙配对，然后在此点击即可一键直连！",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bondedDevices.forEach { dev ->
                        val devName = dev.name ?: dev.address
                        val isComputer = dev.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
                        val isLastUsed = dev.address == lastBtAddress
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = devName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (isLastUsed) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "(上次连接)",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = if (isComputer) "电脑设备 (${dev.address})" else dev.address,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onConnectDevice(dev) },
                                enabled = !isBtConnecting,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("连接", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    )
}

@Composable
private fun CustomSnippetsSection(
    snippets: List<String>,
    onAddSnippet: (String) -> Unit,
    onDeleteSnippet: (Int) -> Unit
) {
    var newSnippetText by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("常用短语", fontSize = 14.sp, fontWeight = FontWeight.Medium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newSnippetText,
                onValueChange = { newSnippetText = it },
                placeholder = { Text("输入新短语...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (newSnippetText.isNotBlank()) {
                        onAddSnippet(newSnippetText.trim())
                        newSnippetText = ""
                    }
                },
                enabled = newSnippetText.isNotBlank()
            ) {
                Text("添加")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            snippets.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { onDeleteSnippet(index) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
