package com.iromashka.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.iromashka.storage.Prefs

enum class ThemeMode { Light, Dark }

data class ThemePalette(
    val background: Color,
    val surface: Color,
    val surfaceHover: Color,
    val titleBar: Color,
    val bubbleOut: Color,
    val bubbleIn: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val divider: Color,
    val accent: Color,
    val accentDark: Color,
    val accentLight: Color,
    val accentBg: Color,
    val onlineGreen: Color,
    val offlineGray: Color,
    val errorRed: Color,
    val chatBg: Color,
    val inputBg: Color,
)

// Light — matches PWA light theme
val LightPalette = ThemePalette(
    background    = Color(0xFFF0F5F9),
    surface       = Color(0xFFFFFFFF),
    surfaceHover  = Color(0xFFF4F4F5),
    titleBar      = Color(0xFF27AE60),
    bubbleOut     = Color(0xFFD9F5E5),
    bubbleIn      = Color(0xFFFFFFFF),
    textPrimary   = Color(0xDE000000),
    textSecondary = Color(0xFF707579),
    textMuted     = Color(0xFFA0AAB0),
    divider       = Color(0xFFDADDE1),
    accent        = Color(0xFF27AE60),
    accentDark    = Color(0xFF1E8C4C),
    accentLight   = Color(0xFF4FCE84),
    accentBg      = Color(0x1A27AE60),
    onlineGreen   = Color(0xFF0AC630),
    offlineGray   = Color(0xFFC4C9CC),
    errorRed      = Color(0xFFE53935),
    chatBg        = Color(0xFFE4EDF3),
    inputBg       = Color(0xFFF0F5F9),
)

// Dark — matches PWA dark theme
val DarkPalette = ThemePalette(
    background    = Color(0xFF212D3B),
    surface       = Color(0xFF17212B),
    surfaceHover  = Color(0xFF242F3D),
    titleBar      = Color(0xFF17212B),
    bubbleOut     = Color(0xFF1A4A2E),
    bubbleIn      = Color(0xFF182533),
    textPrimary   = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFAAB8C2),
    textMuted     = Color(0xFF617D8D),
    divider       = Color(0xFF0D1821),
    accent        = Color(0xFF27AE60),
    accentDark    = Color(0xFF1E8C4C),
    accentLight   = Color(0xFF4FCE84),
    accentBg      = Color(0x1F27AE60),
    onlineGreen   = Color(0xFF0AC630),
    offlineGray   = Color(0xFF617D8D),
    errorRed      = Color(0xFFE53935),
    chatBg        = Color(0xFF0D1B12),
    inputBg       = Color(0xFF17212B),
)

val LocalThemePalette = compositionLocalOf<ThemePalette> { LightPalette }
val LocalThemeMode = compositionLocalOf<ThemeMode> { ThemeMode.Light }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val themeName = Prefs.getTheme(ctx)
    val mode = if (themeName == "Dark") ThemeMode.Dark else ThemeMode.Light
    val palette = if (mode == ThemeMode.Dark) DarkPalette else LightPalette

    val mdScheme = if (mode == ThemeMode.Dark) {
        darkColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onPrimary = Color.White,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onPrimary = Color.White,
        )
    }

    CompositionLocalProvider(
        LocalThemePalette provides palette,
        LocalThemeMode provides mode,
    ) {
        MaterialTheme(colorScheme = mdScheme, content = content)
    }
}
