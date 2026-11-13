package com.silentinput.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.StatusTone
import com.silentinput.app.ui.KeyboardScreen
import com.silentinput.app.ui.TextSenderScreen
import com.silentinput.app.ui.TrackpadScreen
import com.silentinput.app.ui.UnifiedConnectionDialog
import com.silentinput.app.ui.theme.SilentInputTheme
import com.silentinput.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.updateBluetoothPermission(hasBluetoothPermission())
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

            SilentInputTheme(darkTheme = isDarkMode) {
                MainAppScaffold(
                    viewModel = viewModel,
                    onRequestBtPermission = { requestBluetoothPermission() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume(hasBluetoothPermission())
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    viewModel: MainViewModel,
    onRequestBtPermission: () -> Unit
) {
    val view = LocalView.current
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
    val isNetReconnecting by viewModel.isNetReconnecting.collectAsState()
    val isNetEncrypted by viewModel.isNetEncrypted.collectAsState()
    val connectionError by viewModel.netConnectionError.collectAsState()
    val latencyMs by viewModel.netLatencyMs.collectAsState()

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val trackpadSensitivity by viewModel.trackpadSensitivity.collectAsState()
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
        isNetReconnecting = isNetReconnecting,
        isNetEncrypted = isNetEncrypted,
        netConnectionError = connectionError,
        netLatencyMs = latencyMs,
        activeServerIp = activeServerIp
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showConfigDialog = true }) {
                        Text("SilentInput", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        val statusColor = when (topBarStatus.tone) {
                            StatusTone.ERROR, StatusTone.HIGH_LATENCY -> MaterialTheme.colorScheme.error
                            StatusTone.PENDING -> MaterialTheme.colorScheme.primary
                            StatusTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = topBarStatus.text,
                            fontSize = 11.sp,
                            color = statusColor
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showConfigDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Keyboard, contentDescription = "键盘") },
                    label = { Text("键盘") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Mouse, contentDescription = "触控板") },
                    label = { Text("触控板") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "文本") },
                    label = { Text("文本") }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (!isCurrentConnected) {
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
                        Text(
                            text = when (currentMode) {
                                "bluetooth" -> "蓝牙未连接，点击进入设置连接"
                                "lan" -> "局域网未连接，点击进入设置连接"
                                else -> "USB未连接，点击进入设置连接"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "设置 >",
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
                        onSendKey = { viewModel.sendKey(it) }
                    )
                    1 -> TrackpadScreen(
                        sensitivity = trackpadSensitivity,
                        onSensitivityChange = { viewModel.updateTrackpadSensitivity(it) },
                        isHapticEnabled = isHapticEnabled,
                        onMouseMove = { dx, dy -> viewModel.sendMouseMove(dx, dy) },
                        onMouseClick = { viewModel.sendMouseClick(it) },
                        onMouseScroll = { viewModel.sendMouseScroll(it) }
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

        if (showConfigDialog) {
            UnifiedConnectionDialog(
                viewModel = viewModel,
                onRequestBtPermission = onRequestBtPermission,
                onDismiss = { showConfigDialog = false }
            )
        }
    }
}
