package org.melodist.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import org.melodist.data.AppColorTheme
import org.melodist.data.ThemeMode

private fun createDarkColorScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
): ColorScheme =
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = QQMusicBackground,
        onBackground = QQMusicOnBackground,
        surface = QQMusicSurface,
        onSurface = QQMusicOnSurface,
        surfaceVariant = QQMusicSurfaceVariant,
        onSurfaceVariant = QQMusicOnSurfaceVariant,
        surfaceContainer = QQMusicSurfaceContainer,
        surfaceContainerHigh = QQMusicSurfaceContainerHigh,
        outline = QQMusicOutline,
        outlineVariant = QQMusicOutlineVariant,
        error = QQMusicError,
        onError = QQMusicOnError,
    )

// MusicFree 风格扁平亮色底：所有预设主题共享同一套中性色，仅主色不同
private fun createLightColorScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
): ColorScheme =
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = MusicFreeLightSecondary,
        onSecondary = MusicFreeLightOnSecondary,
        secondaryContainer = MusicFreeLightSecondaryContainer,
        onSecondaryContainer = MusicFreeLightOnSecondaryContainer,
        background = MusicFreeLightBackground,
        onBackground = MusicFreeLightOnBackground,
        surface = MusicFreeLightSurface,
        onSurface = MusicFreeLightOnSurface,
        surfaceVariant = MusicFreeLightSurfaceVariant,
        onSurfaceVariant = MusicFreeLightOnSurfaceVariant,
        surfaceContainer = MusicFreeLightSurfaceContainer,
        surfaceContainerHigh = MusicFreeLightSurfaceContainerHigh,
        outline = MusicFreeLightOutline,
        outlineVariant = MusicFreeLightOutlineVariant,
        error = MusicFreeLightError,
        onError = MusicFreeLightOnError,
    )

private val DefaultDarkColorScheme =
    createDarkColorScheme(
        primary = QQMusicPrimary,
        onPrimary = QQMusicOnPrimary,
        primaryContainer = QQMusicPrimaryContainer,
        onPrimaryContainer = QQMusicOnPrimaryContainer,
        secondary = QQMusicSecondary,
        onSecondary = QQMusicOnSecondary,
        secondaryContainer = QQMusicSecondaryContainer,
        onSecondaryContainer = QQMusicOnSecondaryContainer,
    )

private val DefaultLightColorScheme =
    createLightColorScheme(
        primary = MusicFreeLightPrimary,
        onPrimary = MusicFreeLightOnPrimary,
        primaryContainer = MusicFreeLightPrimaryContainer,
        onPrimaryContainer = MusicFreeLightOnPrimaryContainer,
    )

private val VioletDarkColorScheme =
    createDarkColorScheme(
        primary = VioletPrimary,
        onPrimary = VioletOnPrimary,
        primaryContainer = VioletPrimaryContainer,
        onPrimaryContainer = VioletOnPrimaryContainer,
        secondary = VioletPrimary,
        onSecondary = VioletOnPrimary,
        secondaryContainer = VioletPrimaryContainer,
        onSecondaryContainer = VioletOnPrimaryContainer,
    )

private val VioletLightColorScheme =
    createLightColorScheme(
        primary = VioletLightPrimary,
        onPrimary = VioletLightOnPrimary,
        primaryContainer = VioletOnPrimaryContainer,
        onPrimaryContainer = VioletPrimaryContainer,
    )

private val EmeraldDarkColorScheme =
    createDarkColorScheme(
        primary = EmeraldPrimary,
        onPrimary = EmeraldOnPrimary,
        primaryContainer = EmeraldPrimaryContainer,
        onPrimaryContainer = EmeraldOnPrimaryContainer,
        secondary = EmeraldPrimary,
        onSecondary = EmeraldOnPrimary,
        secondaryContainer = EmeraldPrimaryContainer,
        onSecondaryContainer = EmeraldOnPrimaryContainer,
    )

private val EmeraldLightColorScheme =
    createLightColorScheme(
        primary = EmeraldLightPrimary,
        onPrimary = EmeraldLightOnPrimary,
        primaryContainer = EmeraldOnPrimaryContainer,
        onPrimaryContainer = EmeraldPrimaryContainer,
    )

private val AmberDarkColorScheme =
    createDarkColorScheme(
        primary = AmberPrimary,
        onPrimary = AmberOnPrimary,
        primaryContainer = AmberPrimaryContainer,
        onPrimaryContainer = AmberOnPrimaryContainer,
        secondary = AmberPrimary,
        onSecondary = AmberOnPrimary,
        secondaryContainer = AmberPrimaryContainer,
        onSecondaryContainer = AmberOnPrimaryContainer,
    )

private val AmberLightColorScheme =
    createLightColorScheme(
        primary = AmberLightPrimary,
        onPrimary = AmberLightOnPrimary,
        primaryContainer = AmberOnPrimaryContainer,
        onPrimaryContainer = AmberPrimaryContainer,
    )

private val RoseDarkColorScheme =
    createDarkColorScheme(
        primary = RosePrimary,
        onPrimary = RoseOnPrimary,
        primaryContainer = RosePrimaryContainer,
        onPrimaryContainer = RoseOnPrimaryContainer,
        secondary = RosePrimary,
        onSecondary = RoseOnPrimary,
        secondaryContainer = RosePrimaryContainer,
        onSecondaryContainer = RosePrimaryContainer,
    )

private val RoseLightColorScheme =
    createLightColorScheme(
        primary = RoseLightPrimary,
        onPrimary = RoseLightOnPrimary,
        primaryContainer = RoseOnPrimaryContainer,
        onPrimaryContainer = RosePrimaryContainer,
    )

fun parseHexColor(
    hex: String,
    fallback: Color = QQMusicPrimary,
): Color {
    val clean = hex.trim().removePrefix("#")
    val colorLong =
        when (clean.length) {
            6 -> runCatching { 0xFF000000 or clean.toLong(16) }.getOrNull()
            8 -> runCatching { clean.toLong(16) }.getOrNull()
            else -> null
        }
    return if (colorLong != null) Color(colorLong) else fallback
}

private fun Color.adjustHsl(
    saturationFactor: Float = 1f,
    lightness: Float,
): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(
        android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt(),
        ),
        hsl,
    )
    hsl[1] = (hsl[1] * saturationFactor).coerceIn(0f, 1f)
    hsl[2] = lightness.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun createCustomDarkColorScheme(seedColor: Color): ColorScheme {
    // 若原色在暗色背景下过暗，适当调高明度以符合 M3 暗色主色标准
    val effectivePrimary =
        if (seedColor.luminance() < 0.22f) {
            seedColor.adjustHsl(saturationFactor = 0.9f, lightness = 0.72f)
        } else {
            seedColor
        }
    val onPrimary = if (effectivePrimary.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White
    val container = seedColor.adjustHsl(saturationFactor = 0.95f, lightness = 0.22f)
    val onContainer = seedColor.adjustHsl(saturationFactor = 0.8f, lightness = 0.88f)

    return createDarkColorScheme(
        primary = effectivePrimary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = effectivePrimary.adjustHsl(saturationFactor = 0.7f, lightness = 0.65f),
        onSecondary = if (effectivePrimary.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
    )
}

private fun createCustomLightColorScheme(seedColor: Color): ColorScheme {
    // 若原色在浅色背景下过亮，降低明度
    val effectivePrimary =
        if (seedColor.luminance() > 0.65f) {
            seedColor.adjustHsl(saturationFactor = 0.95f, lightness = 0.40f)
        } else {
            seedColor
        }
    val onPrimary = if (effectivePrimary.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White
    val container = seedColor.adjustHsl(saturationFactor = 0.85f, lightness = 0.92f)
    val onContainer = seedColor.adjustHsl(saturationFactor = 0.95f, lightness = 0.15f)

    return createLightColorScheme(
        primary = effectivePrimary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
    )
}

fun getPresetColorScheme(
    theme: AppColorTheme,
    isDark: Boolean,
    customColorHex: String = "#1A73E8",
): ColorScheme =
    when (theme) {
        AppColorTheme.Default -> {
            if (isDark) DefaultDarkColorScheme else DefaultLightColorScheme
        }

        AppColorTheme.Violet -> {
            if (isDark) VioletDarkColorScheme else VioletLightColorScheme
        }

        AppColorTheme.Emerald -> {
            if (isDark) EmeraldDarkColorScheme else EmeraldLightColorScheme
        }

        AppColorTheme.Amber -> {
            if (isDark) AmberDarkColorScheme else AmberLightColorScheme
        }

        AppColorTheme.Rose -> {
            if (isDark) RoseDarkColorScheme else RoseLightColorScheme
        }

        AppColorTheme.Custom -> {
            val seed = parseHexColor(customColorHex)
            if (isDark) createCustomDarkColorScheme(seed) else createCustomLightColorScheme(seed)
        }
    }

private fun ColorScheme.withAmoled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainer = Color(0xFF0F0F0F),
        surfaceContainerHigh = Color(0xFF1A1A1A),
    )

val LocalDarkTheme = androidx.compose.runtime.compositionLocalOf { false }
val LocalAmoledDark = androidx.compose.runtime.compositionLocalOf { false }

// MusicFree 风格统一圆角体系：扁平卡片、胶囊搜索栏
private val QQMusicShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

@Composable
fun isAppInDarkTheme(): Boolean = LocalDarkTheme.current

@Composable
fun isAppInAmoledDark(): Boolean = LocalAmoledDark.current

@Composable
fun QQMusicMobileTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    colorTheme: AppColorTheme = AppColorTheme.Default,
    customColorHex: String = "#1A73E8",
    amoledDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }

    val baseScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            else -> {
                getPresetColorScheme(colorTheme, darkTheme, customColorHex)
            }
        }

    val isAmoled = darkTheme && amoledDark
    val finalScheme = if (isAmoled) baseScheme.withAmoled() else baseScheme

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAmoledDark provides isAmoled,
    ) {
        MaterialTheme(
            colorScheme = finalScheme,
            shapes = QQMusicShapes,
            content = content,
        )
    }
}
