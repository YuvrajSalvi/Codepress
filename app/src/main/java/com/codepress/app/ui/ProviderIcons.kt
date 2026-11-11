package com.codepress.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codepress.app.R
import com.codepress.app.data.ProviderType

fun providerIconRes(p: ProviderType, openAiBase: String = ""): Int = when (p) {
    ProviderType.ANTHROPIC -> R.drawable.ic_claude
    ProviderType.GEMINI -> R.drawable.ic_gemini
    ProviderType.OPENAI -> if (openAiBase.contains("deepseek", ignoreCase = true)) R.drawable.ic_deepseek else R.drawable.ic_openai
    ProviderType.LOCAL -> R.drawable.ic_local_model
}

/** Round logo of an AI provider. Local models use a bundled vector (SVG-style) chip icon. */
@Composable
fun ProviderIcon(iconRes: Int, size: Dp = 20.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Image(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(size * 0.78f))
    }
}
