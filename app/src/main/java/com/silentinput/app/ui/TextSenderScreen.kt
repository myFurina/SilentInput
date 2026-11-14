package com.silentinput.app.ui

import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale

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
    naturalScrolling: Boolean = true,
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
        val targetMaxHeight = if (showTrackpadArea) {
            (containerHeight - 160.dp).coerceAtLeast(180.dp)
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = animatedMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.txtTitle,
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
                    placeholder = { Text(strings.txtPlaceholder) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && isConnected) {
                            sendText(inputText)
                        }
                    }),
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
                            label = strings.txtTargetTerminal,
                            selected = targetMode == "terminal",
                            onClick = { onTargetModeChange("terminal") }
                        )
                        TargetModeChip(
                            label = strings.txtTargetApp,
                            selected = targetMode == "gui",
                            onClick = { onTargetModeChange("gui") }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onEnterOnSendChange(!enterOnSend) }
                                .padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = enterOnSend,
                                onCheckedChange = onEnterOnSendChange
                            )
                            Text(strings.txtOptionEnter, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onClearOnSendChange(!clearOnSend) }
                                .padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = clearOnSend,
                                onCheckedChange = onClearOnSendChange
                            )
                            Text(strings.txtOptionClear, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Primary Action: Prominent Dedicated Send Button & Undo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sendInteraction = remember { MutableInteractionSource() }
                    val sendEnabled = inputText.isNotBlank() && isConnected
                    Button(
                        onClick = { sendText(inputText) },
                        enabled = sendEnabled,
                        interactionSource = sendInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .pressScale(sendInteraction, pressedScale = 0.96f, enabled = sendEnabled),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.txtSendToPc, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    val undoInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            sendKey("ctrl+z")
                        },
                        interactionSource = undoInteraction,
                        modifier = Modifier
                            .height(48.dp)
                            .pressScale(undoInteraction, pressedScale = 0.95f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Text(strings.txtUndoBtn, fontSize = 12.sp)
                    }
                }

                // Quick Snippets (Horizontal scrolling row)
                AnimatedVisibility(
                    visible = snippets.isNotEmpty(),
                    enter = SilentMotion.expandSectionTransition(),
                    exit = SilentMotion.collapseSectionTransition()
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
                        item(key = "add_snippet_chip") {
                            AssistChip(
                                onClick = onOpenSettings,
                                label = { Text(strings.txtAddSnippetChip, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // Remote PC Cursor & Correction Toolbar
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = strings.txtCursorEditTitle,
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
                                label = strings.txtCursorBackspace,
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
                            val cursorEnterInteraction = remember { MutableInteractionSource() }
                            FilledTonalButton(
                                onClick = { sendKey("enter") },
                                interactionSource = cursorEnterInteraction,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(38.dp)
                                    .pressScale(cursorEnterInteraction, pressedScale = 0.94f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    strings.txtCursorEnter,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilterChip(
        selected = selected,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .pressScale(interactionSource, pressedScale = 0.96f)
            .animateContentSize(animationSpec = tween(160)),
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
