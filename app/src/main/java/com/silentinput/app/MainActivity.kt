package com.silentinput.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.silentinput.app.model.AppLocaleResolver
import com.silentinput.app.model.AppStrings
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.StatusTone
import com.silentinput.app.ui.KeyboardScreen
import com.silentinput.app.ui.LocalAppStrings
import com.silentinput.app.ui.LocalResolvedLanguage
import com.silentinput.app.ui.TextSenderScreen
import com.silentinput.app.ui.TrackpadScreen
import com.silentinput.app.ui.UnifiedConnectionDialog
import com.silentinput.app.ui.theme.SilentInputTheme
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.viewmodel.MainViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshBluetoothState(hasBluetoothPermission())
    }

    private val btSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBluetoothState(hasBluetoothPermission())
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Globally disable all UI touch sounds for 100% silent operation
        window.decorView.isSoundEffectsEnabled = false

        // Do NOT request Bluetooth permissions at startup; only check current state.
        viewModel.updateBluetoothPermission(hasBluetoothPermission())

        setContent {
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            val appLanguage by viewModel.appLanguage.collectAsState()
            val configuration = LocalConfiguration.current
            val systemLocale = if (!configuration.locales.isEmpty) {
                configuration.locales[0]
            } else {
                Locale.getDefault()
            }
            val resolvedLanguage = AppLocaleResolver.resolve(appLanguage, systemLocale)
            val strings = remember(resolvedLanguage) { AppStrings.forResolved(resolvedLanguage) }

            CompositionLocalProvider(
                LocalResolvedLanguage provides resolvedLanguage,
                LocalAppStrings provides strings
            ) {
                SilentInputTheme(darkTheme = isDarkMode) {
                    MainAppScaffold(
                        viewModel = viewModel,
                        onRequestBtPermission = { requestBluetoothPermission() },
                        onToggleBluetoothSystem = { enable -> toggleBluetoothSystem(enable) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume(hasBluetoothPermission())
    }

    override fun onPause() {
        super.onPause()
        viewModel.setLeftMouseHold(false)
    }

    private fun requestBluetoothPermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun toggleBluetoothSystem(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            requestBluetoothPermission()
            return
        }
        if (viewModel.tryLegacySetBluetoothEnabled(enable)) {
            return
        }
        val intent = if (enable) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        } else {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        btSettingsLauncher.launch(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    viewModel: MainViewModel,
    onRequestBtPermission: () -> Unit,
    onToggleBluetoothSystem: (Boolean) -> Unit
) {
    val view = LocalView.current
    val strings = LocalAppStrings.current
    val resolvedLanguage = LocalResolvedLanguage.current
    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        onDispose {}
    }

    val currentMode by viewModel.connectionMode.collectAsState()
    val activeServerIp by viewModel.activeServerIp.collectAsState()
    val isCurrentConnected by viewModel.isCurrentModeConnected.collectAsState()
    val isAnyConnected by viewModel.isAnyConnected.collectAsState()

    val isBtConnected by viewModel.isBtConnected.collectAsState()
    val isBtConnecting by viewModel.isBtConnecting.collectAsState()
    val btDeviceName by viewModel.btConnectedDeviceName.collectAsState()
    val btLatency by viewModel.btLatencyMs.collectAsState()

    val isNetConnected by viewModel.isNetConnected.collectAsState()
    val isNetAuthenticated by viewModel.isNetAuthenticated.collectAsState()
    val isNetConnecting by viewModel.isNetConnecting.collectAsState()
    val isNetReconnecting by viewModel.isNetReconnecting.collectAsState()
    val isNetEncrypted by viewModel.isNetEncrypted.collectAsState()
    val connectionError by viewModel.netConnectionError.collectAsState()
    val latencyMs by viewModel.netLatencyMs.collectAsState()

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val trackpadSensitivity by viewModel.trackpadSensitivity.collectAsState()
    val isNaturalScrolling by viewModel.isNaturalScrolling.collectAsState()
    val isLeftMouseHeld by viewModel.isLeftMouseHeld.collectAsState()
    val showInlineTrackpadInText by viewModel.showInlineTrackpadInText.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val longTextInput by viewModel.longTextInput.collectAsState()
    val sendTargetMode by viewModel.sendTargetMode.collectAsState()
    val clearOnSend by viewModel.clearOnSend.collectAsState()
    val enterOnSend by viewModel.enterOnSend.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showConfigDialog by rememberSaveable { mutableStateOf(false) }

    val topBarStatus = ConnectionStatusFormatter.formatTopBarStatus(
        mode = currentMode,
        isBtConnected = isBtConnected,
        isBtConnecting = isBtConnecting,
        btDeviceName = btDeviceName,
        btLatencyMs = btLatency,
        isNetConnected = isNetConnected,
        isNetAuthenticated = isNetAuthenticated,
        isNetConnecting = isNetConnecting,
        isNetReconnecting = isNetReconnecting,
        isNetEncrypted = isNetEncrypted,
        netConnectionError = connectionError,
        netLatencyMs = latencyMs,
        activeServerIp = activeServerIp,
        language = resolvedLanguage
    )

    Scaffold(
        topBar = {
            val settingsIconRotation by animateFloatAsState(
                targetValue = if (showConfigDialog) 45f else 0f,
                animationSpec = tween(SilentMotion.DURATION_STATE_MS, easing = FastOutSlowInEasing),
                label = "settingsIconRotation"
            )
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .clickable { showConfigDialog = true }
                            .animateContentSize(animationSpec = tween(SilentMotion.DURATION_STATE_MS))
                    ) {
                        Text("SilentInput", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        val rawStatusColor = when (topBarStatus.tone) {
                            StatusTone.ERROR, StatusTone.HIGH_LATENCY -> MaterialTheme.colorScheme.error
                            StatusTone.PENDING -> MaterialTheme.colorScheme.primary
                            StatusTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val statusColor by animateColorAsState(
                            targetValue = rawStatusColor,
                            animationSpec = tween(SilentMotion.DURATION_STATE_MS),
                            label = "topBarStatusColor"
                        )
                        Text(
                            text = topBarStatus.text,
                            fontSize = 11.sp,
                            color = statusColor
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showConfigDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = strings.settings,
                            modifier = Modifier.graphicsLayer { rotationZ = settingsIconRotation }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                val navTabs = listOf(
                    Triple(0, Icons.Default.Keyboard, strings.tabKeyboard),
                    Triple(1, Icons.Default.Mouse, strings.tabTrackpad),
                    Triple(2, Icons.Default.Edit, strings.tabText)
                )
                navTabs.forEach { (tabIndex, iconVector, tabLabel) ->
                    val selected = selectedTab == tabIndex
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.08f else 1.0f,
                        animationSpec = tween(SilentMotion.DURATION_STATE_MS, easing = FastOutSlowInEasing),
                        label = "navIconScale"
                    )
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = tabIndex },
                        icon = {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = tabLabel,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                            )
                        },
                        label = { Text(tabLabel) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            AnimatedVisibility(
                visible = !isCurrentConnected,
                enter = SilentMotion.expandSectionTransition(),
                exit = SilentMotion.collapseSectionTransition()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showConfigDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AnimatedContent(
                            targetState = currentMode,
                            transitionSpec = {
                                fadeIn(tween(SilentMotion.DURATION_STATE_MS)) togetherWith
                                    fadeOut(tween(120))
                            },
                            modifier = Modifier.weight(1f),
                            label = "disconnectedBannerText"
                        ) { mode ->
                            Text(
                                text = when (mode) {
                                    "bluetooth" -> strings.bannerBtDisconnected
                                    "lan" -> strings.bannerLanDisconnected
                                    else -> strings.bannerUsbDisconnected
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.bannerSettingsAction,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        SilentMotion.directionalTabTransition(forward = targetState > initialState)
                    },
                    label = "mainTabTransition"
                ) { targetTab ->
                    when (targetTab) {
                        0 -> KeyboardScreen(
                            isHapticEnabled = isHapticEnabled,
                            onSendKey = { viewModel.sendKey(it) }
                        )
                        1 -> TrackpadScreen(
                            sensitivity = trackpadSensitivity,
                            onSensitivityChange = { viewModel.updateTrackpadSensitivity(it) },
                            naturalScrolling = isNaturalScrolling,
                            isHapticEnabled = isHapticEnabled,
                            isConnected = isAnyConnected,
                            isLeftHeld = isLeftMouseHeld,
                            onMouseMove = { dx, dy -> viewModel.sendMouseMove(dx, dy) },
                            onMouseClick = { viewModel.sendMouseClick(it) },
                            onMouseScroll = { viewModel.sendMouseScroll(it) },
                            onLeftMouseHoldChange = { viewModel.setLeftMouseHold(it) },
                            onSendKey = { viewModel.sendKey(it) }
                        )
                        2 -> TextSenderScreen(
                            isConnected = isAnyConnected,
                            isHapticEnabled = isHapticEnabled,
                            inputText = longTextInput,
                            onInputTextChange = { viewModel.updateLongTextInput(it) },
                            targetMode = sendTargetMode,
                            onTargetModeChange = { viewModel.setSendTargetMode(it) },
                            clearOnSend = clearOnSend,
                            onClearOnSendChange = { viewModel.setClearOnSend(it) },
                            enterOnSend = enterOnSend,
                            onEnterOnSendChange = { viewModel.setEnterOnSend(it) },
                            snippets = snippets,
                            showInlineTrackpad = showInlineTrackpadInText,
                            trackpadSensitivity = trackpadSensitivity,
                            naturalScrolling = isNaturalScrolling,
                            onSendText = { viewModel.sendTextPaste(it) },
                            onSendKey = { viewModel.sendKey(it) },
                            onMouseMove = { dx, dy -> viewModel.sendMouseMove(dx, dy) },
                            onMouseClick = { viewModel.sendMouseClick(it) },
                            onMouseScroll = { viewModel.sendMouseScroll(it) },
                            onOpenSettings = { showConfigDialog = true }
                        )
                    }
                }
            }
        }

        if (showConfigDialog) {
            UnifiedConnectionDialog(
                viewModel = viewModel,
                onRequestBtPermission = onRequestBtPermission,
                onToggleBluetoothSystem = onToggleBluetoothSystem,
                onDismiss = { showConfigDialog = false }
            )
        }
    }
}
