package looksee.angelll.com.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AppleBlue,
    secondary = GrayText,
    tertiary = AppleBlue,
    background = Color(0xFF000000), // iOS Dark Mode often pure black or very dark
    surface = DarkBackground,
    onPrimary = Color.White,
    onBackground = LightText,
    onSurface = LightText
)

private val LightColorScheme = lightColorScheme(
    primary = AppleBlue,
    secondary = GrayText,
    tertiary = AppleBlue,
    background = Color(0xFFF2F2F7), // iOS Light Mode background
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun LookSeeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disabled dynamicColor to keep brand consistency with iOS version
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
