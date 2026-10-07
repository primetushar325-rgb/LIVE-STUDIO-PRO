package com.livevip.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Subtle neon glow built from an elevation shadow with a tinted spot color plus a
 * thin accent border. GPU friendly — no heavy runtime blur, so it never costs frames
 * while encoding.
 */
fun Modifier.neonGlow(
    color: Color,
    cornerRadius: Dp = 14.dp,
    radius: Dp = 14.dp,
    borderAlpha: Float = 0.55f
): Modifier = composed {
    if (!LocalGlowEnabled.current) {
        this
    } else {
        val shape = RoundedCornerShape(cornerRadius)
        this
            .shadow(
                elevation = radius,
                shape = shape,
                clip = false,
                ambientColor = color,
                spotColor = color
            )
            .border(1.dp, color.copy(alpha = borderAlpha), shape)
    }
}

@Composable
fun glowAccent(): Color = LocalAccentColor.current
