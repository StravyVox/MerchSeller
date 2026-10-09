package com.merchseller.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val LightColors = lightColorScheme(
    primary = Color(0xFF2859DD), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF081A48),
    secondary = Color(0xFF007F73), tertiary = Color(0xFFE96A45),
    background = Color(0xFFF6F8FF), surface = Color(0xFFFCFCFF), surfaceVariant = Color(0xFFE7EBF8),
    outlineVariant = Color(0xFFCBD3E8)
)
private val MerchTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold)
)

@Composable fun MerchTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = LightColors, typography = MerchTypography, content = content)
