package com.silentinput.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.AppLanguage
import com.silentinput.app.model.ImeSwitchShortcut
import com.silentinput.app.model.QuickEditKeys
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import com.silentinput.app.viewmodel.MainViewModel

@Composable
internal fun MoreSettingsSectionContent(
    viewModel: MainViewModel,
    onOpenHelp: () -> Unit,
    onRequestScrollToBottom: () -> Unit = {}
) {
    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val showInlineTrackpadInText by viewModel.showInlineTrackpadInText.collectAsState()
    val showSnippetsInText by viewModel.showSnippetsInText.collectAsState()
    val useNativeTouchpad by viewModel.useNativeTouchpad.collectAsState()
    val isNaturalScrolling by viewModel.isNaturalScrolling.collectAsState()
    val imeSwitchKey by viewModel.imeSwitchKey.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val quickEditKeys by viewModel.quickEditKeys.collectAsState()
    val strings = LocalAppStrings.current

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LanguageSettingRow(
            selectedLanguage = appLanguage,
            onSelectLanguage = { viewModel.setAppLanguage(it) }
        )

        SectionDivider()

        ToggleSettingRow(
            title = strings.silentModeTitle,
            subtitle = strings.silentModeSubtitle,
            checked = !isHapticEnabled,
            onCheckedChange = { viewModel.setHapticEnabled(!it) }
        )

        SectionDivider()

        ToggleSettingRow(
            title = strings.darkModeTitle,
            subtitle = strings.darkModeSubtitle,
            checked = isDarkMode,
            onCheckedChange = { viewModel.toggleDarkMode() }
        )

        SectionDivider()

        ToggleSettingRow(
            title = strings.inlineTrackpadTitle,
            subtitle = strings.inlineTrackpadSubtitle,
            checked = showInlineTrackpadInText,
            onCheckedChange = { viewModel.setShowInlineTrackpadInText(it) }
        )

        SectionDivider()

        ToggleSettingRow(
            title = strings.nativeTouchpadTitle,
            subtitle = strings.nativeTouchpadSubtitle,
            checked = useNativeTouchpad,
            onCheckedChange = { viewModel.setUseNativeTouchpad(it) }
        )

        SectionDivider()

        ToggleSettingRow(
            title = strings.naturalScrollingTitle,
            subtitle = strings.naturalScrollingSubtitle,
            checked = isNaturalScrolling,
            onCheckedChange = { viewModel.setNaturalScrolling(it) }
        )

        SectionDivider()

        ImeSwitchSettingRow(
            selectedShortcut = imeSwitchKey,
            onSelectShortcut = { viewModel.setImeSwitchKey(it) }
        )

        SectionDivider()

        ToggleSettingRow(
            title = strings.showSnippetsTitle,
            subtitle = strings.showSnippetsSubtitle,
            checked = showSnippetsInText,
            onCheckedChange = { viewModel.setShowSnippetsInText(it) }
        )

        SectionDivider()

        CustomSnippetsSection(
            snippets = snippets,
            onAddSnippet = {
                viewModel.addSnippet(it)
                onRequestScrollToBottom()
            },
            onDeleteSnippet = { viewModel.deleteSnippet(it) }
        )

        SectionDivider()

        QuickEditKeysSection(
            selectedKeys = quickEditKeys,
            onToggleKey = { viewModel.toggleQuickEditKey(it) },
            onResetDefaults = { viewModel.resetQuickEditKeysToDefault() }
        )

        SectionDivider()

        MoreSettingsEntryRow(
            title = strings.helpEntryTitle,
            summary = strings.helpEntrySubtitle,
            onClick = onOpenHelp
        )
    }
}

@Composable
private fun LanguageSettingRow(
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
) {
    val strings = LocalAppStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.languageSettingTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LanguageOptionChip(
                label = strings.languageOptionSystem,
                selected = selectedLanguage == AppLanguage.SYSTEM,
                modifier = Modifier.weight(1.45f),
                onClick = { onSelectLanguage(AppLanguage.SYSTEM) }
            )
            LanguageOptionChip(
                label = strings.languageOptionZh,
                selected = selectedLanguage == AppLanguage.ZH,
                modifier = Modifier.weight(0.95f),
                onClick = { onSelectLanguage(AppLanguage.ZH) }
            )
            LanguageOptionChip(
                label = strings.languageOptionEn,
                selected = selectedLanguage == AppLanguage.EN,
                modifier = Modifier.weight(0.85f),
                onClick = { onSelectLanguage(AppLanguage.EN) }
            )
        }
    }
}

@Composable
private fun LanguageOptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "langChipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "langChipFg"
    )
    Surface(
        modifier = modifier
            .height(34.dp)
            .pressScale(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
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
}

@Composable
private fun ImeSwitchSettingRow(
    selectedShortcut: String,
    onSelectShortcut: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val normalized = ImeSwitchShortcut.normalize(selectedShortcut)
    val isCustomSaved = normalized.isNotBlank() && !ImeSwitchShortcut.isPreset(normalized)
    var showCustomInput by rememberSaveable { mutableStateOf(isCustomSaved) }
    var customDraft by rememberSaveable { mutableStateOf(if (isCustomSaved) normalized else "") }

    LaunchedEffect(normalized) {
        if (normalized.isNotBlank() && !ImeSwitchShortcut.isPreset(normalized)) {
            showCustomInput = true
            customDraft = normalized
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(strings.imeSwitchTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (strings.imeSwitchSubtitle.isNotBlank()) {
                Text(
                    strings.imeSwitchSubtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ImeSwitchShortcut.PRESETS.take(3).forEach { preset ->
                LanguageOptionChip(
                    label = ImeSwitchShortcut.formatLabel(preset),
                    selected = !showCustomInput && normalized == preset,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showCustomInput = false
                        onSelectShortcut(preset)
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ImeSwitchShortcut.PRESETS.drop(3).forEach { preset ->
                LanguageOptionChip(
                    label = ImeSwitchShortcut.formatLabel(preset),
                    selected = !showCustomInput && normalized == preset,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showCustomInput = false
                        onSelectShortcut(preset)
                    }
                )
            }
            LanguageOptionChip(
                label = strings.imeSwitchPresetCustom,
                selected = showCustomInput || isCustomSaved,
                modifier = Modifier.weight(1f),
                onClick = {
                    showCustomInput = true
                    if (customDraft.isBlank() && normalized.isNotBlank()) {
                        customDraft = normalized
                    }
                }
            )
            LanguageOptionChip(
                label = strings.imeSwitchPresetOff,
                selected = !showCustomInput && normalized.isBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    showCustomInput = false
                    onSelectShortcut("")
                }
            )
        }
        AnimatedVisibility(visible = showCustomInput) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customDraft,
                    onValueChange = { customDraft = it },
                    placeholder = { Text(strings.imeSwitchCustomPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                val saveEnabled = ImeSwitchShortcut.normalize(customDraft).isNotBlank()
                val saveInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        val cleaned = ImeSwitchShortcut.normalize(customDraft)
                        if (cleaned.isNotBlank()) {
                            onSelectShortcut(cleaned)
                        }
                    },
                    enabled = saveEnabled,
                    interactionSource = saveInteraction,
                    modifier = Modifier.pressScale(saveInteraction, pressedScale = 0.95f, enabled = saveEnabled)
                ) {
                    Text(strings.imeSwitchSaveBtn)
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun CustomSnippetsSection(
    snippets: List<String>,
    onAddSnippet: (String) -> Unit,
    onDeleteSnippet: (Int) -> Unit
) {
    val strings = LocalAppStrings.current
    var newSnippetText by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.snippetsTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newSnippetText,
                onValueChange = { newSnippetText = it },
                placeholder = { Text(strings.snippetsPlaceholder) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            val addInteraction = remember { MutableInteractionSource() }
            val addEnabled = newSnippetText.isNotBlank()
            Button(
                onClick = {
                    if (newSnippetText.isNotBlank()) {
                        onAddSnippet(newSnippetText.trim())
                        newSnippetText = ""
                    }
                },
                enabled = addEnabled,
                interactionSource = addInteraction,
                modifier = Modifier.pressScale(addInteraction, pressedScale = 0.95f, enabled = addEnabled)
            ) {
                Text(strings.snippetsAddBtn)
            }
        }

        Column(
            modifier = Modifier.animateContentSize(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            snippets.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { onDeleteSnippet(index) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = strings.snippetsDeleteDesc,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEditKeysSection(
    selectedKeys: List<String>,
    onToggleKey: (String) -> Unit,
    onResetDefaults: () -> Unit
) {
    val strings = LocalAppStrings.current
    val isDefault = selectedKeys == QuickEditKeys.DEFAULT_IDS

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.quickEdit.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = strings.quickEdit.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isDefault) {
                TextButton(
                    onClick = onResetDefaults,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(strings.quickEdit.reset, fontSize = 12.sp)
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickEditKeys.ALL.forEach { spec ->
                val checked = spec.id in selectedKeys
                QuickEditSelectableChip(
                    label = QuickEditKeys.settingLabel(spec.id, strings),
                    selected = checked,
                    onClick = { onToggleKey(spec.id) }
                )
            }
        }
    }
}

@Composable
private fun QuickEditSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "qeChipBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SilentMotion.DURATION_STATE_MS),
        label = "qeChipFg"
    )
    Surface(
        modifier = Modifier
            .pressScale(interactionSource, pressedScale = 0.95f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(10.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                maxLines = 1
            )
        }
    }
}
