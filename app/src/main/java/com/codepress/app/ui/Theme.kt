package com.codepress.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Obsidian = Color(0xFF0B0D10)
val Charcoal = Color(0xFF15191F)
val Slate = Color(0xFF252B34)
val SoftWhite = Color(0xFFF5F7FA)
val ElectricBlue = Color(0xFF3B82F6)
val SuccessGreen = Color(0xFF22C55E)
val ErrorRed = Color(0xFFEF4444)
val Muted = Color(0xFF9AA4B2)

private val Scheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = SoftWhite,
    secondary = SuccessGreen,
    onSecondary = Obsidian,
    background = Obsidian,
    onBackground = SoftWhite,
    surface = Charcoal,
    onSurface = SoftWhite,
    surfaceVariant = Slate,
    onSurfaceVariant = Muted,
    surfaceContainer = Charcoal,
    surfaceContainerHigh = Slate,
    error = ErrorRed,
    onError = SoftWhite,
    outline = Color(0xFF3A424F),
    outlineVariant = Color(0xFF2C333D),
)

@Composable
fun CodePressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
