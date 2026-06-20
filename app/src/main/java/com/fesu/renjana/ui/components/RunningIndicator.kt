package com.fesu.renjana.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fesu.renjana.ui.theme.StatusError
import com.fesu.renjana.ui.theme.StatusIdle
import com.fesu.renjana.ui.theme.StatusPaused
import com.fesu.renjana.ui.theme.StatusRunning

/**
 * Pulsing dot indicator for instance status.
 *
 * - Running: green pulsing dot
 * - Paused: orange static dot
 * - Error: red static dot
 * - Idle: gray static dot
 */
@Composable
fun RunningIndicator(
    isRunning: Boolean = false,
    isPaused: Boolean = false,
    isError: Boolean = false,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val color = when {
        isRunning -> StatusRunning
        isPaused -> StatusPaused
        isError -> StatusError
        else -> StatusIdle
    }

    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(if (isRunning) scale else 1f)
            .clip(CircleShape)
            .background(color)
    )
}
