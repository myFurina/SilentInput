package com.silentinput.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.ResolvedLanguage
import com.silentinput.app.model.SavedLanHost
import com.silentinput.app.network.NetworkClient
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale

@Composable
internal fun UsbConnectionTab(
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
internal fun LanConnectionTab(
    ip: String,
    onIpChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    savedHosts: List<SavedLanHost> = emptyList(),
    activeServerIp: String = "",
    onSelectSavedHost: (SavedLanHost) -> Unit = {},
    onToggleSavedHostFavorite: (String) -> Unit = {},
    onRequestRenameSavedHost: (SavedLanHost) -> Unit = {},
    onDeleteSavedHost: (String) -> Unit = {},
    onScanQrClick: () -> Unit = {},
    isProbingQrIps: Boolean = false,
    cameraPermissionDenied: Boolean = false,
    isTlsEnabled: Boolean,
    onToggleTls: (Boolean) -> Unit,
    isLanConnected: Boolean,
    isLanAuthenticated: Boolean,
    isLanConnecting: Boolean,
    isNetEncrypted: Boolean,
    isUdpMouseActive: Boolean = false,
    isNetReconnecting: Boolean,
    connError: String?,
    latencyMs: Long?,
    resolvedLanguage: ResolvedLanguage,
    pinnedCertFingerprint: String?,
    onResetPinnedCert: () -> Unit,
    certMismatchHost: String?,
    savedCertFingerprint: String?,
    pendingCertFingerprint: String?,
    onTrustNewCert: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val strings = LocalAppStrings.current
    var tokenVisible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onScanQrClick,
            enabled = !isProbingQrIps,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isProbingQrIps) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.lanQrProbingIp)
            } else {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = strings.lanQrScanBtn,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.lanQrScanBtn)
            }
        }

        if (cameraPermissionDenied) {
            Text(
                text = strings.lanQrCameraDenied,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        AnimatedVisibility(
            visible = savedHosts.isNotEmpty(),
            enter = SilentMotion.expandSectionTransition(),
            exit = SilentMotion.collapseSectionTransition()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = strings.lanSavedHostsTitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                savedHosts.forEach { host ->
                    val isActive = isLanConnected &&
                        activeServerIp.trim().equals(host.endpoint.trim(), ignoreCase = true)
                    SavedLanHostCard(
                        host = host,
                        isActive = isActive,
                        onSelect = { onSelectSavedHost(host) },
                        onToggleFavorite = { onToggleSavedHostFavorite(host.id) },
                        onRequestRename = { onRequestRenameSavedHost(host) },
                        onDelete = { onDeleteSavedHost(host.id) }
                    )
                }
            }
        }

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
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (tokenVisible) strings.lanTokenHideDesc else strings.lanTokenShowDesc
                    )
                }
            },
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
            isUdpMouseActive = isUdpMouseActive,
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

        if (isTlsEnabled && pinnedCertFingerprint != null && certMismatchHost == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.certPinnedFingerprintLabel(NetworkClient.formatShortFingerprint(pinnedCertFingerprint)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onResetPinnedCert,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(strings.certResetPinnedBtn, fontSize = 11.sp)
                }
            }
        }

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

@Composable
private fun SavedLanHostCard(
    host: SavedLanHost,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRequestRename: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    val shape = RoundedCornerShape(10.dp)
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onSelect),
        shape = shape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (host.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (host.isFavorite) strings.lanSavedHostStarRemoveDesc else strings.lanSavedHostStarAddDesc,
                    tint = if (host.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = host.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Text(
                            text = strings.lanSavedHostActiveTag,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (host.token.isBlank()) {
                        Text(
                            text = strings.lanSavedHostNeedsKeyTag,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    text = if (host.tlsEnabled) "${host.endpoint} · WSS" else "${host.endpoint} · WS",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRequestRename,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = strings.lanSavedHostRenameDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = strings.lanSavedHostDeleteDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
