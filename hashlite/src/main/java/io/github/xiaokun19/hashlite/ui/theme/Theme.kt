package io.github.xiaokun19.hashlite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val LightColors = lightColorScheme(
    primary = BlueLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = Slate,
    tertiary = Teal,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF10233F),
    secondary = Slate,
    tertiary = Teal,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
)

@Composable
fun HashLiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}

/** 校验通过/失败用的强调色（跟随**实际生效**的深浅色，而不是系统值——主题可手动覆盖）。 */
@Composable
fun verdictColors(pass: Boolean): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (pass) {
        (if (dark) SuccessDark else SuccessLight) to (if (dark) SuccessDark else SuccessLight)
    } else {
        MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error
    }
}