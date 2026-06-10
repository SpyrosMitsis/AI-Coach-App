package com.workoutmaker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.workoutmaker.app.R

// Serene Vanguard uses Hanken Grotesk exclusively — tight tracking + bold weights
// for headlines, generous line height for body. We bundle the variable TTF and
// pull each weight off the wght axis (minSdk 26 supports FontVariation).

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun hanken(weight: Int) = Font(
    R.font.hanken_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Hanken = FontFamily(hanken(400), hanken(500), hanken(600), hanken(700))

private val d = Typography()

val AppTypography = Typography(
    displayLarge = d.displayLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displayMedium = d.displayMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displaySmall = d.displaySmall.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold),
    headlineLarge = d.headlineLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = d.headlineMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = d.headlineSmall.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = d.titleLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    titleMedium = d.titleMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    titleSmall = d.titleSmall.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    bodyLarge = d.bodyLarge.copy(fontFamily = Hanken),
    bodyMedium = d.bodyMedium.copy(fontFamily = Hanken),
    bodySmall = d.bodySmall.copy(fontFamily = Hanken),
    labelLarge = d.labelLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    labelMedium = d.labelMedium.copy(fontFamily = Hanken),
    labelSmall = d.labelSmall.copy(fontFamily = Hanken),
)
