package com.silentinput.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.ConnectionStatusFormatter
import com.silentinput.app.model.ResolvedLanguage
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
