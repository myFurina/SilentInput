package com.silentinput.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.ConnectionMode
import com.silentinput.app.model.StatusTone
import com.silentinput.app.network.NetworkClient
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
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
    onToggleBluetoothSystem: (Boolean) -> Unit,
    onOpenMoreSettings: () -> Unit = {},
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
    val favoriteBtAddresses by viewModel.favoriteBtAddresses.collectAsState()

    val isNetConnected by viewModel.isNetConnected.collectAsState()
    val isNetAuthenticated by viewModel.isNetAuthenticated.collectAsState()
    val isNetConnecting by viewModel.isNetConnecting.collectAsState()
    val isNetReconnecting by viewModel.isNetReconnecting.collectAsState()
    val isNetEncrypted by viewModel.isNetEncrypted.collectAsState()
    val connError by viewModel.netConnectionError.collectAsState()
    val lanInputError by viewModel.lanInputError.collectAsState()
    val latencyMs by viewModel.netLatencyMs.collectAsState()
    val certMismatchHost by viewModel.certMismatchHost.collectAsState()
    val savedCertFingerprint by viewModel.savedCertFingerprint.collectAsState()
    val pendingCertFingerprint by viewModel.pendingCertFingerprint.collectAsState()

    val isBtConnected by viewModel.isBtConnected.collectAsState()
    val isBtConnecting by viewModel.isBtConnecting.collectAsState()
    val isBtAdapterEnabled by viewModel.isBtAdapterEnabled.collectAsState()
    val btConnectedName by viewModel.btConnectedDeviceName.collectAsState()
    val btLatency by viewModel.btLatencyMs.collectAsState()
    val btStatusMsg by viewModel.btStatusMessage.collectAsState()

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val strings = LocalAppStrings.current
    val resolvedLanguage = LocalResolvedLanguage.current

    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            when (ConnectionMode.fromWire(currentMode)) {
                ConnectionMode.BLUETOOTH -> 2
                ConnectionMode.LAN -> 1
                ConnectionMode.USB -> 0
            }
        )
    }
    var ipInput by rememberSaveable { mutableStateOf(savedLanIp) }
    var tokenInput by rememberSaveable { mutableStateOf(savedToken) }
    var pinnedCertNonce by remember { mutableIntStateOf(0) }
    val dialogScrollState = rememberScrollState()

    val isUsbTarget = NetworkClient.parseServerEndpoint(activeServerIp)
        ?.let { NetworkClient.isLoopbackHost(it.host) } == true
    val isUsbConnected = isNetConnected && isUsbTarget
    val isLanConnected = isNetConnected && !isUsbTarget
    val isUsbConnecting = isNetConnecting && isUsbTarget
    val isLanConnecting = isNetConnecting && !isUsbTarget
    val isUsbReconnecting = isNetReconnecting && isUsbTarget
    val isLanReconnecting = isNetReconnecting && !isUsbTarget
    val usbConnError = if (isUsbTarget) connError else null
    val lanConnError = lanInputError ?: if (!isUsbTarget) connError else null
    val pinnedCertFingerprint = remember(ipInput, isLanConnected, pinnedCertNonce) {
        viewModel.getPinnedCertFingerprint(ipInput)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.settings, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(dialogScrollState)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(strings.settingsTabUsb) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(strings.settingsTabLan) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            onRequestBtPermission()
                            viewModel.refreshBondedDevices()
                        },
                        text = { Text(strings.settingsTabBluetooth) }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = tween(SilentMotion.DURATION_STATE_MS, easing = FastOutSlowInEasing))
                ) {
                    when (selectedTab) {
                        0 -> UsbConnectionTab(
                            isUsbConnected = isUsbConnected,
                            isUsbAuthenticated = isNetAuthenticated,
                            isUsbConnecting = isUsbConnecting,
                            isNetEncrypted = isNetEncrypted,
                            isNetReconnecting = isUsbReconnecting,
                            isTlsEnabled = isTlsEnabled,
                            connError = usbConnError,
                            latencyMs = latencyMs,
                            resolvedLanguage = resolvedLanguage,
                            onConnect = { viewModel.connectUsb() },
                            onDisconnect = { viewModel.disconnectNetwork() }
                        )
                        1 -> LanConnectionTab(
                            ip = ipInput,
                            onIpChange = {
                                ipInput = it
                                viewModel.clearLanInputError()
                            },
                            token = tokenInput,
                            onTokenChange = {
                                tokenInput = it
                                viewModel.clearLanInputError()
                            },
                            isTlsEnabled = isTlsEnabled,
                            onToggleTls = { viewModel.toggleTlsEncryption(it) },
                            isLanConnected = isLanConnected,
                            isLanAuthenticated = isNetAuthenticated,
                            isLanConnecting = isLanConnecting,
                            isNetEncrypted = isNetEncrypted,
                            isNetReconnecting = isLanReconnecting,
                            connError = lanConnError,
                            latencyMs = latencyMs,
                            resolvedLanguage = resolvedLanguage,
                            pinnedCertFingerprint = pinnedCertFingerprint,
                            onResetPinnedCert = {
                                if (viewModel.clearPinnedCertificate(ipInput)) {
                                    pinnedCertNonce++
                                }
                            },
                            certMismatchHost = certMismatchHost,
                            savedCertFingerprint = savedCertFingerprint,
                            pendingCertFingerprint = pendingCertFingerprint,
                            onTrustNewCert = {
                                viewModel.trustNewCertificateAndReconnect()
                                pinnedCertNonce++
                            },
                            onConnect = { viewModel.connectLan(ipInput, tokenInput, isTlsEnabled) },
                            onDisconnect = { viewModel.disconnectNetwork() }
                        )
                        else -> BluetoothConnectionTab(
                            hasBtPermission = hasBtPermission,
                            isBluetoothEnabled = isBtAdapterEnabled,
                            isBtConnected = isBtConnected,
                            isBtConnecting = isBtConnecting,
                            btConnectedName = btConnectedName,
                            btLatency = btLatency,
                            btStatusMsg = btStatusMsg,
                            bondedDevices = bondedDevices,
                            favoriteBtAddresses = favoriteBtAddresses,
                            lastBtAddress = lastBtAddress,
                            onRequestBtPermission = onRequestBtPermission,
                            onToggleBluetoothSystem = onToggleBluetoothSystem,
                            onRefreshBluetoothState = { viewModel.refreshBluetoothState() },
                            onToggleFavoriteDevice = { viewModel.toggleFavoriteBtDevice(it) },
                            onConnectDevice = { viewModel.connectBluetooth(it) },
                            onDisconnect = { viewModel.disconnectBluetooth() }
                        )
                    }
                }

                SectionDivider()

                val langShort = when (appLanguage) {
                    AppLanguage.SYSTEM -> strings.languageShortSystem
                    AppLanguage.ZH -> strings.languageShortZh
                    AppLanguage.EN -> strings.languageShortEn
                }
                MoreSettingsEntryRow(
                    title = strings.moreSettingsTitle,
                    summary = strings.moreSettingsSummary(!isHapticEnabled, isDarkMode, langShort, snippets.size),
                    onClick = onOpenMoreSettings
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.done)
            }
        }
    )
}

@Composable
internal fun MoreSettingsEntryRow(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    val strings = LocalAppStrings.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.98f)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = summary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = strings.expandDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    )
}
