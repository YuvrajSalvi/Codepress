package com.codepress.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette follows the Linear design spec from awesome-design-md:
// near-black canvas, a four step surface ladder, hairline borders, one lavender-blue accent.
val Canvas = Color(0xFF010102)
val Surface1 = Color(0xFF0F1011)
val Surface2 = Color(0xFF141516)
val Surface3 = Color(0xFF18191A)
val Surface4 = Color(0xFF191A1B)
val Hairline = Color(0xFF23252A)
val HairlineStrong = Color(0xFF34343A)
val Ink = Color(0xFFF7F8F8)
val InkMuted = Color(0xFFD0D6E0)
val InkSubtle = Color(0xFF8A8F98)
val Lavender = Color(0xFF5E6AD2)

/** Background of the message composer. */
val ComposerBlue = Color(0xFF5E69D1)
val Success = Color(0xFF27A644)
val ErrorRed = Color(0xFFE5484D)

private val Scheme = darkColorScheme(
    primary = Lavender,
    onPrimary = Color.White,
    secondary = Success,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Surface1,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = InkSubtle,
    surfaceContainer = Surface1,
    surfaceContainerLow = Surface1,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Surface4,
    outline = HairlineStrong,
    outlineVariant = Hairline,
    error = ErrorRed,
    onError = Color.White,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun CodePressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
