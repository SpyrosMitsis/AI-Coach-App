package com.workoutmaker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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

// Light "Serene Vanguard": the same sage identity on warm sage-tinted paper.
// Depth is tonal (cards lift lighter off the page; inset rows recess darker),
// mirroring the dark theme's logic rather than relying on shadows. The accent is
// a DEEPER sage than the dark-mode pastel so it stays legible on a light surface.
private val LightColors = lightColorScheme(
    primary = Color(0xFF40624A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2E8C7),
    onPrimaryContainer = Color(0xFF00210D),
    secondary = Color(0xFF6A5D4E),          // warm taupe — the light-mode "Sand"
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E3CE),
    onSecondaryContainer = Color(0xFF241A0C),
    tertiary = Color(0xFF4A635A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE9DC),
    onTertiaryContainer = Color(0xFF06201A),
    background = Color(0xFFF1F4EC),          // soft sage paper
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFAFCF4),             // card — lifts lighter off the paper
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFDDE5D8),
    onSurfaceVariant = Color(0xFF424A40),
    // Lowest is intentionally DARKER than the card so inset stats/quote blocks
    // read as recessed (same trick as the dark theme, inverted).
    surfaceContainerLowest = Color(0xFFE7ECE0),
    surfaceContainerLow = Color(0xFFF3F6EC),
    surfaceContainer = Color(0xFFFAFCF4),
    surfaceContainerHigh = Color(0xFFEFF2E8),
    surfaceContainerHighest = Color(0xFFE9EDE2),
    outline = Color(0xFF72796D),
    outlineVariant = Color(0xFFC2C9BB),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

// The one brand accent with no Material colorScheme slot: caution/amber. Sage →
// colorScheme.primary, Sand → secondary, red → error already adapt; amber needs
// this. Reads the live scheme's luminance so it tracks whichever theme is active
// (system or forced). Dark returns the exact original BandAmber → no dark change.
@Composable
fun amberAccent(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5) Color(0xFF8A5A12) else BandAmber

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
