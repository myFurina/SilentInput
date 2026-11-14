package com.silentinput.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.ResolvedLanguage
import com.silentinput.app.model.StatusTone
import com.silentinput.app.network.NetworkClient
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import com.silentinput.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            when (currentMode) {
                "bluetooth" -> 2
                "lan" -> 1
                else -> 0
            }
        )
    }
    var ipInput by rememberSaveable { mutableStateOf(savedLanIp) }
    var tokenInput by rememberSaveable { mutableStateOf(savedToken) }
    val dialogScrollState = rememberScrollState()

    val isUsbTarget = activeServerIp == "127.0.0.1"
    val isUsbConnected = isNetConnected && isUsbTarget
    val isLanConnected = isNetConnected && !isUsbTarget
    val isUsbConnecting = isNetConnecting && isUsbTarget
    val isLanConnecting = isNetConnecting && !isUsbTarget
    val isUsbReconnecting = isNetReconnecting && isUsbTarget
    val isLanReconnecting = isNetReconnecting && !isUsbTarget
    val usbConnError = if (isUsbTarget) connError else null
    val lanConnError = if (!isUsbTarget) connError else null

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
                            onIpChange = { ipInput = it },
                            token = tokenInput,
                            onTokenChange = { tokenInput = it },
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
                            certMismatchHost = certMismatchHost,
                            savedCertFingerprint = savedCertFingerprint,
                            pendingCertFingerprint = pendingCertFingerprint,
                            onTrustNewCert = { viewModel.trustNewCertificateAndReconnect() },
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
private fun MoreSettingsEntryRow(
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
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = strings.expandDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    BackHandler(onBack = onBack)

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val showInlineTrackpadInText by viewModel.showInlineTrackpadInText.collectAsState()
    val isNaturalScrolling by viewModel.isNaturalScrolling.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val strings = LocalAppStrings.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.moreSettingsTitle,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = strings.collapseDesc
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDone) {
                        Text(strings.done, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LanguageSettingRow(
                        selectedLanguage = appLanguage,
                        onSelectLanguage = { viewModel.setAppLanguage(it) }
                    )

                    SectionDivider()

                    ToggleSettingRow(
                        title = strings.silentModeTitle,
                        subtitle = strings.silentModeSubtitle,
                        checked = !isHapticEnabled,
                        onCheckedChange = { viewModel.setHapticEnabled(!it) }
                    )

                    SectionDivider()

                    ToggleSettingRow(
                        title = strings.darkModeTitle,
                        subtitle = strings.darkModeSubtitle,
                        checked = isDarkMode,
                        onCheckedChange = { viewModel.toggleDarkMode() }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToggleSettingRow(
                        title = strings.inlineTrackpadTitle,
                        subtitle = strings.inlineTrackpadSubtitle,
                        checked = showInlineTrackpadInText,
                        onCheckedChange = { viewModel.setShowInlineTrackpadInText(it) }
                    )

                    SectionDivider()

                    ToggleSettingRow(
                        title = strings.naturalScrollingTitle,
                        subtitle = strings.naturalScrollingSubtitle,
                        checked = isNaturalScrolling,
                        onCheckedChange = { viewModel.setNaturalScrolling(it) }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    CustomSnippetsSection(
                        snippets = snippets,
                        onAddSnippet = {
                            viewModel.addSnippet(it)
                            coroutineScope.launch {
                                delay(80)
                                scrollState.animateScrollTo(
                                    value = scrollState.maxValue,
                                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                                )
                            }
                        },
                        onDeleteSnippet = { viewModel.deleteSnippet(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UsbConnectionTab(
    isUsbConnected: Boolean,
    isUsbAuthenticated: Boolean,
    isUsbConnecting: Boolean,
    isNetEncrypted: Boolean,
    isNetReconnecting: Boolean,
    isTlsEnabled: Boolean,
    connError: String?,
    latencyMs: Long?,
    resolvedLanguage: ResolvedLanguage,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalAppStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val statusDisplay = ConnectionStatusFormatter.formatUsbDialogStatus(
            isUsbConnected = isUsbConnected,
            isUsbAuthenticated = isUsbAuthenticated,
            isNetEncrypted = isNetEncrypted,
            isNetReconnecting = isNetReconnecting,
            connError = connError,
            latencyMs = latencyMs,
            isUsbConnecting = isUsbConnecting,
            language = resolvedLanguage
        )
        val statusColor by animateColorAsState(
            targetValue = statusToneColor(statusDisplay.tone),
            animationSpec = tween(SilentMotion.DURATION_STATE_MS),
            label = "usbStatusColor"
        )
        Text(
            text = statusDisplay.text,
            fontSize = 13.sp,
            color = statusColor
        )
        Text(
            text = if (isTlsEnabled) strings.usbProtocolTls else strings.usbProtocolPlaintext,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NetworkConnectButton(
            isConnected = isUsbConnected,
            isAuthenticated = isUsbAuthenticated,
            isConnecting = isUsbConnecting,
            isReconnecting = isNetReconnecting,
            onConnect = onConnect,
            onDisconnect = onDisconnect
        )
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
    isLanConnecting: Boolean,
    isNetEncrypted: Boolean,
    isNetReconnecting: Boolean,
    connError: String?,
    latencyMs: Long?,
    resolvedLanguage: ResolvedLanguage,
    certMismatchHost: String?,
    savedCertFingerprint: String?,
    pendingCertFingerprint: String?,
    onTrustNewCert: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalAppStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = ip,
            onValueChange = onIpChange,
            label = { Text(strings.lanIpLabel) },
            placeholder = { Text(strings.lanIpPlaceholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            label = { Text(strings.lanTokenLabel) },
            placeholder = { Text(strings.lanTokenPlaceholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.lanTlsTitle, fontSize = 14.sp)
                Text(
                    if (isTlsEnabled) strings.lanTlsSubtitleOn else strings.lanTlsSubtitleOff,
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
            latencyMs = latencyMs,
            isLanConnecting = isLanConnecting,
            language = resolvedLanguage
        )
        val lanStatusColor by animateColorAsState(
            targetValue = statusToneColor(statusDisplay.tone),
            animationSpec = tween(SilentMotion.DURATION_STATE_MS),
            label = "lanStatusColor"
        )
        Text(
            text = statusDisplay.text,
            fontSize = 13.sp,
            color = lanStatusColor
        )

        AnimatedVisibility(
            visible = certMismatchHost != null,
            enter = SilentMotion.expandSectionTransition(),
            exit = SilentMotion.collapseSectionTransition()
        ) {
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
                            text = strings.certMismatchTitle(certMismatchHost),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = strings.certMismatchDetails(
                                NetworkClient.formatShortFingerprint(savedCertFingerprint),
                                NetworkClient.formatShortFingerprint(pendingCertFingerprint)
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = onTrustNewCert,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.certTrustAndReconnect)
                        }
                    }
                }
            }
        }

        NetworkConnectButton(
            isConnected = isLanConnected,
            isAuthenticated = isLanAuthenticated,
            isConnecting = isLanConnecting,
            isReconnecting = isNetReconnecting,
            onConnect = onConnect,
            onDisconnect = onDisconnect
        )
    }
}

@Composable
private fun NetworkConnectButton(
    isConnected: Boolean,
    isAuthenticated: Boolean,
    isConnecting: Boolean,
    isReconnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalAppStrings.current
    val isAuthenticating = isConnected && !isAuthenticated
    val isBusyOrActive = isConnected || isConnecting || isReconnecting
    val interactionSource = remember { MutableInteractionSource() }
    val showSpinner = isConnecting || isAuthenticating
    Button(
        onClick = { if (isBusyOrActive) onDisconnect() else onConnect() },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.97f)
    ) {
        AnimatedVisibility(
            visible = showSpinner,
            enter = fadeIn(tween(150)) + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut(tween(110)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
        Text(
            when {
                isConnecting -> strings.btnConnecting
                isAuthenticating -> strings.btnVerifyingKey
                isConnected -> strings.btnDisconnect
                isReconnecting -> strings.btnStopReconnect
                else -> strings.btnConnect
            }
        )
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
    favoriteBtAddresses: Set<String>,
    lastBtAddress: String?,
    onRequestBtPermission: () -> Unit,
    onToggleBluetoothSystem: (Boolean) -> Unit,
    onRefreshBluetoothState: () -> Unit,
    onToggleFavoriteDevice: (String) -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalAppStrings.current
    val deviceListScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var refreshSpinCount by remember { mutableIntStateOf(0) }
    val refreshRotation by animateFloatAsState(
        targetValue = refreshSpinCount * 360f,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "btRefreshRotation"
    )

    LaunchedEffect(hasBtPermission) {
        onRefreshBluetoothState()
    }

    val sortedDevices = remember(bondedDevices, favoriteBtAddresses, lastBtAddress) {
        bondedDevices.sortedWith(
            compareByDescending<BluetoothDevice> { favoriteBtAddresses.contains(it.address) }
                .thenByDescending { it.address == lastBtAddress }
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // System Bluetooth toggle & refresh inside the Bluetooth tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(strings.btSystemTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = when {
                        !hasBtPermission -> strings.btSystemNoPermission
                        isBluetoothEnabled -> strings.btSystemOnCount(bondedDevices.size)
                        else -> strings.btSystemOff
                    },
                    fontSize = 11.sp,
                    color = if (hasBtPermission && !isBluetoothEnabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = {
                    refreshSpinCount++
                    onRefreshBluetoothState()
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = strings.btRefreshContentDesc,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = refreshRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = hasBtPermission && isBluetoothEnabled,
                onCheckedChange = { enable ->
                    if (!hasBtPermission) {
                        onRequestBtPermission()
                    } else {
                        onToggleBluetoothSystem(enable)
                    }
                }
            )
        }

        if (!hasBtPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.btPermissionCardBody, fontSize = 12.sp)
                    Button(
                        onClick = onRequestBtPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.btGrantPermissionBtn)
                    }
                }
            }
        } else if (!isBluetoothEnabled) {
            Text(
                text = strings.btDisabledHint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            if (isBtConnected) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val latStr = if (btLatency != null) " (${btLatency}ms)" else ""
                    Text(
                        text = strings.btConnectedToLabel(btConnectedName ?: strings.statusFallbackPcName, latStr),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strings.btDisconnectBtn)
                    }
                }
            } else if (isBtConnecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = btStatusMsg ?: strings.btConnectingFallback,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.btPairedDevicesTitle(bondedDevices.size),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    if (bondedDevices.isNotEmpty()) {
                        Text(
                            text = strings.btFavoriteTip,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    if (bondedDevices.isEmpty()) {
                        Text(
                            text = strings.btPairedEmptyBody,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 184.dp)
                                .verticalScroll(deviceListScrollState)
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sortedDevices.forEach { dev ->
                                key(dev.address) {
                                    val isFavorite = favoriteBtAddresses.contains(dev.address)
                                    BluetoothDeviceRow(
                                        device = dev,
                                        isFavorite = isFavorite,
                                        isLastUsed = dev.address == lastBtAddress,
                                        isConnecting = isBtConnecting,
                                        onToggleFavorite = {
                                            val willPin = !isFavorite
                                            onToggleFavoriteDevice(dev.address)
                                            if (willPin) {
                                                coroutineScope.launch {
                                                    deviceListScrollState.animateScrollTo(
                                                        value = 0,
                                                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                                                    )
                                                }
                                            }
                                        },
                                        onConnect = { onConnectDevice(dev) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothDeviceRow(
    device: BluetoothDevice,
    isFavorite: Boolean,
    isLastUsed: Boolean,
    isConnecting: Boolean,
    onToggleFavorite: () -> Unit,
    onConnect: () -> Unit
) {
    val strings = LocalAppStrings.current
    val devName = device.name ?: device.address
    val isComputer = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
    val starColor by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "btStarColor"
    )
    val starScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "btStarScale"
    )
    val connectInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isFavorite) strings.btStarRemoveDesc else strings.btStarAddDesc,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = starScale
                        scaleY = starScale
                    },
                tint = starColor
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = devName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isLastUsed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.btLastUsedBadge,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = if (isComputer) strings.btComputerDevice(device.address) else device.address,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onConnect,
            enabled = !isConnecting,
            interactionSource = connectInteraction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier
                .height(32.dp)
                .pressScale(connectInteraction, pressedScale = 0.95f, enabled = !isConnecting)
        ) {
            Text(if (isConnecting) strings.btRowConnecting else strings.btRowConnect, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LanguageSettingRow(
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
) {
    val strings = LocalAppStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.languageSettingTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LanguageOptionChip(
                label = strings.languageOptionSystem,
                selected = selectedLanguage == AppLanguage.SYSTEM,
                modifier = Modifier.weight(1.45f),
                onClick = { onSelectLanguage(AppLanguage.SYSTEM) }
            )
            LanguageOptionChip(
                label = strings.languageOptionZh,
                selected = selectedLanguage == AppLanguage.ZH,
                modifier = Modifier.weight(0.95f),
                onClick = { onSelectLanguage(AppLanguage.ZH) }
            )
            LanguageOptionChip(
                label = strings.languageOptionEn,
                selected = selectedLanguage == AppLanguage.EN,
                modifier = Modifier.weight(0.85f),
                onClick = { onSelectLanguage(AppLanguage.EN) }
            )
        }
    }
}

@Composable
private fun LanguageOptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "langChipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "langChipFg"
    )
    Surface(
        modifier = modifier
            .height(34.dp)
            .pressScale(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
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
    val strings = LocalAppStrings.current
    var newSnippetText by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.snippetsTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newSnippetText,
                onValueChange = { newSnippetText = it },
                placeholder = { Text(strings.snippetsPlaceholder) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            val addInteraction = remember { MutableInteractionSource() }
            val addEnabled = newSnippetText.isNotBlank()
            Button(
                onClick = {
                    if (newSnippetText.isNotBlank()) {
                        onAddSnippet(newSnippetText.trim())
                        newSnippetText = ""
                    }
                },
                enabled = addEnabled,
                interactionSource = addInteraction,
                modifier = Modifier.pressScale(addInteraction, pressedScale = 0.95f, enabled = addEnabled)
            ) {
                Text(strings.snippetsAddBtn)
            }
        }

        Column(
            modifier = Modifier.animateContentSize(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
                            contentDescription = strings.snippetsDeleteDesc,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
