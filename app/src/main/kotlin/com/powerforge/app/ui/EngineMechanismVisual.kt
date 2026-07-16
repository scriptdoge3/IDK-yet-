package com.powerforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The actual mechanism, not a decorative spinner: a piston sliding in a cylinder,
 * a connecting rod to a crank pin, and the flywheel it's all bolted to - all driven
 * by the real tracked [crankAngleRadProvider], not an independent visual-only spin.
 * The cylinder's fill color tracks [cylinderPressureProvider] directly, so you can
 * watch it glow hot on the power stroke and go dull on an over-expanded one.
 */
@Composable
fun EngineMechanismVisual(
    rpmProvider: () -> Double,
    crankAngleRadProvider: () -> Double,
    cylinderPressurePaProvider: () -> Double,
    isFailing: Boolean,
    modifier: Modifier = Modifier,
) {
    var displayAngleRad by remember { mutableFloatStateOf(0f) }
    val latestRpm = rememberUpdatedState(rpmProvider)
    val latestTrueAngle = rememberUpdatedState(crankAngleRadProvider)

    LaunchedEffect(Unit) {
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dtSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000.0
            lastFrameNanos = frameNanos

            val angularSpeed = (latestRpm.value() / 60.0) * 2.0 * PI
            var next = (displayAngleRad + angularSpeed * dtSeconds).toFloat()

            // Gently pull the smooth visual angle back toward the real simulated one
            // (shortest angular path) so it never drifts, without visibly snapping.
            val trueAngle = latestTrueAngle.value().toFloat()
            var diff = (trueAngle - next) % (2f * PI.toFloat())
            if (diff > PI) diff -= 2f * PI.toFloat()
            if (diff < -PI) diff += 2f * PI.toFloat()
            next += diff * 0.06f

            displayAngleRad = next % (2f * PI.toFloat())
            if (displayAngleRad < 0f) displayAngleRad += 2f * PI.toFloat()
        }
    }

    val rimColor = if (isFailing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val spokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val cylinderWallColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val pistonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    Canvas(modifier = modifier.fillMaxWidth().height(170.dp)) {
        val flywheelRadius = size.height * 0.42f
        val flywheelCenter = Offset(size.width * 0.82f, size.height * 0.52f)
        val crankPinRadius = flywheelRadius * 0.55f

        val cylinderRight = flywheelCenter.x - flywheelRadius - crankPinRadius - size.width * 0.06f
        val cylinderLeft = size.width * 0.06f
        val strokeVisualPx = cylinderRight - cylinderLeft - size.height * 0.18f
        val cylinderTop = flywheelCenter.y - size.height * 0.14f
        val cylinderHeight = size.height * 0.28f

        val theta = displayAngleRad.toDouble()
        val crankRadiusRatio = 0.06 // r/L ratio for the visual slider-crank approximation
        val displacementFraction =
            0.5 * (1 - cos(theta)) + (crankRadiusRatio / 4.0) * (1 - cos(2 * theta))
        val pistonCenterX = (cylinderLeft + size.height * 0.09f + displacementFraction * strokeVisualPx).toFloat()

        val cylinderPressurePa = cylinderPressurePaProvider()
        val cylinderFillColor = pressureToColor(cylinderPressurePa)

        // Cylinder body
        drawRoundRect(
            color = Color(0xFF15181C),
            topLeft = Offset(cylinderLeft, cylinderTop),
            size = androidx.compose.ui.geometry.Size(cylinderRight - cylinderLeft, cylinderHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cylinderHeight * 0.25f),
        )
        // Steam fill behind the piston (toward the flywheel side, the working face)
        drawRoundRect(
            color = cylinderFillColor,
            topLeft = Offset(pistonCenterX, cylinderTop + 4f),
            size = androidx.compose.ui.geometry.Size((cylinderRight - pistonCenterX).coerceAtLeast(0f), cylinderHeight - 8f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cylinderHeight * 0.2f),
        )
        drawRoundRect(
            color = cylinderWallColor,
            topLeft = Offset(cylinderLeft, cylinderTop),
            size = androidx.compose.ui.geometry.Size(cylinderRight - cylinderLeft, cylinderHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cylinderHeight * 0.25f),
            style = Stroke(width = 3f),
        )

        // Piston
        val pistonWidth = size.height * 0.06f
        drawRect(
            color = pistonColor,
            topLeft = Offset(pistonCenterX - pistonWidth / 2f, cylinderTop - 3f),
            size = androidx.compose.ui.geometry.Size(pistonWidth, cylinderHeight + 6f),
        )

        // Crank pin position on the flywheel
        val crankPin = Offset(
            flywheelCenter.x + (crankPinRadius * cos(theta)).toFloat(),
            flywheelCenter.y + (crankPinRadius * sin(theta)).toFloat(),
        )

        // Connecting rod
        drawLine(
            color = pistonColor,
            start = Offset(pistonCenterX, flywheelCenter.y),
            end = crankPin,
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )

        // Flywheel
        drawCircle(color = rimColor, radius = flywheelRadius, center = flywheelCenter, style = Stroke(width = 12f))
        val spokeCount = 6
        repeat(spokeCount) { i ->
            val spokeAngle = (2 * PI / spokeCount) * i + theta
            val end = Offset(
                flywheelCenter.x + (flywheelRadius * 0.9f * cos(spokeAngle)).toFloat(),
                flywheelCenter.y + (flywheelRadius * 0.9f * sin(spokeAngle)).toFloat(),
            )
            drawLine(color = spokeColor, start = flywheelCenter, end = end, strokeWidth = 6f)
        }
        drawCircle(color = Color(0xFF15181C), radius = flywheelRadius * 0.18f, center = flywheelCenter)
        drawCircle(color = pistonColor, radius = 6f, center = crankPin)
    }
}

/** Cool blue-gray near vacuum, neutral at atmospheric, hot orange-white under real boiler pressure. */
private fun pressureToColor(pressurePa: Double): Color {
    val atmospheric = 101_325.0
    val hot = 400_000.0
    return when {
        pressurePa < atmospheric -> {
            val t = (pressurePa / atmospheric).coerceIn(0.0, 1.0).toFloat()
            lerp(Color(0xFF2A3A55), Color(0xFF5A6577), t)
        }
        else -> {
            val t = ((pressurePa - atmospheric) / (hot - atmospheric)).coerceIn(0.0, 1.0).toFloat()
            lerp(Color(0xFF5A6577), Color(0xFFF0703C), t)
        }
    }
}
