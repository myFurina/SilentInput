package com.silentinput.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.silentinput.app.model.AppStrings
import com.silentinput.app.model.ResolvedLanguage

val LocalResolvedLanguage = staticCompositionLocalOf { ResolvedLanguage.ZH }
val LocalAppStrings = staticCompositionLocalOf { AppStrings.ZH }
