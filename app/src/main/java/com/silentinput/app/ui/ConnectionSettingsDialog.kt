package com.silentinput.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.silentinput.app.TopBarConnectionStatusSubtitle
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.ConnectionMode
import com.silentinput.app.model.PairingQrPayload
import com.silentinput.app.model.SavedLanHost
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    initialTab: Int? = null,
    initialExpandMore: Boolean = false,
    onRequestBtPermission: () -> Unit,
    onToggleBluetoothSystem: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val currentMode by viewModel.connectionMode.collectAsState()
    val activeServerIp by viewModel.activeServerIp.collectAsState()
    val savedLanIp by viewModel.lanIp.collectAsState()
    val savedToken by viewModel.savedAuthToken.collectAsState()
    val savedLanHosts by viewModel.savedLanHosts.collectAsState()
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
    val isUdpMouseActive by viewModel.isUdpMouseActive.collectAsState()
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
            initialTab ?: when (ConnectionMode.fromWire(currentMode)) {
                ConnectionMode.BLUETOOTH -> 2
                ConnectionMode.LAN -> 1
                ConnectionMode.USB -> 0
            }
        )
    }
    LaunchedEffect(initialTab) {
        if (initialTab != null) {
            selectedTab = initialTab
        }
    }
    val daemonVersion by viewModel.daemonVersion.collectAsState()
    var ipInput by rememberSaveable { mutableStateOf(savedLanIp) }
    var tokenInput by rememberSaveable { mutableStateOf(savedToken) }
    var pinnedCertNonce by remember { mutableIntStateOf(0) }
    var moreExpanded by rememberSaveable { mutableStateOf(initialExpandMore) }
    var showHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    var isProbingQrIps by remember { mutableStateOf(false) }
    var pendingQrPayload by remember { mutableStateOf<PairingQrPayload?>(null) }
    var pendingReachableIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var renamingHost by remember { mutableStateOf<SavedLanHost?>(null) }
    val pageScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(initialExpandMore) {
        if (initialExpandMore) {
            moreExpanded = true
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionDenied = false
            showQrScanner = true
        } else {
            cameraPermissionDenied = true
        }
    }

    val isUsbTarget = currentMode == ConnectionMode.USB.wireValue
    val isLanTarget = currentMode == ConnectionMode.LAN.wireValue
    val isUsbConnected = isNetConnected && isUsbTarget
    val isLanConnected = isNetConnected && isLanTarget
    val isUsbConnecting = isNetConnecting && isUsbTarget
    val isLanConnecting = isNetConnecting && isLanTarget
    val isUsbReconnecting = isNetReconnecting && isUsbTarget
    val isLanReconnecting = isNetReconnecting && isLanTarget
    val usbConnError = if (isUsbTarget) connError else null
    val lanConnError = lanInputError ?: if (isLanTarget) connError else null
    val pinnedCertFingerprint = remember(ipInput, isLanConnected, pinnedCertNonce) {
        viewModel.getPinnedCertFingerprint(ipInput)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = strings.settings,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        TopBarConnectionStatusSubtitle(viewModel = viewModel)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.collapseDesc
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDismiss) {
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
                .verticalScroll(pageScrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card 1: Connection Mode & Host Management
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            ) {
                Column {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
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
                            .padding(14.dp)
                            .animateContentSize(
                                animationSpec = tween(
                                    SilentMotion.DURATION_STATE_MS,
                                    easing = FastOutSlowInEasing
                                )
                            )
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
                                savedHosts = savedLanHosts,
                                activeServerIp = activeServerIp,
                                onSelectSavedHost = { host ->
                                    ipInput = host.endpoint
                                    tokenInput = host.token
                                    viewModel.clearLanInputError()
                                    if (host.token.isBlank()) {
                                        viewModel.toggleTlsEncryption(host.tlsEnabled, reconnectIfActive = false)
                                        viewModel.notifyUserToast { it.lanSavedHostNeedsKeyToast }
                                    } else {
                                        viewModel.connectLan(host.endpoint, host.token, host.tlsEnabled, host.name)
                                        pinnedCertNonce++
                                    }
                                },
                                onToggleSavedHostFavorite = { viewModel.toggleSavedHostFavorite(it) },
                                onRequestRenameSavedHost = { renamingHost = it },
                                onDeleteSavedHost = { viewModel.deleteSavedHost(it) },
                                onScanQrClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        cameraPermissionDenied = false
                                        showQrScanner = true
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                isProbingQrIps = isProbingQrIps,
                                cameraPermissionDenied = cameraPermissionDenied,
                                isTlsEnabled = isTlsEnabled,
                                onToggleTls = { viewModel.toggleTlsEncryption(it) },
                                isLanConnected = isLanConnected,
                                isLanAuthenticated = isNetAuthenticated,
                                isLanConnecting = isLanConnecting,
                                isNetEncrypted = isNetEncrypted,
                                isUdpMouseActive = isUdpMouseActive,
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
                }
            }

            // Card 2: Collapsible "More Settings" Card inside the unified Settings page
            val langShort = when (appLanguage) {
                AppLanguage.SYSTEM -> strings.languageShortSystem
                AppLanguage.ZH -> strings.languageShortZh
                AppLanguage.EN -> strings.languageShortEn
            }
            val arrowRotation by animateFloatAsState(
                targetValue = if (moreExpanded) 180f else 0f,
                animationSpec = tween(SilentMotion.DURATION_STATE_MS, easing = FastOutSlowInEasing),
                label = "moreSettingsArrowRotation"
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { moreExpanded = !moreExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = strings.moreSettingsTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = strings.moreSettingsSummary(!isHapticEnabled, isDarkMode, langShort, snippets.size),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (moreExpanded) strings.collapseDesc else strings.expandDesc,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { rotationZ = arrowRotation }
                        )
                    }

                    AnimatedVisibility(
                        visible = moreExpanded,
                        enter = SilentMotion.expandSectionTransition(),
                        exit = SilentMotion.collapseSectionTransition()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SectionDivider()
                            MoreSettingsSectionContent(
                                viewModel = viewModel,
                                onOpenHelp = { showHelpDialog = true },
                                onRequestScrollToBottom = {
                                    coroutineScope.launch {
                                        delay(80)
                                        pageScrollState.animateScrollTo(
                                            value = pageScrollState.maxValue,
                                            animationSpec = tween(240, easing = FastOutSlowInEasing)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHelpDialog) {
        HelpAndAboutDialog(
            daemonVersion = daemonVersion,
            onDismiss = { showHelpDialog = false }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onPayloadScanned = { payload ->
                showQrScanner = false
                when {
                    payload.ips.isEmpty() -> {
                        viewModel.notifyUserToast { it.lanQrNoIpErr }
                    }
                    payload.ips.size == 1 -> {
                        val endpoint = viewModel.applyScannedPairing(payload, payload.ips.first())
                        ipInput = endpoint
                        tokenInput = payload.token
                        pinnedCertNonce++
                    }
                    else -> {
                        isProbingQrIps = true
                        viewModel.probeReachableHostIps(payload.ips, payload.port) { reachable ->
                            isProbingQrIps = false
                            if (reachable.size == 1) {
                                val endpoint = viewModel.applyScannedPairing(payload, reachable.first())
                                ipInput = endpoint
                                tokenInput = payload.token
                                pinnedCertNonce++
                            } else {
                                pendingReachableIps = reachable
                                pendingQrPayload = payload
                            }
                        }
                    }
                }
            },
            onInvalidQr = {},
            onDismiss = { showQrScanner = false }
        )
    }

    pendingQrPayload?.let { payload ->
        QrIpPickerDialog(
            payload = payload,
            reachableIps = pendingReachableIps,
            onSelectIp = { chosenIp ->
                val endpoint = viewModel.applyScannedPairing(payload, chosenIp)
                ipInput = endpoint
                tokenInput = payload.token
                pinnedCertNonce++
                pendingQrPayload = null
                pendingReachableIps = emptyList()
            },
            onDismiss = {
                pendingQrPayload = null
                pendingReachableIps = emptyList()
            }
        )
    }

    renamingHost?.let { host ->
        RenameSavedHostDialog(
            initialName = host.name,
            onConfirm = { newName ->
                viewModel.renameSavedHost(host.id, newName)
                renamingHost = null
            },
            onDismiss = { renamingHost = null }
        )
    }
}

@Composable
private fun RenameSavedHostDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    var nameInput by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.lanSavedHostRenameTitle, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text(strings.lanSavedHostNameLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(nameInput) },
                enabled = nameInput.isNotBlank()
            ) {
                Text(strings.btnSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.btnCancel)
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
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
