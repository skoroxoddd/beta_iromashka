package com.iromashka.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.iromashka.storage.Prefs

enum class ThemeMode {
    ROSE,
    QIP,
    CUSTOM
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

val RosePalette = ThemePalette(
    background    = Color(0xFFE0F0F8),
    surface       = Color(0xFFFFFFFF),
    titleBar      = listOf(Color(0xFF27AE60), Color(0xFF219A52)),
    bubbleOut     = Color(0xFF88B0C8),
    bubbleIn      = Color(0xFF3890C8),
    textPrimary   = Color(0xFF304860),
    textSecondary = Color(0xFFA0B8CC),
    divider       = Color(0xFFC0D8E8),
    accent        = Color(0xFFF8D860),
    onlineGreen   = Color(0xFF27AE60),
    offlineGray   = Color(0xFFA0B8CC),
    errorRed      = Color(0xFFC0392B),
    avatar        = Color(0xFF3890C8),
    inputBg       = Color(0xFFFFFFFF),
    dropdownBg    = Color(0xFFFFFFFF),
    chatBg        = Color(0xFFE0F0F8),
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

val LocalThemePalette = compositionLocalOf<ThemePalette> { RosePalette }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val themeName = Prefs.getTheme(ctx)
    val palette = when {
        themeName.startsWith("CUSTOM_") -> RosePalette
        themeName == "QIP" -> QipPalette
        else -> RosePalette
    }

    val isDark = themeName == "QIP"
    val mdScheme = if (isDark) {
        darkColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
        )
    }

    CompositionLocalProvider(
        LocalThemePalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = mdScheme,
            content = content
        )
    }
}
