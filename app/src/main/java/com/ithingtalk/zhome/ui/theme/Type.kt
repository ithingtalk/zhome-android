package com.ithingtalk.zhome.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * Indices match [com.ithingtalk.zhome.ui.screens.settings.SettingsViewModel.FONT_LABELS]:
 * 0 = system / Material default scale; 1..9 = target body size (sp) relative to 16sp baseline.
 */
internal fun fontScaleForFontSizeIdx(idx: Int): Float {
    if (idx <= 0) return 1f
    val targetSp = when (idx) {
        1 -> 11; 2 -> 13; 3 -> 15; 4 -> 17; 5 -> 19
        6 -> 21; 7 -> 23; 8 -> 25; 9 -> 27
        else -> 16
    }
    return targetSp / 16f
}

private fun TextStyle.scaleSize(factor: Float): TextStyle {
    val lh = lineHeight
    val ls = letterSpacing
    return copy(
        fontSize = (fontSize.value * factor).sp,
        lineHeight = if (!lh.isUnspecified) (lh.value * factor).sp else lh,
        letterSpacing = if (!ls.isUnspecified) (ls.value * factor).sp else ls,
    )
}

private fun Typography.scaled(factor: Float): Typography =
    copy(
        displayLarge = displayLarge.scaleSize(factor),
        displayMedium = displayMedium.scaleSize(factor),
        displaySmall = displaySmall.scaleSize(factor),
        headlineLarge = headlineLarge.scaleSize(factor),
        headlineMedium = headlineMedium.scaleSize(factor),
        headlineSmall = headlineSmall.scaleSize(factor),
        titleLarge = titleLarge.scaleSize(factor),
        titleMedium = titleMedium.scaleSize(factor),
        titleSmall = titleSmall.scaleSize(factor),
        bodyLarge = bodyLarge.scaleSize(factor),
        bodyMedium = bodyMedium.scaleSize(factor),
        bodySmall = bodySmall.scaleSize(factor),
        labelLarge = labelLarge.scaleSize(factor),
        labelMedium = labelMedium.scaleSize(factor),
        labelSmall = labelSmall.scaleSize(factor),
    )

private fun uniformTypography(baseSizeSp: Float): Typography {
    val base = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = baseSizeSp.sp,
        lineHeight = (baseSizeSp * 1.4f).sp,
        letterSpacing = 0.sp,
    )
    return Typography(
        displayLarge = base,
        displayMedium = base,
        displaySmall = base,
        headlineLarge = base,
        headlineMedium = base,
        headlineSmall = base,
        titleLarge = base,
        titleMedium = base,
        titleSmall = base,
        bodyLarge = base,
        bodyMedium = base,
        bodySmall = base,
        labelLarge = base,
        labelMedium = base,
        labelSmall = base,
    )
}

fun zhomeTypography(fontSizeIdx: Int): Typography {
    val scale = fontScaleForFontSizeIdx(fontSizeIdx)
    return uniformTypography(16f * scale)
}
