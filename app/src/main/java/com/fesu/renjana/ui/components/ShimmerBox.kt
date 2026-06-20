package com.fesu.renjana.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer loading skeleton box.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerColors = listOf(
        Color(0xFFB0B0B0).copy(alpha = 0.2f),
        Color(0xFFB0B0B0).copy(alpha = 0.4f),
        Color(0xFFB0B0B0).copy(alpha = 0.2f),
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .drawWithCache {
                val gradient = Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(translateAnim - 300f, 0f),
                    end = Offset(translateAnim, 0f)
                )
                onDrawBehind {
                    drawRect(gradient)
                }
            }
    )
}

/**
 * A row of shimmer placeholders mimicking instance list cards.
 */
@Composable
fun ShimmerInstanceCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShimmerBox(
            modifier = Modifier.size(44.dp),
            cornerRadius = 12
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp),
                cornerRadius = 4
            )
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(12.dp),
                cornerRadius = 4
            )
        }
    }
}
