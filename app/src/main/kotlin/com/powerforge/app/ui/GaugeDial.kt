package com.powerforge.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val SWEEP_START_DEGREES = 135f
private const val SWEEP_DEGREES = 270f

/**
 * A real analog instrument dial: a needle sweeps 270 degrees from [minValue] to [maxValue],
 * with an optional red danger band past [dangerValue]. The needle is critically damped
 * (no overshoot) rather than snapping straight to the value, the way a real gauge's
 * mechanism has inertia - manufacturers deliberately damp real gauges this way so a
 * noisy real reading doesn't make the needle flutter or hunt back and forth. The printed
 * number underneath follows that same damped needle position, not the raw instantaneous
 * value - a real gauge only has one reading, not a jittery digit next to a smooth needle.
 */
@Composable
fun GaugeDial(
    label: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    unit: String,
    modifier: Modifier = Modifier,
    dangerValue: Float? = null,
    diameter: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val animatedValue by animateFloatAsState(
        targetValue = value.coerceIn(minValue, maxValue),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow),
        label = "gaugeNeedle",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
            val faceColor = MaterialTheme.colorScheme.surface
            val rimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            val needleColor = MaterialTheme.colorScheme.primary
            val dangerColor = MaterialTheme.colorScheme.error

            Canvas(modifier = Modifier.size(diameter)) {
                val radius = size.minDimension / 2f * 0.92f
                val center = Offset(size.width / 2f, size.height / 2f)

                drawCircle(color = faceColor, radius = radius, center = center)
                drawCircle(color = rimColor, radius = radius, center = center, style = Stroke(width = 4f))

                if (dangerValue != null && dangerValue in minValue..maxValue) {
                    val dangerFraction = (dangerValue - minValue) / (maxValue - minValue)
                    drawArc(
                        color = dangerColor.copy(alpha = 0.35f),
                        startAngle = SWEEP_START_DEGREES + SWEEP_DEGREES * dangerFraction,
                        sweepAngle = SWEEP_DEGREES * (1f - dangerFraction),
                        useCenter = false,
                        topLeft = Offset(center.x - radius * 0.82f, center.y - radius * 0.82f),
                        size = androidx.compose.ui.geometry.Size(radius * 1.64f, radius * 1.64f),
                        style = Stroke(width = radius * 0.14f),
                    )
                }

                val majorTicks = 6
                for (i in 0..majorTicks) {
                    val fraction = i / majorTicks.toFloat()
                    val angleDeg = SWEEP_START_DEGREES + SWEEP_DEGREES * fraction
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val outer = Offset(
                        center.x + (radius * 0.88f * cos(angleRad)).toFloat(),
                        center.y + (radius * 0.88f * sin(angleRad)).toFloat(),
                    )
                    val inner = Offset(
                        center.x + (radius * 0.72f * cos(angleRad)).toFloat(),
                        center.y + (radius * 0.72f * sin(angleRad)).toFloat(),
                    )
                    drawLine(color = tickColor, start = inner, end = outer, strokeWidth = 3f)
                }

                val needleFraction = (animatedValue - minValue) / (maxValue - minValue)
                val needleAngleDeg = SWEEP_START_DEGREES + SWEEP_DEGREES * needleFraction.coerceIn(0f, 1f)
                val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())
                val isDanger = dangerValue != null && animatedValue >= dangerValue
                val tip = Offset(
                    center.x + (radius * 0.78f * cos(needleAngleRad)).toFloat(),
                    center.y + (radius * 0.78f * sin(needleAngleRad)).toFloat(),
                )
                val tail = Offset(
                    center.x - (radius * 0.18f * cos(needleAngleRad)).toFloat(),
                    center.y - (radius * 0.18f * sin(needleAngleRad)).toFloat(),
                )
                drawLine(
                    color = if (isDanger) dangerColor else needleColor,
                    start = tail,
                    end = tip,
                    strokeWidth = 5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawCircle(color = if (isDanger) dangerColor else needleColor, radius = radius * 0.08f, center = center)
            }

            Text(
                text = "${formatGaugeValue(animatedValue)}$unit",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

private fun formatGaugeValue(value: Float): String =
    if (value >= 100f) value.toInt().toString() else "%.1f".format(value)
