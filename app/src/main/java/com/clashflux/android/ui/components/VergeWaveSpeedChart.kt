package com.clashflux.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.TrafficDownload
import com.clashflux.android.theme.TrafficUpload

@Composable
fun VergeWaveSpeedChart(
    history: List<TrafficStats>,
    modifier: Modifier = Modifier,
    height: Dp = 116.dp
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
    val seriesPath = remember { Path() }
    val seriesFillPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val width = size.width
        val h = size.height
        val usableHeight = h * 0.82f
        val bottomY = h - 4.dp.toPx()

        // Draw subtle dashed horizontal grid lines
        for (i in 0..3) {
            val y = bottomY - usableHeight * (i / 3f)
            drawLine(
                color = if (i == 0) gridColor.copy(alpha = 0.4f) else gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (i == 0) null else dashEffect
            )
        }

        if (history.size < 2) return@Canvas

        // Find max speed for scaling (minimum 32 KB/s scale so small fluctuations look natural)
        val maxSpeed = (history.maxOfOrNull { maxOf(it.down, it.up) } ?: 1024L)
            .coerceAtLeast(1024L * 32)
            .toFloat()

        val stepX = width / (history.size - 1)

        fun drawSpeedSeries(
            values: List<Long>,
            lineColor: Color,
            fillBrush: Brush
        ) {
            seriesPath.reset()
            seriesFillPath.reset()

            fun getY(value: Long): Float {
                val ratio = (value.toFloat() / maxSpeed).coerceIn(0f, 1f)
                return bottomY - ratio * usableHeight
            }

            val startY = getY(values[0])
            seriesPath.moveTo(0f, startY)
            seriesFillPath.moveTo(0f, bottomY)
            seriesFillPath.lineTo(0f, startY)

            for (i in 0 until values.size - 1) {
                val x1 = i * stepX
                val y1 = getY(values[i])
                val x2 = (i + 1) * stepX
                val y2 = getY(values[i + 1])

                val cx = (x1 + x2) / 2f
                seriesPath.cubicTo(cx, y1, cx, y2, x2, y2)
                seriesFillPath.cubicTo(cx, y1, cx, y2, x2, y2)
            }

            seriesFillPath.lineTo(width, bottomY)
            seriesFillPath.close()

            // Area gradient fill
            drawPath(path = seriesFillPath, brush = fillBrush)

            // Smooth curve stroke
            drawPath(
                path = seriesPath,
                color = lineColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Live endpoint indicator dot on the right edge
            val lastY = getY(values.last())
            val endCenter = Offset(width - 3.dp.toPx(), lastY)
            drawCircle(
                color = lineColor.copy(alpha = 0.25f),
                radius = 6.dp.toPx(),
                center = endCenter
            )
            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = endCenter
            )
        }

        // 1. Upload series (Amber/Orange) drawn first in back
        drawSpeedSeries(
            values = history.map { it.up },
            lineColor = TrafficUpload,
            fillBrush = Brush.verticalGradient(
                colors = listOf(
                    TrafficUpload.copy(alpha = 0.22f),
                    TrafficUpload.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = bottomY
            )
        )

        // 2. Download series (Emerald Green) drawn in front
        drawSpeedSeries(
            values = history.map { it.down },
            lineColor = TrafficDownload,
            fillBrush = Brush.verticalGradient(
                colors = listOf(
                    TrafficDownload.copy(alpha = 0.30f),
                    TrafficDownload.copy(alpha = 0.06f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = bottomY
            )
        )
    }
}
