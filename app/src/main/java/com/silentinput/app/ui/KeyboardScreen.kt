package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.ProtocolPayloads
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import kotlinx.coroutines.delay

@Composable
fun KeyboardScreen(
    isHapticEnabled: Boolean = false,
    onSendKey: (String) -> Unit,
    onOpenLandscapeKeyboard: () -> Unit = {}
) {
    val view = LocalView.current
    val strings = LocalAppStrings.current
    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        onDispose {}
    }

    var ctrlActive by rememberSaveable { mutableStateOf(false) }
    var altActive by rememberSaveable { mutableStateOf(false) }
    var shiftActive by rememberSaveable { mutableStateOf(false) }
    var superActive by rememberSaveable { mutableStateOf(false) }
    var showFullKeyboard by rememberSaveable { mutableStateOf(false) }
    var miniKeyboardTab by rememberSaveable { mutableIntStateOf(0) } // 0: A-Z/0-9, 1: F1-F12/Nav
    val scrollState = rememberScrollState()
    var prevShowFullKeyboard by remember { mutableStateOf(showFullKeyboard) }

    LaunchedEffect(showFullKeyboard) {
        if (showFullKeyboard && !prevShowFullKeyboard) {
            delay(90)
            val targetScroll = (scrollState.value + 220).coerceAtMost(scrollState.maxValue)
            if (targetScroll > scrollState.value) {
                scrollState.animateScrollTo(
                    value = targetScroll,
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                )
            }
        }
        prevShowFullKeyboard = showFullKeyboard
    }

    fun triggerHaptic() {
        if (isHapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun sendKey(keyName: String) {
        triggerHaptic()
        val fullKey = ProtocolPayloads.formatKeyWithModifiers(
            keyName = keyName,
            ctrl = ctrlActive,
            alt = altActive,
            shift = shiftActive,
            superKey = superActive
        )
        onSendKey(fullKey)
        // Auto release sticky modifiers after action
        ctrlActive = false
        altActive = false
        shiftActive = false
        superActive = false
    }

    fun sendDirectShortcut(name: String) {
        triggerHaptic()
        onSendKey(name)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Quick Actions Bar
        Text(
            text = strings.kbShortcutsTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Row 1: Core Typing Controls (IME Switch, Space, Enter, Backspace)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionButton(strings.kbImeSwitch, Modifier.weight(1f)) {
                sendDirectShortcut("shift")
            }
            QuickActionButton(strings.kbSpace, Modifier.weight(1f)) {
                sendKey("space")
            }
            QuickActionButton(strings.kbEnter, Modifier.weight(1f)) {
                sendKey("enter")
            }
            QuickActionButton(
                text = strings.kbBackspace,
                modifier = Modifier.weight(1f),
                isRepeatable = true,
                cancelOnDrag = showFullKeyboard
            ) {
                sendKey("backspace")
            }
        }

        // Row 2: Clipboard & Editing
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionButton(strings.kbCopy, Modifier.weight(1f)) {
                sendDirectShortcut("ctrl+c")
            }
            QuickActionButton(strings.kbPaste, Modifier.weight(1f)) {
                sendDirectShortcut("ctrl+v")
            }
            QuickActionButton(strings.kbUndo, Modifier.weight(1f)) {
                sendDirectShortcut("ctrl+z")
            }
            QuickActionButton(strings.kbSelectAll, Modifier.weight(1f)) {
                sendDirectShortcut("ctrl+a")
            }
        }

        // Row 3: Auxiliary Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionButton("Esc", Modifier.weight(1f)) {
                sendKey("esc")
            }
            QuickActionButton("Tab", Modifier.weight(1f)) {
                sendKey("tab")
            }
            QuickActionButton(
                text = "Delete",
                modifier = Modifier.weight(1f),
                isRepeatable = true,
                cancelOnDrag = showFullKeyboard
            ) {
                sendKey("delete")
            }
            QuickActionButton("Win", Modifier.weight(1f)) {
                sendKey("super")
            }
            QuickActionButton(strings.kbScreenshot, Modifier.weight(1f)) {
                sendKey("prtsc")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 2. Modifiers
        val activeModifiersPrefix = ProtocolPayloads.formatKeyWithModifiers(
            keyName = "",
            ctrl = ctrlActive,
            alt = altActive,
            shift = shiftActive,
            superKey = superActive
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (activeModifiersPrefix.isNotEmpty()) {
                    strings.kbModifiersPending(activeModifiersPrefix.uppercase())
                } else {
                    strings.kbModifiersTitle
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            AnimatedVisibility(
                visible = activeModifiersPrefix.isNotEmpty(),
                enter = fadeIn(tween(150)) + expandHorizontally(expandFrom = Alignment.End),
                exit = fadeOut(tween(110)) + shrinkHorizontally(shrinkTowards = Alignment.End)
            ) {
                Text(
                    text = strings.kbModifiersTapHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModifierToggleButton("Ctrl", ctrlActive, Modifier.weight(1f)) {
                triggerHaptic(); ctrlActive = !ctrlActive
            }
            ModifierToggleButton("Alt", altActive, Modifier.weight(1f)) {
                triggerHaptic(); altActive = !altActive
            }
            ModifierToggleButton("Shift", shiftActive, Modifier.weight(1f)) {
                triggerHaptic(); shiftActive = !shiftActive
            }
            ModifierToggleButton("Win", superActive, Modifier.weight(1f)) {
                triggerHaptic(); superActive = !superActive
            }
        }

        // Collapsible Mini Full Keyboard Toggle
        val toggleInteractionSource = remember { MutableInteractionSource() }
        val toggleContainerColor by animateColorAsState(
            targetValue = if (showFullKeyboard) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            animationSpec = tween(SilentMotion.DURATION_STATE_MS),
            label = "miniKbToggleBg"
        )
        val toggleBorderColor by animateColorAsState(
            targetValue = if (showFullKeyboard) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            },
            animationSpec = tween(SilentMotion.DURATION_STATE_MS),
            label = "miniKbToggleBorder"
        )
        val toggleArrowRotation by animateFloatAsState(
            targetValue = if (showFullKeyboard) 180f else 0f,
            animationSpec = tween(SilentMotion.DURATION_EXPAND_MS, easing = FastOutSlowInEasing),
            label = "miniKbToggleArrow"
        )
        val landscapeInteractionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    triggerHaptic()
                    showFullKeyboard = !showFullKeyboard
                },
                interactionSource = toggleInteractionSource,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .pressScale(toggleInteractionSource, pressedScale = 0.98f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = toggleContainerColor),
                border = BorderStroke(1.dp, toggleBorderColor),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showFullKeyboard) strings.kbCollapseMiniFull else strings.kbExpandMiniFull,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = toggleArrowRotation }
                )
            }

            OutlinedButton(
                onClick = {
                    triggerHaptic()
                    onOpenLandscapeKeyboard()
                },
                interactionSource = landscapeInteractionSource,
                modifier = Modifier
                    .height(38.dp)
                    .pressScale(landscapeInteractionSource, pressedScale = 0.96f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenRotation,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = strings.kbLandscapeBtn,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }

        AnimatedVisibility(
            visible = showFullKeyboard,
            enter = SilentMotion.expandSectionTransition(),
            exit = SilentMotion.collapseSectionTransition()
        ) {
            MiniFullKeyboardPanel(
                selectedTab = miniKeyboardTab,
                onSelectTab = { miniKeyboardTab = it },
                onKeyPress = { keyCode -> sendKey(keyCode) }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 3. Navigation
        Text(
            text = strings.kbNavigationTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RepeatableArrowButton("▲", cancelOnDrag = showFullKeyboard) { sendKey("up") }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatableArrowButton("◀", cancelOnDrag = showFullKeyboard) { sendKey("left") }
                RepeatableArrowButton("▼", cancelOnDrag = showFullKeyboard) { sendKey("down") }
                RepeatableArrowButton("▶", cancelOnDrag = showFullKeyboard) { sendKey("right") }
            }
        }
    }
}

private data class MiniKeySpec(val label: String, val code: String, val weight: Float = 1f)

private val MINI_NUMBER_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    .map { MiniKeySpec(it, it) }
private val MINI_QWERTY_TOP = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
    .map { MiniKeySpec(it, it.lowercase()) }
private val MINI_QWERTY_MID = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
    .map { MiniKeySpec(it, it.lowercase()) }
private val MINI_QWERTY_BOT = listOf("Z", "X", "C", "V", "B", "N", "M")
    .map { MiniKeySpec(it, it.lowercase()) } + listOf(
    MiniKeySpec("-", "minus"),
    MiniKeySpec("=", "equal"),
    MiniKeySpec("/", "slash")
)
private val MINI_F_ROW_1 = (1..6).map { MiniKeySpec("F$it", "f$it") }
private val MINI_F_ROW_2 = (7..12).map { MiniKeySpec("F$it", "f$it") }
private val MINI_NAV_ROW = listOf(
    MiniKeySpec("Home", "home"),
    MiniKeySpec("End", "end"),
    MiniKeySpec("PgUp", "pageup"),
    MiniKeySpec("PgDn", "pagedown"),
    MiniKeySpec("Ins", "insert"),
    MiniKeySpec("\\", "backslash")
)

@Composable
private fun MiniFullKeyboardPanel(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onKeyPress: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniTabChip(
                    label = strings.kbMiniTabAlphaNum,
                    selected = selectedTab == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTab(0) }
                )
                MiniTabChip(
                    label = strings.kbMiniTabFNav,
                    selected = selectedTab == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTab(1) }
                )
            }

            Column(
                modifier = Modifier.animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (selectedTab == 0) {
                    MiniKeyRow(MINI_NUMBER_ROW, onKeyPress)
                    MiniKeyRow(MINI_QWERTY_TOP, onKeyPress)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        MINI_QWERTY_MID.forEach { key ->
                            MiniKeyboardKey(
                                label = key.label,
                                modifier = Modifier.weight(key.weight),
                                onClick = { onKeyPress(key.code) }
                            )
                        }
                    }
                    MiniKeyRow(MINI_QWERTY_BOT, onKeyPress)
                } else {
                    MiniKeyRow(MINI_F_ROW_1, onKeyPress, spacing = 5.dp, fontSize = 12.sp)
                    MiniKeyRow(MINI_F_ROW_2, onKeyPress, spacing = 5.dp, fontSize = 12.sp)
                    MiniKeyRow(MINI_NAV_ROW, onKeyPress, spacing = 5.dp, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun MiniTabChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "miniTabChipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "miniTabChipFg"
    )
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .height(28.dp)
            .pressScale(interactionSource, pressedScale = 0.96f)
            .clip(shape)
            .background(containerColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun MiniKeyRow(
    keys: List<MiniKeySpec>,
    onKeyPress: (String) -> Unit,
    spacing: androidx.compose.ui.unit.Dp = 3.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        keys.forEach { key ->
            MiniKeyboardKey(
                label = key.label,
                fontSize = fontSize,
                modifier = Modifier.weight(key.weight),
                onClick = { onKeyPress(key.code) }
            )
        }
    }
}

@Composable
private fun MiniKeyboardKey(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(36.dp)
            .pressScale(interactionSource, pressedScale = 0.93f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun RepeatableArrowButton(
    label: String,
    cancelOnDrag: Boolean = false,
    onTrigger: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .width(90.dp)
            .height(46.dp)
            .pressScale(interactionSource, pressedScale = 0.94f)
            .clip(shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { onTrigger(); true }
            }
            .indication(interactionSource, LocalIndication.current)
            .repeatOnPress(interactionSource = interactionSource, cancelOnDrag = cancelOnDrag, onRepeat = onTrigger),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun QuickActionButton(
    text: String,
    modifier: Modifier = Modifier,
    isPrimaryHighlight: Boolean = false,
    isRepeatable: Boolean = false,
    cancelOnDrag: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .height(58.dp)
            .pressScale(interactionSource, pressedScale = 0.95f)
            .clip(shape)
            .then(
                if (isRepeatable) {
                    Modifier
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            onClick { onClick(); true }
                        }
                        .indication(interactionSource, LocalIndication.current)
                        .repeatOnPress(interactionSource = interactionSource, cancelOnDrag = cancelOnDrag, onRepeat = onClick)
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick
                    )
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimaryHighlight)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (isPrimaryHighlight)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ModifierToggleButton(text: String, isActive: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(160),
        label = "modifierBgColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(160),
        label = "modifierTextColor"
    )
    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(48.dp)
            .pressScale(interactionSource, pressedScale = 0.95f),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}
