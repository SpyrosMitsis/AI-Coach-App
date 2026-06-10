package com.workoutmaker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================================
// "Serene Vanguard" — grounded, restorative wellness palette.
// Sage Green accents on a deep charcoal base; depth is tonal, not shadowed.
// ============================================================================

// Brand
val Sage = Color(0xFFB6CCB6)        // primary accent
val SageDim = Color(0xFF8DA38E)     // primary-container / muted sage
val Sand = Color(0xFFD3C4B3)        // secondary (warm sand)
val Moss = Color(0xFF5E6D5F)        // subtle distinctions

// Semantic readiness bands (kept calm, per design — no emergency reds unless needed)
val BandGreen = Color(0xFFB6CCB6)
val BandAmber = Color(0xFFE5C07B)
val BandRed = Color(0xFFFFB4AB)
val Red = BandRed

// Surfaces (tonal elevation: lighter = higher)
private val Base = Color(0xFF121414)
private val SurfaceCard = Color(0xFF1E2020)
private val SurfaceHigh = Color(0xFF282A2A)
private val SurfaceVariant = Color(0xFF333535)

private val DarkColors = darkColorScheme(
    primary = Sage,
    onPrimary = Color(0xFF223525),
    primaryContainer = Color(0xFF384B3A),
    onPrimaryContainer = Color(0xFFD1E9D1),
    secondary = Sand,
    onSecondary = Color(0xFF382F23),
    secondaryContainer = Color(0xFF51483A),
    onSecondaryContainer = Color(0xFFC4B6A5),
    tertiary = Color(0xFFBBCBBA),
    onTertiary = Color(0xFF263428),
    background = Base,
    onBackground = Color(0xFFE2E2E2),
    surface = SurfaceCard,
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFFC3C8C0),
    surfaceContainerLowest = Color(0xFF0D0F0F),
    surfaceContainerLow = Color(0xFF1A1C1C),
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = Color(0xFF333535),
    outline = Color(0xFF8D928B),
    outlineVariant = Color(0xFF434842),
    error = BandRed,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(primary = SageDim)

@Composable
fun WorkoutMakerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
