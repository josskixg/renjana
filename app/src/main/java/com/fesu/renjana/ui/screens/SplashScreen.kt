package com.fesu.renjana.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fesu.renjana.ui.theme.HeadlineFont
import com.fesu.renjana.ui.theme.BodyFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Splash-specific colors — always dark, ignores user theme
private val SplashBackground = Color(0xFF0A0A0B)
private val SplashPrimary    = Color(0xFF0A84FF)
private val SplashAccent     = Color(0xFF0066FF)
private val SplashTextMain   = Color(0xFFFFFFFF)
private val SplashTextSub    = Color(0xFF8E8E93)

@Composable
fun SplashScreen(
    onAnimationComplete: () -> Unit,
    animationDurationMs: Int = 1800,
) {
    // ── Entrance animatables ──────────────────────────────────
    val logoAlpha  = remember { Animatable(0f) }
    val logoScale  = remember { Animatable(0.8f) }
    val tagAlpha   = remember { Animatable(0f) }
    val exitAlpha  = remember { Animatable(1f) }

    // ── Infinite pulse for the glow dot ──────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue  = 1.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue  = 0.9f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    // Rotating arc accent
    val arcRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
        ),
        label = "arcRotation",
    )

    // ── Sequence ─────────────────────────────────────────────
    LaunchedEffect(Unit) {
        // Logo fades in + scales up
        launch {
            logoAlpha.animateTo(
                targetValue   = 1f,
                animationSpec = tween(500, easing = FastOutSlowInEasing),
            )
        }
        launch {
            logoScale.animateTo(
                targetValue   = 1f,
                animationSpec = tween(500, easing = FastOutSlowInEasing),
            )
        }
        // Tagline follows 200ms later
        delay(200)
        tagAlpha.animateTo(
            targetValue   = 1f,
            animationSpec = tween(400, easing = FastOutSlowInEasing),
        )
        // Hold for remainder of total duration, then fade out
        val elapsed = 700L
        val remaining = (animationDurationMs - elapsed).coerceAtLeast(300L)
        delay(remaining)
        exitAlpha.animateTo(
            targetValue   = 0f,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
        )
        onAnimationComplete()
    }

    // ── UI ───────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(exitAlpha.value)
            .background(SplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        // Radial background glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRadialGlow(
                center = center,
                glowRadius = size.minDimension * 0.6f,
                color  = SplashAccent,
                alpha  = 0.07f,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Animated glow dot + arc
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(72.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(pulseScale),
                ) {
                    drawGlowDot(
                        center    = center,
                        coreColor = SplashPrimary,
                        glowColor = SplashAccent,
                        alpha     = pulseAlpha,
                    )
                }
                // Rotating arc ring
                Canvas(
                    modifier = Modifier
                        .size(72.dp),
                ) {
                    drawArcRing(
                        rotation = arcRotation,
                        color    = SplashPrimary,
                        alpha    = 0.35f,
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // "Renjana" headline
            Text(
                text     = "Renjana",
                modifier = Modifier
                    .alpha(logoAlpha.value)
                    .scale(logoScale.value),
                style = TextStyle(
                    fontFamily    = HeadlineFont,
                    fontWeight    = FontWeight.Bold,
                    fontSize      = 48.sp,
                    letterSpacing = (-1.5).sp,
                    color         = SplashTextMain,
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tagline
            Text(
                text     = "Container Engine",
                modifier = Modifier.alpha(tagAlpha.value),
                style = TextStyle(
                    fontFamily    = BodyFont,
                    fontWeight    = FontWeight.Normal,
                    fontSize      = 13.sp,
                    letterSpacing = 3.sp,
                    color         = SplashTextSub,
                ),
            )
        }
    }
}

// ── Canvas helpers ────────────────────────────────────────────

private fun DrawScope.drawRadialGlow(
    center: Offset,
    glowRadius: Float,
    color: Color,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = center,
    )
}

private fun DrawScope.drawGlowDot(
    center: Offset,
    coreColor: Color,
    glowColor: Color,
    alpha: Float,
) {
    // Outer glow layers
    for (i in 3 downTo 1) {
        val glowRadius = 18f + i * 10f
        drawCircle(
            color  = glowColor.copy(alpha = alpha * 0.15f / i),
            radius = glowRadius,
            center = center,
        )
    }
    // Core dot
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, coreColor),
            center = center,
            radius = 10f,
        ),
        radius = 10f,
        center = center,
    )
}

private fun DrawScope.drawArcRing(
    rotation: Float,
    color: Color,
    alpha: Float,
) {
    val strokePx = 1.5.dp.toPx()
    val radius   = size.minDimension / 2f - strokePx

    // Dashed-effect: 3 arcs of 80° each, separated by 40° gaps
    val arcLength = 80f
    val startAngles = listOf(rotation, rotation + 120f, rotation + 240f)

    for (startAngle in startAngles) {
        drawArc(
            color      = color.copy(alpha = alpha),
            startAngle = startAngle,
            sweepAngle = arcLength,
            useCenter  = false,
            style      = Stroke(width = strokePx),
            topLeft    = Offset(strokePx, strokePx),
            size       = androidx.compose.ui.geometry.Size(
                width  = size.width  - strokePx * 2,
                height = size.height - strokePx * 2,
            ),
        )
    }
}
