package com.dinoe.clockwise.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun ClockwiseTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when (themeMode) {
        ThemeMode.SYSTEM -> if (darkTheme) systemDarkScheme(context) else systemLightScheme(context)
        ThemeMode.DARK -> systemDarkScheme(context)
        ThemeMode.LIGHT -> systemLightScheme(context)
        ThemeMode.GRUVBOX -> gruvboxColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

internal fun systemDarkScheme(context: Context): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }

internal fun systemLightScheme(context: Context): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme()
    }

private val GruvboxBackground = Color(0xFF1D2021)
private val GruvboxForeground = Color(0xFFD4BE98)
private val GruvboxYellow = Color(0xFFD8A657)

private val gruvboxColorScheme = darkColorScheme(
    primary = GruvboxYellow,
    onPrimary = GruvboxBackground,
    primaryContainer = Color(0xFF4F3D21),
    onPrimaryContainer = Color(0xFFE6C98C),
    inversePrimary = Color(0xFF8A6A25),
    secondary = Color(0xFFA9B665),
    onSecondary = GruvboxBackground,
    secondaryContainer = Color(0xFF414A2E),
    onSecondaryContainer = Color(0xFFCFD99F),
    tertiary = Color(0xFF7DAEA3),
    onTertiary = GruvboxBackground,
    tertiaryContainer = Color(0xFF38504B),
    onTertiaryContainer = Color(0xFFA9CFC6),
    background = GruvboxBackground,
    onBackground = GruvboxForeground,
    surface = GruvboxBackground,
    onSurface = GruvboxForeground,
    surfaceVariant = Color(0xFF32302F),
    onSurfaceVariant = Color(0xFF928374),
    surfaceTint = GruvboxYellow,
    inverseSurface = GruvboxForeground,
    inverseOnSurface = GruvboxBackground,
    error = Color(0xFFEA6962),
    onError = GruvboxBackground,
    errorContainer = Color(0xFF532F2B),
    onErrorContainer = Color(0xFFF2B3AE),
    outline = Color(0xFF665C54),
    outlineVariant = Color(0xFF3F3B36),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3C3936),
    surfaceDim = Color(0xFF171A19),
    surfaceContainerLowest = Color(0xFF141818),
    surfaceContainerLow = Color(0xFF232726),
    surfaceContainer = Color(0xFF2C2C29),
    surfaceContainerHigh = Color(0xFF32302F),
    surfaceContainerHighest = Color(0xFF3C3936),
)
