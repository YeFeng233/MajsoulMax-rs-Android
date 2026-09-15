package moe.majsoulmax.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Mahjong-table green with a tile-red accent, used whenever the platform cannot
 * provide a dynamic palette (below Android 12).
 */
private val BrandGreen = Color(0xFF1B5E4B)
private val BrandGreenLight = Color(0xFF4C8C77)
private val BrandRed = Color(0xFFC62828)
private val BrandSand = Color(0xFFE8E1D3)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6E1D2),
    onPrimaryContainer = Color(0xFF002014),
    secondary = Color(0xFF4C6359),
    tertiary = BrandRed,
    onTertiary = Color.White,
    surfaceVariant = BrandSand,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFFB6E1D2),
    secondary = Color(0xFFB3CCC0),
    tertiary = Color(0xFFFFB4AB),
    error = Color(0xFFFFB4AB),
)

@Composable
fun MajsoulMaxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, typography = MajsoulTypography, content = content)
}
