package com.silentinput.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
internal fun BluetoothConnectionTab(
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
    val devName = try {
        device.name ?: device.address
    } catch (_: SecurityException) {
        device.address
    }
    val isComputer = try {
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
    } catch (_: SecurityException) {
        false
    }
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
