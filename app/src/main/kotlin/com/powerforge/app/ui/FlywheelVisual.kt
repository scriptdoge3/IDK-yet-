package com.powerforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A spinning flywheel whose rotation speed tracks live RPM. The animation loop
 * reads [rpmProvider] every frame so speed changes don't need to restart the effect.
 */
@Composable
fun FlywheelVisual(
    rpmProvider: () -> Double,
    isFailing: Boolean,
    modifier: Modifier = Modifier,
) {
    var angleDegrees by remember { mutableFloatStateOf(0f) }
    val latestRpm = rememberUpdatedState(rpmProvider)

    LaunchedEffect(Unit) {
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dtSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000.0
            lastFrameNanos = frameNanos
            val degreesPerSecond = (latestRpm.value() / 60.0) * 360.0
            angleDegrees = ((angleDegrees + degreesPerSecond * dtSeconds) % 360.0).toFloat()
        }
    }

    val rimColor = if (isFailing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val spokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Canvas(modifier = modifier.size(160.dp).rotate(angleDegrees)) {
        val radius = size.minDimension / 2f * 0.85f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = rimColor, radius = radius, center = center, style = Stroke(width = 14f))

        val spokeCount = 6
        repeat(spokeCount) { i ->
            val theta = (2 * Math.PI / spokeCount) * i
            val end = Offset(
                x = center.x + (radius * 0.92f * cos(theta)).toFloat(),
                y = center.y + (radius * 0.92f * sin(theta)).toFloat(),
            )
            drawLine(color = spokeColor, start = center, end = end, strokeWidth = 8f)
        }

        drawCircle(color = Color(0xFF15181C), radius = radius * 0.16f, center = center)
    }
}
