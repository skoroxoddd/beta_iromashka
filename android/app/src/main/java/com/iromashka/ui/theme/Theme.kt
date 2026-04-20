package com.iromashka.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.iromashka.storage.Prefs

enum class ThemeMode {
    Iromashka,
    QIP;
}

data class ThemePalette(
    val background: Color,
    val surface: Color,
    val titleBar: List<Color>,
    val bubbleOut: Color,
    val bubbleIn: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val accent: Color,
    val onlineGreen: Color,
    val offlineGray: Color,
    val errorRed: Color,
    val avatar: Color,
    val inputBg: Color,
    val dropdownBg: Color,
    val chatBg: Color,
)

val IcqpPalette = ThemePalette(
    background    = Color(0xFFEEF3FA),
    surface       = Color(0xFFFFFFFF),
    titleBar      = listOf(Color(0xFF1B3A6B), Color(0xFF2A5298)),
    bubbleOut     = Color(0xFF1B3A6B),
    bubbleIn      = Color(0xFFFFFFFF),
    textPrimary   = Color(0xFF1C2B4A),
    textSecondary = Color(0xFF6B7FA3),
    divider       = Color(0xFFC8D8EA),
    accent        = Color(0xFF2A5298),
    onlineGreen   = Color(0xFF2AB06F),
    offlineGray   = Color(0xFF9BABB8),
    errorRed      = Color(0xFFCC0000),
    avatar        = Color(0xFF1B3A6B),
    inputBg       = Color(0xFFFFFFFF),
    dropdownBg    = Color(0xFFFFFFFF),
    chatBg        = Color(0xFFE8EEF7),
)

val QipPalette = ThemePalette(
    background    = Color(0xFF1E1E2E),
    surface       = Color(0xFF272736),
    titleBar      = listOf(Color(0xFF272736), Color(0xFF1B1B2A)),
    bubbleOut     = Color(0xFF3D3D5C),
    bubbleIn      = Color(0xFF2A2A3A),
    textPrimary   = Color(0xFFD5D5E5),
    textSecondary = Color(0xFF8888A0),
    divider       = Color(0xFF33334A),
    accent        = Color(0xFFDDB65C),
    onlineGreen   = Color(0xFF4CB860),
    offlineGray   = Color(0xFF666677),
    errorRed      = Color(0xFFD05C5C),
    avatar        = Color(0xFFDDB65C),
    inputBg       = Color(0xFF2A2A3A),
    dropdownBg    = Color(0xFF272736),
    chatBg        = Color(0xFF15152A),
)

val LocalThemePalette = compositionLocalOf<ThemePalette> { IcqpPalette }
val LocalThemeMode = compositionLocalOf<ThemeMode> { ThemeMode.Iromashka }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val themeName = Prefs.getTheme(ctx)
    val mode = ThemeMode.entries.find { it.name == themeName } ?: ThemeMode.Iromashka
    val palette = when (mode) {
        ThemeMode.Iromashka -> IcqpPalette
        ThemeMode.QIP -> QipPalette
    }

    val mdScheme = when (mode) {
        ThemeMode.QIP -> darkColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
        )
        else -> lightColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
        )
    }

    CompositionLocalProvider(
        LocalThemePalette provides palette,
        LocalThemeMode provides mode,
    ) {
        MaterialTheme(
            colorScheme = mdScheme,
            content = content
        )
    }
}
