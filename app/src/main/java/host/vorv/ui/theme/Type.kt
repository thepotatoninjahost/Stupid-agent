package host.vorv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The app draws with its own typeface: without this it inherits the phone's decorative
// system font, and every screen is drawn in whatever the owner picked there.
private val AppFont = FontFamily.SansSerif

private fun style(weight: FontWeight, size: Int, line: Int, color: androidx.compose.ui.graphics.Color) =
    TextStyle(fontFamily = AppFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, color = color)

val Typography = Typography(
    headlineLarge = style(FontWeight.Bold, 32, 40, OnBackground),
    headlineMedium = style(FontWeight.Bold, 24, 32, OnBackground),
    titleLarge = style(FontWeight.SemiBold, 18, 24, OnBackground),
    titleMedium = style(FontWeight.SemiBold, 16, 22, OnBackground),
    bodyLarge = style(FontWeight.Normal, 16, 24, OnBackground),
    bodyMedium = style(FontWeight.Normal, 14, 20, OnBackground),
    bodySmall = style(FontWeight.Normal, 12, 16, OnSurfaceVariant),
    labelLarge = style(FontWeight.SemiBold, 14, 20, OnBackground),
    labelMedium = style(FontWeight.Medium, 12, 16, OnSurfaceVariant),
    labelSmall = style(FontWeight.Medium, 10, 14, OnSurfaceVariant),
)
