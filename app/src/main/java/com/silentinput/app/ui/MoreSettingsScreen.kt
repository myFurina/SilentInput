package com.silentinput.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import com.silentinput.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    BackHandler(onBack = onBack)

    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val showInlineTrackpadInText by viewModel.showInlineTrackpadInText.collectAsState()
    val isNaturalScrolling by viewModel.isNaturalScrolling.collectAsState()
    val imeSwitchKey by viewModel.imeSwitchKey.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val strings = LocalAppStrings.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showHelpDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.moreSettingsTitle,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.collapseDesc
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDone) {
                        Text(strings.done, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
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
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToggleSettingRow(
                        title = strings.inlineTrackpadTitle,
                        subtitle = strings.inlineTrackpadSubtitle,
                        checked = showInlineTrackpadInText,
                        onCheckedChange = { viewModel.setShowInlineTrackpadInText(it) }
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
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    CustomSnippetsSection(
                        snippets = snippets,
                        onAddSnippet = {
                            viewModel.addSnippet(it)
                            coroutineScope.launch {
                                delay(80)
                                scrollState.animateScrollTo(
                                    value = scrollState.maxValue,
                                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                                )
                            }
                        },
                        onDeleteSnippet = { viewModel.deleteSnippet(it) }
                    )
                }
            }

            MoreSettingsEntryRow(
                title = strings.helpEntryTitle,
                summary = strings.helpEntrySubtitle,
                onClick = { showHelpDialog = true }
            )
        }
    }

    if (showHelpDialog) {
        HelpAndAboutDialog(onDismiss = { showHelpDialog = false })
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
            Text(
                strings.imeSwitchSubtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
