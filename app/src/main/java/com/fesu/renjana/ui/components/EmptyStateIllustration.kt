package com.fesu.renjana.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Line-art style illustration for empty states.
 */
@Composable
fun EmptyStateIllustration(
    modifier: Modifier = Modifier,
    color: Color = Color.Gray
) {
    Canvas(modifier = modifier.size(96.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round)

        // Outer container rounded rect (draw with drawRoundRect)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.25f, h * 0.1f),
            size = androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.8f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f, w * 0.08f),
            style = stroke
        )

        // Inner app window
        drawRect(
            color = color,
            topLeft = Offset(w * 0.33f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w * 0.34f, h * 0.3f),
            style = stroke
        )

        // Dots row
        for (i in 0..2) {
            val x = w * (0.38f + i * 0.1f)
            drawCircle(
                color = color,
                radius = 3f,
                center = Offset(x, h * 0.68f)
            )
        }

        // Plus circle (bottom right)
        drawCircle(
            color = color,
            radius = w * 0.1f,
            center = Offset(w * 0.78f, h * 0.78f),
            style = stroke
        )
        drawLine(
            color = color,
            start = Offset(w * 0.73f, h * 0.78f),
            end = Offset(w * 0.83f, h * 0.78f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.78f, h * 0.73f),
            end = Offset(w * 0.78f, h * 0.83f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
    }
}
