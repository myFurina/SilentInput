package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.ProtocolPayloads

@Composable
fun KeyboardScreen(
    isHapticEnabled: Boolean = false,
    onSendKey: (String) -> Unit
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
            .verticalScroll(rememberScrollState())
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
            QuickActionButton(strings.kbImeSwitch, Modifier.weight(1f), isPrimaryHighlight = true) {
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
            if (activeModifiersPrefix.isNotEmpty()) {
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
        OutlinedButton(
            onClick = {
                triggerHaptic()
                showFullKeyboard = !showFullKeyboard
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
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
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (showFullKeyboard) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = showFullKeyboard) {
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

            if (selectedTab == 0) {
                val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                    .map { MiniKeySpec(it, it) }
                val qwertyTop = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
                    .map { MiniKeySpec(it, it.lowercase()) }
                val qwertyMid = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
                    .map { MiniKeySpec(it, it.lowercase()) }
                val qwertyBot = listOf("Z", "X", "C", "V", "B", "N", "M")
                    .map { MiniKeySpec(it, it.lowercase()) } + listOf(
                    MiniKeySpec("-", "minus"),
                    MiniKeySpec("=", "equal"),
                    MiniKeySpec("/", "slash")
                )

                MiniKeyRow(numberRow, onKeyPress)
                MiniKeyRow(qwertyTop, onKeyPress)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    qwertyMid.forEach { key ->
                        MiniKeyboardKey(
                            label = key.label,
                            modifier = Modifier.weight(key.weight),
                            onClick = { onKeyPress(key.code) }
                        )
                    }
                }
                MiniKeyRow(qwertyBot, onKeyPress)
            } else {
                val fRow1 = (1..6).map { MiniKeySpec("F$it", "f$it") }
                val fRow2 = (7..12).map { MiniKeySpec("F$it", "f$it") }
                val navRow = listOf(
                    MiniKeySpec("Home", "home"),
                    MiniKeySpec("End", "end"),
                    MiniKeySpec("PgUp", "pageup"),
                    MiniKeySpec("PgDn", "pagedown"),
                    MiniKeySpec("Ins", "insert"),
                    MiniKeySpec("\\", "backslash")
                )
                MiniKeyRow(fRow1, onKeyPress, spacing = 5.dp, fontSize = 12.sp)
                MiniKeyRow(fRow2, onKeyPress, spacing = 5.dp, fontSize = 12.sp)
                MiniKeyRow(navRow, onKeyPress, spacing = 5.dp, fontSize = 11.sp)
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
    Surface(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
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
    Surface(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    Modifier.clickable(onClick = onClick)
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
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}
