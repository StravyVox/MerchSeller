package com.merchseller.ui

import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF176056), onPrimary = Color.White,
    primaryContainer = Color(0xFFD2EDE6), onPrimaryContainer = Color(0xFF123D36),
    secondary = Color(0xFF465F58), secondaryContainer = Color(0xFFDEEAE4),
    onSecondaryContainer = Color(0xFF213B34),
    background = Color(0xFFF3F6F2), onBackground = Color(0xFF182520),
    surface = Color(0xFFFBFDF9), onSurface = Color(0xFF182520),
    surfaceVariant = Color(0xFFE7EDE8), onSurfaceVariant = Color(0xFF46564D),
    outline = Color(0xFF66776E), outlineVariant = Color(0xFFC7D3CB),
    error = Color(0xFF9C292C), onError = Color.White
)
private val MerchTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold)
)

private val MerchShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)
)

@Composable fun MerchTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = LightColors, typography = MerchTypography, shapes = MerchShapes, content = content
)
