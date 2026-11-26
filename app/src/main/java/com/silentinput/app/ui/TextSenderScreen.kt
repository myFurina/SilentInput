package com.silentinput.app.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.silentinput.app.model.ImeSwitchShortcut
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextSenderScreen(
    isConnected: Boolean,
    isHapticEnabled: Boolean = false,
    supportsUnicodeDirectTyping: Boolean = true,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    targetMode: String,
    onTargetModeChange: (String) -> Unit,
    clearOnSend: Boolean,
    onClearOnSendChange: (Boolean) -> Unit,
    enterOnSend: Boolean,
    onEnterOnSendChange: (Boolean) -> Unit,
    snippets: List<String>,
    showSnippets: Boolean = true,
    showInlineTrackpad: Boolean = true,
    trackpadSensitivity: Float = 0.6f,
    naturalScrolling: Boolean = true,
    imeSwitchKey: String = ImeSwitchShortcut.DEFAULT,
    onImeSwitchKeyChange: (String) -> Unit = {},
    onSendText: (String) -> Unit,
    onSendKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val view = LocalView.current
    val strings = LocalAppStrings.current
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

    var lastSendTimestamp by remember { mutableLongStateOf(0L) }
    fun sendText(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendTimestamp < 200L) return
        lastSendTimestamp = now
        if (text.isBlank() || text.length > 300_000) return
        if (isHapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        onSendText(text)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeight = maxHeight
        val showTrackpadArea = showInlineTrackpad && !isImeVisible && containerHeight >= 520.dp
        val targetMaxHeight = if (showTrackpadArea) {
            (containerHeight - 150.dp).coerceAtLeast(180.dp)
        } else {
            containerHeight
        }
        val animatedMaxHeight by animateDpAsState(
            targetValue = targetMaxHeight,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "textEditorAreaMaxHeight"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = animatedMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isDirectMode = targetMode == "keys"
                val nonAsciiCount = remember(inputText, isDirectMode) {
                    if (!isDirectMode) {
                        0
                    } else {
                        inputText.count { ch ->
                            ch != '\n' && ch != '\r' && ch != '\t' && ch.code !in 0x20..0x7E
                        }
                    }
                }
                val hasDirectHeadlessError =
                    isDirectMode && !supportsUnicodeDirectTyping && nonAsciiCount > 0
                val hasDirectLargeUnicodeWarning =
                    isDirectMode && supportsUnicodeDirectTyping && nonAsciiCount > 60
                val isTextTooLong = inputText.length > 300_000
                val sendEnabled = inputText.isNotBlank() && isConnected && !hasDirectHeadlessError && !isTextTooLong

                // Card 1: Unified M3 Expressive Composer Surface (Input + 1-Tap Mode + Options + Primary CTA)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextField(
                            value = inputText,
                            onValueChange = onInputTextChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp, max = 148.dp),
                            placeholder = {
                                Text(
                                    text = strings.txtPlaceholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            },
                            isError = hasDirectHeadlessError || isTextTooLong,
                            supportingText = if (isTextTooLong || isDirectMode) {
                                {
                                    val msg = when {
                                        isTextTooLong -> strings.txtTextTooLongError(300_000)
                                        hasDirectHeadlessError -> strings.txtDirectHeadlessUnsupportedError
                                        !supportsUnicodeDirectTyping -> strings.txtDirectHeadlessHint
                                        hasDirectLargeUnicodeWarning -> strings.txtDirectLargeUnicodeWarning(nonAsciiCount)
                                        else -> strings.txtDirectDesktopHint
                                    }
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hasDirectHeadlessError || isTextTooLong) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (isDirectMode) KeyboardType.Ascii else KeyboardType.Text
                            ),
                            trailingIcon = {
                                AnimatedVisibility(
                                    visible = inputText.isNotEmpty(),
                                    enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.7f, animationSpec = tween(150)),
                                    exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.7f, animationSpec = tween(110))
                                ) {
                                    IconButton(onClick = { onInputTextChange("") }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = strings.txtClearInputDesc,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                errorIndicatorColor = Color.Transparent
                            )
                        )

                        // Inline Control Row: 1-Tap Connected Pill Group + Soft Tonal Toggle Pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                Row(
                                    modifier = Modifier.padding(3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ConnectedModeSegment(
                                        label = strings.txtTargetTerminal,
                                        selected = targetMode == "terminal",
                                        onClick = { onTargetModeChange("terminal") }
                                    )
                                    ConnectedModeSegment(
                                        label = strings.txtTargetApp,
                                        selected = targetMode == "gui",
                                        onClick = { onTargetModeChange("gui") }
                                    )
                                    ConnectedModeSegment(
                                        label = strings.txtTargetDirect,
                                        selected = targetMode == "keys",
                                        onClick = { onTargetModeChange("keys") }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TonalOptionTogglePill(
                                    label = strings.txtOptionEnter,
                                    checked = enterOnSend,
                                    onToggle = { onEnterOnSendChange(!enterOnSend) }
                                )
                                TonalOptionTogglePill(
                                    label = strings.txtOptionClear,
                                    checked = clearOnSend,
                                    onToggle = { onClearOnSendChange(!clearOnSend) }
                                )
                            }
                        }

                        // Hero Full-Width Primary Action
                        val sendInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { sendText(inputText) },
                            enabled = sendEnabled,
                            interactionSource = sendInteraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .pressScale(sendInteraction, pressedScale = 0.97f, enabled = sendEnabled),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.txtSendToPc, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Quick Snippets Strip (Borderless Soft Tonal Capsules, Toggleable in More Settings)
                AnimatedVisibility(
                    visible = showSnippets,
                    enter = fadeIn(tween(180)) + expandVertically(),
                    exit = fadeOut(tween(140)) + shrinkVertically()
                ) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(
                            items = snippets,
                            key = { index, snippet -> "${index}_$snippet" }
                        ) { _, snippet ->
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onInputTextChange(inputText + snippet) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                            ) {
                                Text(
                                    text = snippet,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                        item(key = "add_snippet_chip") {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onOpenSettings),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = strings.txtAddSnippetChip,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }

                // Card 2: Unified Edit & Cursor Strip (Includes Undo + Return Icon + IME Pill)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.txtCursorEditTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (imeSwitchKey.isNotBlank()) {
                                var showImeMenu by remember { mutableStateOf(false) }
                                Box {
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .combinedClickable(
                                                onClick = { sendKey(imeSwitchKey) },
                                                onLongClick = {
                                                    if (isHapticEnabled) {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    }
                                                    showImeMenu = true
                                                }
                                            ),
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = strings.imeSwitchQuickChip(ImeSwitchShortcut.formatLabel(imeSwitchKey)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showImeMenu,
                                        onDismissRequest = { showImeMenu = false }
                                    ) {
                                        ImeSwitchShortcut.PRESETS.forEach { preset ->
                                            val isSelected = ImeSwitchShortcut.normalize(imeSwitchKey) == preset
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = ImeSwitchShortcut.formatLabel(preset),
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    onImeSwitchKeyChange(preset)
                                                    showImeMenu = false
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(strings.imeSwitchPresetOff) },
                                            onClick = {
                                                onImeSwitchKeyChange("")
                                                showImeMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(strings.imeSwitchQuickMenuSettings) },
                                            onClick = {
                                                showImeMenu = false
                                                onOpenSettings()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RepeatableEditKey(
                                label = "←",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onRepeat = { sendKey("left") }
                            )
                            RepeatableEditKey(
                                label = "→",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onRepeat = { sendKey("right") }
                            )
                            RepeatableEditKey(
                                label = strings.txtCursorBackspace,
                                modifier = Modifier.weight(1.15f),
                                fontSize = 12.sp,
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                onRepeat = { sendKey("backspace") }
                            )
                            RepeatableEditKey(
                                label = "Del",
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onRepeat = { sendKey("delete") }
                            )
                            TonalActionEditKey(
                                label = strings.txtUndoBtn,
                                modifier = Modifier.weight(1.05f),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onClick = { sendKey("ctrl+z") }
                            )
                            TonalActionEditKey(
                                label = strings.txtCursorEnter,
                                modifier = Modifier.weight(1.05f),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = { sendKey("enter") }
                            )
                        }
                    }
                }
            }

            // Card 3: Dot-Matrix Minimal Inline Trackpad (No collapse button)
            AnimatedVisibility(
                visible = showTrackpadArea,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enter = fadeIn(tween(200, delayMillis = 30)) + expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = tween(220, easing = SilentMotion.EmphasizedDecelerateEasing)
                ),
                exit = fadeOut(tween(110)) + shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = tween(150, easing = SilentMotion.EmphasizedAccelerateEasing)
                )
            ) {
                TrackpadSurface(
                    sensitivity = trackpadSensitivity,
                    naturalScrolling = naturalScrolling,
                    isHapticEnabled = isHapticEnabled,
                    onMouseMove = onMouseMove,
                    onMouseClick = onMouseClick,
                    onMouseScroll = onMouseScroll,
                    modifier = Modifier.fillMaxSize(),
                    minimalStyle = true,
                    hintText = strings.txtInlineTrackpadHint
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
private fun ConnectedModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "modeSegBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "modeSegFg"
    )
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(11.dp),
        color = bg
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun TonalOptionTogglePill(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "optPillBg"
    )
    val fg by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "optPillFg"
    )
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .pressScale(interactionSource, pressedScale = 0.95f)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onToggle
            )
            .animateContentSize(animationSpec = tween(150)),
        shape = RoundedCornerShape(11.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                color = fg,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TonalActionEditKey(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .height(38.dp)
            .pressScale(interactionSource, pressedScale = 0.94f)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = shape,
        color = containerColor
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = contentColor, maxLines = 1)
        }
    }
}

@Composable
private fun RepeatableEditKey(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    containerColor: Color,
    contentColor: Color,
    onRepeat: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .height(38.dp)
            .pressScale(interactionSource, pressedScale = 0.94f)
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
