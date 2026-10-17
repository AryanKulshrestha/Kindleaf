package com.kindleaf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = SurfaceWhite,
    primaryContainer = PrimaryGreen.copy(alpha = 0.12f),
    onPrimaryContainer = DarkGreen,
    secondary = DarkGreen,
    onSecondary = SurfaceWhite,
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = Error,
    onError = SurfaceWhite,
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF7F1D1D),
    secondaryContainer = PrimaryGreen.copy(alpha = 0.10f),
    onSecondaryContainer = DarkGreen,
    tertiary = AccentGold,
    onTertiary = SurfaceWhite,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreenLight,
    onPrimary = DarkGreen,
    primaryContainer = PrimaryGreen.copy(alpha = 0.24f),
    onPrimaryContainer = PrimaryGreenLight,
    secondary = PrimaryGreenLight,
    onSecondary = DarkGreen,
    background = DarkBackground,
    onBackground = SurfaceWhite,
    surface = DarkSurface,
    onSurface = SurfaceWhite,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondaryDark,
    outline = Border.copy(alpha = 0.3f),
    error = Color(0xFFF28B82),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    secondaryContainer = PrimaryGreen.copy(alpha = 0.22f),
    onSecondaryContainer = PrimaryGreenLight,
    tertiary = AccentGold,
    onTertiary = DarkGreen,
)

val KindleafShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KindleafTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = KindleafShapes,
        content = content
    )
}
