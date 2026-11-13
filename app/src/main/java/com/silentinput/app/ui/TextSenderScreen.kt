package com.silentinput.app.ui

import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSenderScreen(
    isConnected: Boolean,
    isHapticEnabled: Boolean = false,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    targetMode: String,
    onTargetModeChange: (String) -> Unit,
    clearOnSend: Boolean,
    onClearOnSendChange: (Boolean) -> Unit,
    enterOnSend: Boolean,
    onEnterOnSendChange: (Boolean) -> Unit,
    snippets: List<String>,
    showInlineTrackpad: Boolean = true,
    trackpadSensitivity: Float = 0.6f,
    onSendText: (String) -> Unit,
    onSendKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val view = LocalView.current
    val isImeVisible = rememberIsImeVisible()

    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        onDispose {}
    }

    fun sendKey(keyName: String) {
        if (isHapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        onSendKey(keyName)
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        if (isHapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        onSendText(text)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeight = maxHeight
        val showTrackpadArea = showInlineTrackpad && !isImeVisible && containerHeight >= 540.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (showTrackpadArea) {
                            Modifier.heightIn(max = (containerHeight - 160.dp).coerceAtLeast(180.dp))
                        } else {
                            Modifier.weight(1f)
                        }
                    )
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
            text = "文本输入",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = onInputTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp, max = 150.dp),
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("在此输入文本...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (inputText.isNotBlank() && isConnected) {
                    sendText(inputText)
                }
            }),
            trailingIcon = {
                if (inputText.isNotEmpty()) {
                    IconButton(onClick = { onInputTextChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空输入",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            )
        )

        // Target Mode Selector & Options Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TargetModeChip(
                    label = "终端",
                    selected = targetMode == "terminal",
                    onClick = { onTargetModeChange("terminal") }
                )
                TargetModeChip(
                    label = "应用",
                    selected = targetMode == "gui",
                    onClick = { onTargetModeChange("gui") }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = enterOnSend,
                    onCheckedChange = onEnterOnSendChange
                )
                Text("回车", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(2.dp))
                Checkbox(
                    checked = clearOnSend,
                    onCheckedChange = onClearOnSendChange
                )
                Text("清空", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Primary Action: Prominent Dedicated Send Button & Undo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { sendText(inputText) },
                enabled = inputText.isNotBlank() && isConnected,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("发送到电脑", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    sendKey("ctrl+z")
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Text("撤销 (Ctrl+Z)", fontSize = 12.sp)
            }
        }

        // Quick Snippets (Horizontal scrolling row)
        if (snippets.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(snippets) { snippet ->
                    SuggestionChip(
                        onClick = { onInputTextChange(inputText + snippet) },
                        label = {
                            Text(
                                text = snippet,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = onOpenSettings,
                        label = { Text("+ 短语", fontSize = 11.sp) }
                    )
                }
            }
        }

        // Remote PC Cursor & Correction Toolbar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "光标与编辑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RepeatableEditKey(
                        label = "←",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onRepeat = { sendKey("left") }
                    )
                    RepeatableEditKey(
                        label = "→",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onRepeat = { sendKey("right") }
                    )
                    RepeatableEditKey(
                        label = "退格",
                        modifier = Modifier.weight(1.3f),
                        fontSize = 12.sp,
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onRepeat = { sendKey("backspace") }
                    )
                    RepeatableEditKey(
                        label = "Del",
                        modifier = Modifier.weight(1.1f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onRepeat = { sendKey("delete") }
                    )
                    FilledTonalButton(
                        onClick = { sendKey("enter") },
                        modifier = Modifier.weight(1.2f).height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("回车", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
            }

            if (showTrackpadArea) {
                TrackpadSurface(
                    sensitivity = trackpadSensitivity,
                    isHapticEnabled = isHapticEnabled,
                    onMouseMove = onMouseMove,
                    onMouseClick = onMouseClick,
                    onMouseScroll = onMouseScroll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    hintText = "触控区域（输入法弹出时自动折叠）\n单指移动 · 双指滚动 · 轻点左键"
                )
            }
        }
    }
}

@Composable
private fun rememberIsImeVisible(): Boolean {
    val view = LocalView.current
    var isImeVisible by remember { mutableStateOf(false) }

    DisposableEffect(view) {
        fun checkImeVisible(): Boolean {
            val insets = ViewCompat.getRootWindowInsets(view)
            val imeFromInsets = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val rootHeight = view.rootView.height
            val imeFromResize = rootHeight > 0 && (rootHeight - rect.bottom) > rootHeight * 0.18f
            return imeFromInsets || imeFromResize
        }

        isImeVisible = checkImeVisible()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = checkImeVisible()
            if (isImeVisible != visible) {
                isImeVisible = visible
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return isImeVisible
}

@Composable
private fun RepeatableEditKey(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    containerColor: Color,
    contentColor: Color,
    onRepeat: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { onRepeat(); true }
            }
            .indication(interactionSource, LocalIndication.current)
            .repeatOnPress(
                interactionSource = interactionSource,
                cancelOnDrag = true,
                onRepeat = onRepeat
            ),
        shape = shape,
        color = containerColor
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = fontSize, fontWeight = fontWeight, color = contentColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else null,
        label = {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
