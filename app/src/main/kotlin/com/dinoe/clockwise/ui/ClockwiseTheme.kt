package com.dinoe.clockwise.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

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

/**
 * Keeps the status/navigation bar icons in sync with the app theme. Activities should call
 * [enableEdgeToEdge] with [SystemBarStyle.auto] in `onCreate`, then use this effect to react to
 * a user-forced light/dark/Gruvbox theme that differs from the system setting.
 */
@Composable
fun ApplySystemBarStyle(activity: ComponentActivity, themeMode: ThemeMode) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK, ThemeMode.GRUVBOX -> true
        ThemeMode.LIGHT -> false
    }
    LaunchedEffect(activity, darkTheme) {
        val statusBarStyle = SystemBarStyle.auto(
            lightScrim = AndroidColor.TRANSPARENT,
            darkScrim = AndroidColor.TRANSPARENT,
            detectDarkMode = { darkTheme },
        )
        val navigationBarStyle = SystemBarStyle.auto(
            lightScrim = AndroidColor.TRANSPARENT,
            darkScrim = AndroidColor.TRANSPARENT,
            detectDarkMode = { darkTheme },
        )
        activity.enableEdgeToEdge(
            statusBarStyle = statusBarStyle,
            navigationBarStyle = navigationBarStyle,
        )
    }
}

/**
 * Locale used for all date and time labels. Reading it through Compose keeps formatters in sync
 * with configuration changes (the activity is recreated on a locale change).
 */
@Composable
fun rememberLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.locales[0] }
}

/** Clock formatter that follows the device 12/24-hour setting and the current locale. */
@Composable
fun rememberClockFormatter(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = rememberLocale()
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) {
        val skeleton = if (is24Hour) "Hm" else "hm"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

/** Fully localized date format for the requested [FormatStyle]. */
@Composable
fun rememberLocalizedDateFormatter(
    style: FormatStyle = FormatStyle.MEDIUM,
): DateTimeFormatter {
    val locale = rememberLocale()
    return remember(locale, style) {
        DateTimeFormatter.ofLocalizedDate(style).withLocale(locale)
    }
}

/** Localized formatter for an ICU date skeleton such as `yMMMM` or `MMMdEEEE`. */
@Composable
fun rememberDatePatternFormatter(skeleton: String): DateTimeFormatter {
    val locale = rememberLocale()
    return remember(locale, skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

/** Monday-first weekday labels (short form) supplied by the current locale. */
@Composable
fun rememberWeekdayLabels(): List<String> {
    val locale = rememberLocale()
    return remember(locale) {
        (1..7).map { day ->
            DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale)
        }
    }
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
