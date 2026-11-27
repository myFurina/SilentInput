package com.silentinput.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalContext
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
import com.silentinput.app.ui.LandscapeKeyboardScreen
import com.silentinput.app.ui.LocalAppStrings
import com.silentinput.app.ui.LocalResolvedLanguage
import com.silentinput.app.ui.SettingsScreen
import com.silentinput.app.ui.TextSenderScreen
import com.silentinput.app.ui.TrackpadScreen
import com.silentinput.app.ui.theme.SilentInputTheme
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.findActivity
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
        viewModel.releaseTouchpad()
        viewModel.setLeftMouseHold(false)
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    private fun requestBluetoothPermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
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
    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        onDispose {}
    }

    val currentMode by viewModel.connectionMode.collectAsState()
    val isCurrentConnected by viewModel.isCurrentModeConnected.collectAsState()
    val isAnyConnected by viewModel.isAnyConnected.collectAsState()
    val supportsUnicodeDirectTyping by viewModel.supportsUnicodeDirectTyping.collectAsState()
    val supportsHiResWheel by viewModel.supportsHiResWheel.collectAsState()
    val isNativeTouchpadActive by viewModel.isNativeTouchpadActive.collectAsState()

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val trackpadSensitivity by viewModel.trackpadSensitivity.collectAsState()
    val trackpadTuning by viewModel.trackpadTuning.collectAsState()
    val nativeTouchpadMaxCoord by viewModel.nativeTouchpadMaxCoord.collectAsState()
    val isNaturalScrolling by viewModel.isNaturalScrolling.collectAsState()
    val isLeftMouseHeld by viewModel.isLeftMouseHeld.collectAsState()
    val showInlineTrackpadInText by viewModel.showInlineTrackpadInText.collectAsState()
    val showSnippetsInText by viewModel.showSnippetsInText.collectAsState()
    val imeSwitchKey by viewModel.imeSwitchKey.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val quickEditKeys by viewModel.quickEditKeys.collectAsState()
    val longTextInput by viewModel.longTextInput.collectAsState()
    val sendTargetMode by viewModel.sendTargetMode.collectAsState()
    val clearOnSend by viewModel.clearOnSend.collectAsState()
    val enterOnSend by viewModel.enterOnSend.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showSettingsPage by rememberSaveable { mutableStateOf(false) }
    var settingsInitialTab by rememberSaveable { mutableStateOf<Int?>(null) }
    var settingsExpandMore by rememberSaveable { mutableStateOf(false) }
    var showLandscapeKeyboard by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val currentStrings by rememberUpdatedState(strings)
    LaunchedEffect(Unit) {
        viewModel.userToastEvent.collect { event ->
            Toast.makeText(context, event(currentStrings), Toast.LENGTH_SHORT).show()
        }
    }

    fun openSettings(preferredTab: Int? = null, expandMore: Boolean = false) {
        settingsInitialTab = preferredTab ?: when (currentMode) {
            "bluetooth" -> 2
            "lan" -> 1
            else -> 0
        }
        settingsExpandMore = expandMore
        showSettingsPage = true
    }

    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, showLandscapeKeyboard) {
        activity?.requestedOrientation = if (showLandscapeKeyboard) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    if (showSettingsPage) {
        SettingsScreen(
            viewModel = viewModel,
            initialTab = settingsInitialTab,
            initialExpandMore = settingsExpandMore,
            onRequestBtPermission = onRequestBtPermission,
            onToggleBluetoothSystem = onToggleBluetoothSystem,
            onDismiss = {
                showSettingsPage = false
                settingsInitialTab = null
                settingsExpandMore = false
            }
        )
        return
    }

    if (showLandscapeKeyboard) {
        LandscapeKeyboardScreen(
            isHapticEnabled = isHapticEnabled,
            isConnected = isAnyConnected,
            imeSwitchKey = imeSwitchKey,
            onSendKey = { viewModel.sendKey(it) },
            onOpenSettings = {
                showLandscapeKeyboard = false
                openSettings()
            },
            onExit = { showLandscapeKeyboard = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openSettings() }
                    ) {
                        Text("SilentInput", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        TopBarConnectionStatusSubtitle(viewModel = viewModel)
                    }
                },
                actions = {
                    IconButton(onClick = { openSettings() }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = strings.settings
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
                        .clickable { openSettings() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (currentMode) {
                                "bluetooth" -> strings.bannerBtDisconnected
                                "lan" -> strings.bannerLanDisconnected
                                else -> strings.bannerUsbDisconnected
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
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
                when (selectedTab) {
                    0 -> KeyboardScreen(
                        isHapticEnabled = isHapticEnabled,
                        imeSwitchKey = imeSwitchKey,
                        onSendKey = { viewModel.sendKey(it) },
                        onOpenLandscapeKeyboard = { showLandscapeKeyboard = true }
                    )
                    1 -> TrackpadScreen(
                        tuning = trackpadTuning,
                        onTuningChange = { viewModel.updateTrackpadTuning(it, persist = false) },
                        onTuningChangeFinished = { viewModel.commitTrackpadTuning() },
                        onResetTuning = { viewModel.resetTrackpadTuning() },
                        naturalScrolling = isNaturalScrolling,
                        hiResScroll = supportsHiResWheel,
                        isHapticEnabled = isHapticEnabled,
                        isConnected = isAnyConnected,
                        isLeftHeld = isLeftMouseHeld,
                        nativeTouchpadActive = isNativeTouchpadActive,
                        touchpadMaxCoord = nativeTouchpadMaxCoord,
                        onMouseMove = { dx, dy -> viewModel.sendMouseMove(dx, dy) },
                        onMouseClick = { viewModel.sendMouseClick(it) },
                        onMouseScroll = { w, hw -> viewModel.sendMouseScroll(w, hw) },
                        onTouchpadFrame = { contacts, isTransition ->
                            viewModel.sendTouchpadFrame(contacts, isTransition)
                        },
                        onLeftMouseHoldChange = { viewModel.setLeftMouseHold(it) },
                        onSendKey = { viewModel.sendKey(it) }
                    )
                    2 -> TextSenderScreen(
                        isConnected = isAnyConnected,
                        isHapticEnabled = isHapticEnabled,
                        supportsUnicodeDirectTyping = supportsUnicodeDirectTyping,
                        supportsHiResWheel = supportsHiResWheel,
                        nativeTouchpadActive = isNativeTouchpadActive,
                        inputText = longTextInput,
                        onInputTextChange = { viewModel.updateLongTextInput(it) },
                        targetMode = sendTargetMode,
                        onTargetModeChange = { viewModel.setSendTargetMode(it) },
                        clearOnSend = clearOnSend,
                        onClearOnSendChange = { viewModel.setClearOnSend(it) },
                        enterOnSend = enterOnSend,
                        onEnterOnSendChange = { viewModel.setEnterOnSend(it) },
                        snippets = snippets,
                        quickEditKeys = quickEditKeys,
                        showSnippets = showSnippetsInText,
                        showInlineTrackpad = showInlineTrackpadInText,
                        trackpadSensitivity = trackpadSensitivity,
                        trackpadTuning = trackpadTuning,
                        touchpadMaxCoord = nativeTouchpadMaxCoord,
                        naturalScrolling = isNaturalScrolling,
                        imeSwitchKey = imeSwitchKey,
                        onImeSwitchKeyChange = { viewModel.setImeSwitchKey(it) },
                        onSendText = { viewModel.sendTextPaste(it) },
                        onSendKey = { viewModel.sendKey(it) },
                        onMouseMove = { dx, dy -> viewModel.sendMouseMove(dx, dy) },
                        onMouseClick = { viewModel.sendMouseClick(it) },
                        onMouseScroll = { w, hw -> viewModel.sendMouseScroll(w, hw) },
                        onTouchpadFrame = { contacts, isTransition ->
                            viewModel.sendTouchpadFrame(contacts, isTransition)
                        },
                        onLeftMouseHoldChange = { viewModel.setLeftMouseHold(it) },
                        onOpenSettings = { openSettings(expandMore = true) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun TopBarConnectionStatusSubtitle(
    viewModel: MainViewModel
) {
    val resolvedLanguage = LocalResolvedLanguage.current
    val currentMode by viewModel.connectionMode.collectAsState()
    val activeServerIp by viewModel.activeServerIp.collectAsState()

    val isBtConnected by viewModel.isBtConnected.collectAsState()
    val isBtConnecting by viewModel.isBtConnecting.collectAsState()
    val btDeviceName by viewModel.btConnectedDeviceName.collectAsState()
    val btLatency by viewModel.btLatencyMs.collectAsState()

    val isNetConnected by viewModel.isNetConnected.collectAsState()
    val isNetAuthenticated by viewModel.isNetAuthenticated.collectAsState()
    val isNetConnecting by viewModel.isNetConnecting.collectAsState()
    val isNetReconnecting by viewModel.isNetReconnecting.collectAsState()
    val isNetEncrypted by viewModel.isNetEncrypted.collectAsState()
    val isUdpMouseActive by viewModel.isUdpMouseActive.collectAsState()
    val connectionError by viewModel.netConnectionError.collectAsState()
    val latencyMs by viewModel.netLatencyMs.collectAsState()

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
        isUdpMouseActive = isUdpMouseActive,
        language = resolvedLanguage
    )

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
        color = statusColor,
        maxLines = 1
    )
}
