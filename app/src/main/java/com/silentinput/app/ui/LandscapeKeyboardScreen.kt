package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.ProtocolPayloads
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale

@Composable
fun LandscapeKeyboardScreen(
    isHapticEnabled: Boolean,
    onSendKey: (String) -> Unit,
    onExit: () -> Unit
) {
    val view = LocalView.current
    val strings = LocalAppStrings.current

    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        onDispose {}
    }

    BackHandler(onBack = onExit)

    var ctrlActive by rememberSaveable { mutableStateOf(false) }
    var altActive by rememberSaveable { mutableStateOf(false) }
    var shiftActive by rememberSaveable { mutableStateOf(false) }
    var superActive by rememberSaveable { mutableStateOf(false) }
    var showFnRow by rememberSaveable { mutableStateOf(false) }

    fun triggerHaptic() {
        if (isHapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun sendKeyWithModifiers(keyCode: String) {
        triggerHaptic()
        val combined = ProtocolPayloads.formatKeyWithModifiers(
            keyName = keyCode,
            ctrl = ctrlActive,
            alt = altActive,
            shift = shiftActive,
            superKey = superActive
        )
        onSendKey(combined)
        ctrlActive = false
        altActive = false
        shiftActive = false
        superActive = false
    }

    val hasAnyModifier = ctrlActive || altActive || shiftActive || superActive
    val modifierPrefix = buildString {
        if (ctrlActive) append("Ctrl+")
        if (altActive) append("Alt+")
        if (shiftActive) append("Shift+")
        if (superActive) append("Win+")
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Top Utility Bar: Fn/Nav switch + quick nav keys + pending modifier badge + Exit Landscape
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LandscapeFnToggleChip(
                    label = strings.kbLandscapeFnToggle,
                    active = showFnRow,
                    onClick = {
                        triggerHaptic()
                        showFnRow = !showFnRow
                    }
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LandscapeUtilityKey(label = "Ins", onClick = { sendKeyWithModifiers("insert") })
                    LandscapeUtilityKey(label = "Home", onClick = { sendKeyWithModifiers("home") })
                    LandscapeUtilityKey(label = "PgUp", onClick = { sendKeyWithModifiers("pageup") })
                    LandscapeUtilityKey(label = "PgDn", onClick = { sendKeyWithModifiers("pagedown") })
                    LandscapeUtilityKey(label = "End", onClick = { sendKeyWithModifiers("end") })
                    LandscapeUtilityKey(label = "PrtSc", onClick = { sendKeyWithModifiers("printscreen") })
                }

                if (hasAnyModifier) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = modifierPrefix.trimEnd('+'),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            maxLines = 1
                        )
                    }
                }

                val exitInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        triggerHaptic()
                        onExit()
                    },
                    interactionSource = exitInteraction,
                    modifier = Modifier
                        .height(32.dp)
                        .pressScale(exitInteraction, pressedScale = 0.95f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = strings.kbLandscapeExitBtn,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            // Row 1: Esc + (1-0, -, = OR F1-F12) + Backspace (repeatable) -> total weight 14.8f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LandscapeKey(
                    label = "Esc",
                    weight = 1f,
                    isFunction = true,
                    onClick = { sendKeyWithModifiers("esc") }
                )
                if (showFnRow) {
                    for (fn in 1..12) {
                        val fKey = "f$fn"
                        LandscapeKey(
                            label = "F$fn",
                            weight = 1f,
                            isFunction = true,
                            onClick = { sendKeyWithModifiers(fKey) }
                        )
                    }
                } else {
                    val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")
                    numberRow.forEach { keyName ->
                        LandscapeKey(
                            label = keyName,
                            weight = 1f,
                            onClick = { sendKeyWithModifiers(keyName) }
                        )
                    }
                }
                LandscapeRepeatableKey(
                    label = "⌫",
                    weight = 1.8f,
                    isFunction = true,
                    onRepeat = { sendKeyWithModifiers("backspace") }
                )
            }

            // Row 2: Tab + Q-P + [ + ] + \ -> total weight 14.5f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LandscapeKey(
                    label = "Tab",
                    weight = 1.5f,
                    isFunction = true,
                    onClick = { sendKeyWithModifiers("tab") }
                )
                val row2Keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\\")
                row2Keys.forEach { keyName ->
                    val display = if (keyName.length == 1 && keyName[0].isLetter()) {
                        if (shiftActive) keyName.uppercase() else keyName
                    } else {
                        keyName
                    }
                    LandscapeKey(
                        label = display,
                        weight = 1f,
                        onClick = { sendKeyWithModifiers(keyName) }
                    )
                }
            }

            // Row 3: ` + A-L + ; + ' + Enter -> total weight 14.5f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LandscapeKey(
                    label = "`",
                    weight = 1.4f,
                    isFunction = true,
                    onClick = { sendKeyWithModifiers("grave") }
                )
                val row3Keys = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'")
                row3Keys.forEach { keyName ->
                    val display = if (keyName.length == 1 && keyName[0].isLetter()) {
                        if (shiftActive) keyName.uppercase() else keyName
                    } else {
                        keyName
                    }
                    LandscapeKey(
                        label = display,
                        weight = 1f,
                        onClick = { sendKeyWithModifiers(keyName) }
                    )
                }
                LandscapeKey(
                    label = "Enter",
                    weight = 2.1f,
                    isPrimary = true,
                    onClick = { sendKeyWithModifiers("enter") }
                )
            }

            // Row 4: Shift + Z-M + , + . + / + Up (repeatable) + Del (repeatable) -> total weight 14.5f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LandscapeModifierKey(
                    label = "Shift",
                    active = shiftActive,
                    weight = 1.9f,
                    onClick = {
                        triggerHaptic()
                        shiftActive = !shiftActive
                    }
                )
                val row4Keys = listOf("z", "x", "c", "v", "b", "n", "m", ",", ".", "/")
                row4Keys.forEach { keyName ->
                    val display = if (keyName.length == 1 && keyName[0].isLetter()) {
                        if (shiftActive) keyName.uppercase() else keyName
                    } else {
                        keyName
                    }
                    LandscapeKey(
                        label = display,
                        weight = 1f,
                        onClick = { sendKeyWithModifiers(keyName) }
                    )
                }
                LandscapeRepeatableKey(
                    label = "↑",
                    weight = 1.3f,
                    isFunction = true,
                    onRepeat = { sendKeyWithModifiers("up") }
                )
                LandscapeRepeatableKey(
                    label = "Del",
                    weight = 1.3f,
                    isFunction = true,
                    onRepeat = { sendKeyWithModifiers("delete") }
                )
            }

            // Row 5: Ctrl + Win + Alt + Space + Left + Down + Right -> total weight 14.5f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LandscapeModifierKey(
                    label = "Ctrl",
                    active = ctrlActive,
                    weight = 1.3f,
                    onClick = {
                        triggerHaptic()
                        ctrlActive = !ctrlActive
                    }
                )
                LandscapeModifierKey(
                    label = "Win",
                    active = superActive,
                    weight = 1.1f,
                    onClick = {
                        triggerHaptic()
                        superActive = !superActive
                    }
                )
                LandscapeModifierKey(
                    label = "Alt",
                    active = altActive,
                    weight = 1.1f,
                    onClick = {
                        triggerHaptic()
                        altActive = !altActive
                    }
                )
                LandscapeKey(
                    label = "Space",
                    weight = 7.1f,
                    onClick = { sendKeyWithModifiers("space") }
                )
                LandscapeRepeatableKey(
                    label = "←",
                    weight = 1.3f,
                    isFunction = true,
                    onRepeat = { sendKeyWithModifiers("left") }
                )
                LandscapeRepeatableKey(
                    label = "↓",
                    weight = 1.3f,
                    isFunction = true,
                    onRepeat = { sendKeyWithModifiers("down") }
                )
                LandscapeRepeatableKey(
                    label = "→",
                    weight = 1.3f,
                    isFunction = true,
                    onRepeat = { sendKeyWithModifiers("right") }
                )
            }
        }
    }
}

@Composable
private fun RowScope.LandscapeKey(
    label: String,
    weight: Float,
    isFunction: Boolean = false,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor = when {
        isPrimary -> MaterialTheme.colorScheme.primary
        isFunction -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isPrimary -> MaterialTheme.colorScheme.onPrimary
        isFunction -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isPrimary) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    }

    Surface(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .pressScale(interactionSource, pressedScale = 0.93f),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        contentColor = contentColor,
        border = if (isPrimary) null else BorderStroke(1.dp, borderColor),
        shadowElevation = if (isPrimary) 0.dp else 1.dp,
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = label,
                fontSize = if (label.length > 2) 11.sp else 14.sp,
                fontWeight = if (isPrimary || isFunction) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun RowScope.LandscapeRepeatableKey(
    label: String,
    weight: Float,
    isFunction: Boolean = true,
    onRepeat: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor = if (isFunction) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isFunction) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(8.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .pressScale(interactionSource, pressedScale = 0.93f)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), shape)
            .indication(interactionSource, LocalIndication.current)
            .semantics { role = Role.Button }
            .repeatOnPress(
                interactionSource = interactionSource,
                cancelOnDrag = false,
                onRepeat = onRepeat
            )
            .padding(horizontal = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = if (label.length > 2) 11.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun RowScope.LandscapeModifierKey(
    label: String,
    active: Boolean,
    weight: Float,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "landscapeModBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "landscapeModContent"
    )

    Surface(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .pressScale(interactionSource, pressedScale = 0.93f),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        contentColor = contentColor,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun LandscapeFnToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "landscapeFnToggleBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "landscapeFnToggleContent"
    )

    Surface(
        modifier = Modifier
            .height(32.dp)
            .pressScale(interactionSource, pressedScale = 0.95f),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        contentColor = contentColor,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LandscapeUtilityKey(
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(7.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(30.dp)
            .pressScale(interactionSource, pressedScale = 0.94f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
