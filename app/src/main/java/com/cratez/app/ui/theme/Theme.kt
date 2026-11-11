package com.cratez.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cratez.app.R

object C {
    val Bg = Color(0xFF04060A)
    val Panel = Color(0xFF0A1018)
    val PanelHi = Color(0xFF0F1823)
    val Line = Color(0xFF1A2938)
    val Electric = Color(0xFF1EC8FF)
    val ElectricDim = Color(0xFF0D5F7D)
    val Glow = Color(0x551EC8FF)
    val Text = Color(0xFFD6E6F2)
    val TextDim = Color(0xFF6C8295)
    val Hot = Color(0xFFFF2E7E)
    val Warn = Color(0xFFF3E600)
    val Ok = Color(0xFF39FF9E)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object F {
    val Display = FontFamily(
        Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(R.font.orbitron, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
        Font(R.font.orbitron, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    )
    val Body = FontFamily(
        Font(R.font.rajdhani_medium, FontWeight.Medium),
        Font(R.font.rajdhani_medium, FontWeight.Normal),
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
        Font(R.font.rajdhani_bold, FontWeight.Bold),
    )
    val Mono = FontFamily(Font(R.font.sharetech_mono))
}

private val typography = Typography(
    bodyLarge = TextStyle(fontFamily = F.Body, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = C.Text),
    bodyMedium = TextStyle(fontFamily = F.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = C.Text),
    bodySmall = TextStyle(fontFamily = F.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = C.TextDim),
    titleLarge = TextStyle(fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp),
    titleMedium = TextStyle(fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleSmall = TextStyle(fontFamily = F.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelLarge = TextStyle(fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp),
    labelMedium = TextStyle(fontFamily = F.Display, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = F.Mono, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

@Composable
fun CratezTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Electric, onPrimary = Color.Black, secondary = C.Hot, background = C.Bg, onBackground = C.Text,
            surface = C.Panel, onSurface = C.Text, surfaceVariant = C.PanelHi, onSurfaceVariant = C.TextDim, outline = C.Line,
            error = C.Hot, surfaceContainer = C.Panel, surfaceContainerHigh = C.PanelHi, surfaceContainerHighest = C.PanelHi,
            surfaceContainerLow = C.Panel, primaryContainer = C.ElectricDim, onPrimaryContainer = C.Text,
        ),
        typography = typography,
        content = content,
    )
}
