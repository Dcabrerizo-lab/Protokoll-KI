package de.protokollki.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    inversePrimary = Indigo80,
    secondary = Violet40,
    onSecondary = Color.White,
    secondaryContainer = Violet90,
    onSecondaryContainer = Violet20,
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Coral20,
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFFBFAFF),
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE4E1EF),
    onSurfaceVariant = Color(0xFF5B5A6B),
    surfaceTint = Indigo40,
    inverseSurface = Color(0xFF302F38),
    inverseOnSurface = Color(0xFFF3F0FA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF8B8A9C),
    outlineVariant = Color(0xFFD9D6E6),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFBFAFF),
    surfaceDim = Color(0xFFDCD9E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4FD),
    surfaceContainer = Color(0xFFF0EEF8),
    surfaceContainerHigh = Color(0xFFEAE8F3),
    surfaceContainerHighest = Color(0xFFE4E2ED),
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    inversePrimary = Indigo40,
    secondary = Violet80,
    onSecondary = Violet20,
    secondaryContainer = Violet30,
    onSecondaryContainer = Violet90,
    tertiary = Coral80,
    onTertiary = Coral20,
    tertiaryContainer = Coral30,
    onTertiaryContainer = Coral90,
    background = Color(0xFF121119),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF121119),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF46454F),
    onSurfaceVariant = Color(0xFFC8C5D3),
    surfaceTint = Indigo80,
    inverseSurface = Color(0xFFE5E1EC),
    inverseOnSurface = Color(0xFF302F38),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF928F9E),
    outlineVariant = Color(0xFF46454F),
    scrim = Color.Black,
    surfaceBright = Color(0xFF383640),
    surfaceDim = Color(0xFF121119),
    surfaceContainerLowest = Color(0xFF0D0C14),
    surfaceContainerLow = Color(0xFF1A1922),
    surfaceContainer = Color(0xFF1F1E27),
    surfaceContainerHigh = Color(0xFF292831),
    surfaceContainerHighest = Color(0xFF34333C),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ProtokollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,   // aus: einheitliches Markenbild
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Grüne Erfolgsfarbe passend zum aktuellen Thema. */
@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) SuccessGreenDark else SuccessGreen
