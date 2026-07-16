package com.powerforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SWEEP_START_DEGREES = 135.0
private const val SWEEP_DEGREES = 270.0

private fun angleFromCenterDeg(center: Offset, point: Offset): Double =
    Math.toDegrees(atan2((point.y - center.y).toDouble(), (point.x - center.x).toDouble()))

private fun angleToFraction(angleDeg: Double): Double {
    val normalized = if (angleDeg < SWEEP_START_DEGREES) angleDeg + 360.0 else angleDeg
    return ((normalized - SWEEP_START_DEGREES) / SWEEP_DEGREES).coerceIn(0.0, 1.0)
}

/**
 * A valve wheel you turn, not a slider you drag sideways - same 270-degree sweep
 * language as [GaugeDial] so setting a control and reading its gauge feel like the
 * same physical vocabulary. Tapping anywhere on the dial jumps straight to that
 * position (like grabbing a real valve wheel spoke), and dragging turns it by the
 * real angle your finger has swept around the center since the last frame - not by
 * jumping to your finger's absolute angle, which makes a real dial feel wildly
 * oversensitive near its own center (a tiny position wobble there swings across a
 * huge angle) and doesn't behave like actually turning something.
 */
@Composable
fun RotaryValveControl(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Double> = 0.0..1.0,
    diameter: Dp = 64.dp,
    valueText: String = "${(((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)) * 100).roundToInt()}%",
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val knobColor = MaterialTheme.colorScheme.secondary
        val faceColor = MaterialTheme.colorScheme.surface
        val rimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        val rangeSpan = valueRange.endInclusive - valueRange.start

        Canvas(
            modifier = modifier
                .size(diameter)
                .pointerInput(valueRange) {
                    detectTapGestures { tapPosition ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val fraction = angleToFraction(angleFromCenterDeg(center, tapPosition))
                        onValueChange(valueRange.start + fraction * rangeSpan)
                    }
                }
                .pointerInput(valueRange) {
                    var lastAngleDeg: Double? = null
                    detectDragGestures(
                        onDragStart = { lastAngleDeg = null },
                        onDragEnd = { lastAngleDeg = null },
                        onDragCancel = { lastAngleDeg = null },
                    ) { change, _ ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val currentAngleDeg = angleFromCenterDeg(center, change.position)
                        val previous = lastAngleDeg
                        if (previous != null) {
                            // Shortest angular step since the last frame, so crossing the
                            // dial's +-180 degree seam doesn't register as a huge jump.
                            var deltaDeg = currentAngleDeg - previous
                            if (deltaDeg > 180.0) deltaDeg -= 360.0
                            if (deltaDeg < -180.0) deltaDeg += 360.0
                            val deltaFraction = deltaDeg / SWEEP_DEGREES
                            val currentFraction = ((value - valueRange.start) / rangeSpan).coerceIn(0.0, 1.0)
                            val nextFraction = (currentFraction + deltaFraction).coerceIn(0.0, 1.0)
                            onValueChange(valueRange.start + nextFraction * rangeSpan)
                        }
                        lastAngleDeg = currentAngleDeg
                    }
                },
        ) {
            val radius = size.minDimension / 2f * 0.9f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawArc(
                color = trackColor,
                startAngle = SWEEP_START_DEGREES.toFloat(),
                sweepAngle = SWEEP_DEGREES.toFloat(),
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.86f),
                size = androidx.compose.ui.geometry.Size(radius * 1.72f, radius * 1.72f),
                style = Stroke(width = radius * 0.16f, cap = StrokeCap.Round),
            )

            val fraction = ((value - valueRange.start) / rangeSpan).coerceIn(0.0, 1.0)
            drawArc(
                color = knobColor,
                startAngle = SWEEP_START_DEGREES.toFloat(),
                sweepAngle = (SWEEP_DEGREES * fraction).toFloat(),
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.86f),
                size = androidx.compose.ui.geometry.Size(radius * 1.72f, radius * 1.72f),
                style = Stroke(width = radius * 0.16f, cap = StrokeCap.Round),
            )

            drawCircle(color = faceColor, radius = radius * 0.6f, center = center)
            drawCircle(color = rimColor, radius = radius * 0.6f, center = center, style = Stroke(width = 3f))

            val pointerAngleDeg = SWEEP_START_DEGREES + SWEEP_DEGREES * fraction
            val pointerAngleRad = Math.toRadians(pointerAngleDeg)
            val tip = Offset(
                center.x + (radius * 0.56f * cos(pointerAngleRad)).toFloat(),
                center.y + (radius * 0.56f * sin(pointerAngleRad)).toFloat(),
            )
            drawLine(color = knobColor, start = center, end = tip, strokeWidth = 5f, cap = StrokeCap.Round)
            drawCircle(color = knobColor, radius = radius * 0.08f, center = center)
        }

        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(valueText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
