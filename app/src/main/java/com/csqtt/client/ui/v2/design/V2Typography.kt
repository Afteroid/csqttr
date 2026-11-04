package com.csqtt.client.ui.v2.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val V2Typography = Typography(
    // Existing system font — screen titles, hero text, versions.
    displayLarge = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    // Big list row titles (routing cards).
    titleLarge = TextStyle(
        fontFamily = V2DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    // Card and row titles; regular weight, as in the original interface.
    titleMedium = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Body,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Body,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Body,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Body,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Secondary,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Action,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = V2TextSizes.Navigation,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = V2BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp,
    ),
)
