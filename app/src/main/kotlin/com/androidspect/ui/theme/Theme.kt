package com.androidspect.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Mirrors apkauditor.com tokens - same green-on-deep-black family.
private val Green        = Color(0xFF10D689)
private val GreenDark    = Color(0xFF08B073)
private val Cyan         = Color(0xFF38BDF8)
private val DarkBg       = Color(0xFF07080B)
private val DarkSurface  = Color(0xFF0E1117)
private val DarkElevated = Color(0xFF161A22)
private val DarkText     = Color(0xFFF6F8FA)
private val DarkText2    = Color(0xFF9CA3AF)
private val Red          = Color(0xFFF43F5E)

// "Paper" light - warm off-white, not clinical. Greens darken slightly so
// they still pop on a light backdrop.
private val LightBg       = Color(0xFFF5F2EC)  // warm cream
private val LightSurface  = Color(0xFFFAF8F3)
private val LightElevated = Color(0xFFEEEAE1)
private val LightText     = Color(0xFF1A1F1B)
private val LightText2    = Color(0xFF5C6660)
private val GreenLight    = Color(0xFF07B273)
private val CyanLight     = Color(0xFF0E84B8)
private val RedLight      = Color(0xFFC2185B)

private val DarkScheme = darkColorScheme(
    primary            = Green,
    onPrimary          = DarkBg,
    primaryContainer   = GreenDark,
    onPrimaryContainer = Color.White,
    secondary          = Cyan,
    onSecondary        = DarkBg,
    background         = DarkBg,
    onBackground       = DarkText,
    surface            = DarkSurface,
    onSurface          = DarkText,
    surfaceVariant     = DarkElevated,
    onSurfaceVariant   = DarkText2,
    outline            = DarkText2,
    error              = Red,
    onError            = Color.White
)

private val LightScheme = lightColorScheme(
    primary            = GreenLight,
    onPrimary          = Color.White,
    primaryContainer   = GreenLight,
    onPrimaryContainer = Color.White,
    secondary          = CyanLight,
    onSecondary        = Color.White,
    background         = LightBg,
    onBackground       = LightText,
    surface            = LightSurface,
    onSurface          = LightText,
    surfaceVariant     = LightElevated,
    onSurfaceVariant   = LightText2,
    outline            = LightText2,
    error              = RedLight,
    onError            = Color.White
)

/**
 * Single source of truth for the active theme. Persisted in SharedPreferences
 * so the choice survives restarts. Default = dark (matches the brand).
 */
object ThemePref {
    private const val PREFS = "androidspect.ui"
    private const val KEY_DARK = "dark_theme"

    fun load(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)

    fun save(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK, dark).apply()
    }
}

/** Set by [AndroidSpectTheme] so any child composable can flip the theme. */
val LocalThemeToggle = compositionLocalOf<() -> Unit> { {} }
val LocalIsDark      = compositionLocalOf { true }

@Composable
fun AndroidSpectTheme(
    context: Context,
    content: @Composable () -> Unit
) {
    var dark by remember { mutableStateOf(ThemePref.load(context)) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalIsDark provides dark,
        LocalThemeToggle provides {
            dark = !dark
            ThemePref.save(context, dark)
        }
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography  = MaterialTheme.typography,
            content     = content
        )
    }
}
