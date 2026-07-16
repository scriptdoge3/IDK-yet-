package com.powerforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Which physical quantity the whole engine drawing is color-coded by right now. */
enum class EngineVisualMode {
    NORMAL,
    THERMAL,
    STRESS,
}

/**
 * The whole drivetrain in one connected drawing - boiler, cylinder/piston/crank, flywheel,
 * and generator, all on the one real shaft, driven by the same tracked [crankAngleRadProvider]
 * the physics itself advances. [mode] doesn't add any new physics, it just chooses which real
 * quantity already computed by the simulation each part's fill color reports: pressure/normal
 * appearance, real temperatures (boiler water, cylinder steam, generator windings), or real
 * structural load fractions (flywheel rim tension vs. its real breaking tension, boiler
 * pressure vs. its real rupture pressure, winding temperature vs. its real rated maximum).
 * A part with no real modeled failure/thermal quantity (the piston/rod in stress mode, the
 * flywheel in thermal mode - cast iron's temperature isn't tracked, there's no real physics
 * backing a number there) is drawn neutral rather than inventing one.
 */
@Composable
fun EngineVisual(
    mode: EngineVisualMode,
    rpmProvider: () -> Double,
    crankAngleRadProvider: () -> Double,
    cylinderPressurePa: Double,
    cylinderTemperatureK: Double,
    boilerTemperatureK: Double,
    boilerWaterLevelFraction: Double,
    boilerStressFraction: Double,
    flywheelStressFraction: Double,
    windingStressFraction: Double,
    rotorWindingTemperatureK: Double,
    flameActive: Boolean,
    generatorEngaged: Boolean,
    electricalPowerW: Double,
    isFailing: Boolean,
    modifier: Modifier = Modifier,
) {
    var displayAngleRad by remember { mutableFloatStateOf(0f) }
    var flamePhase by remember { mutableFloatStateOf(0f) }
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
            flamePhase += dtSeconds.toFloat() * 5f
        }
    }

    val neutralMetal = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val strongMetal = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val spokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val stressBase = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val housingColor = Color(0xFF15181C)
    val waterColor = Color(0xFF3E7CB1)
    val flameColor = Color(0xFFFFA23C)

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val midY = size.height * 0.56f

        // --- Zone layout: boiler | cylinder+piston | flywheel | generator, left to right ---
        val boilerLeft = size.width * 0.02f
        val boilerRight = size.width * 0.27f
        val cylinderLeft = size.width * 0.31f
        val cylinderRight = size.width * 0.62f
        val flywheelCenter = Offset(size.width * 0.75f, midY)
        val flywheelRadius = size.height * 0.30f
        val crankPinRadius = flywheelRadius * 0.55f
        val generatorCenter = Offset(size.width * 0.93f, midY)
        val generatorRadius = size.height * 0.20f

        // === Boiler ===
        val boilerTop = midY - size.height * 0.26f
        val boilerHeight = size.height * 0.40f
        val boilerShellColor = when (mode) {
            EngineVisualMode.NORMAL -> neutralMetal
            EngineVisualMode.THERMAL -> thermalColor(boilerTemperatureK)
            EngineVisualMode.STRESS -> lerp(stressBase, errorColor, boilerStressFraction.coerceIn(0.0, 1.0).toFloat())
        }
        drawRoundRect(
            color = housingColor,
            topLeft = Offset(boilerLeft, boilerTop),
            size = Size(boilerRight - boilerLeft, boilerHeight),
            cornerRadius = CornerRadius(boilerHeight * 0.22f),
        )
        // Water fill, from the bottom, real level fraction
        val waterFillHeight = (boilerHeight - 6f) * boilerWaterLevelFraction.toFloat()
        drawRoundRect(
            color = waterColor,
            topLeft = Offset(boilerLeft + 3f, boilerTop + boilerHeight - 3f - waterFillHeight),
            size = Size(boilerRight - boilerLeft - 6f, waterFillHeight),
            cornerRadius = CornerRadius(boilerHeight * 0.16f),
        )
        drawRoundRect(
            color = boilerShellColor,
            topLeft = Offset(boilerLeft, boilerTop),
            size = Size(boilerRight - boilerLeft, boilerHeight),
            cornerRadius = CornerRadius(boilerHeight * 0.22f),
            style = Stroke(width = 4f),
        )
        // Firebox flame, a real status cue (lit or not), gently flickering while lit
        val fireboxCenterX = (boilerLeft + boilerRight) / 2f
        val fireboxY = boilerTop + boilerHeight + size.height * 0.03f
        if (flameActive) {
            val flicker = 0.75f + 0.25f * kotlin.math.sin(flamePhase * 3f)
            val flameHeight = size.height * 0.09f * flicker
            drawCircle(
                color = flameColor.copy(alpha = 0.35f * flicker),
                radius = flameHeight * 1.4f,
                center = Offset(fireboxCenterX, fireboxY),
            )
            drawCircle(
                color = flameColor,
                radius = flameHeight * 0.7f,
                center = Offset(fireboxCenterX, fireboxY),
            )
        } else {
            drawCircle(color = housingColor, radius = size.height * 0.05f, center = Offset(fireboxCenterX, fireboxY))
        }
        // Pipe from boiler to cylinder
        drawLine(
            color = strongMetal,
            start = Offset(boilerRight, boilerTop + boilerHeight * 0.22f),
            end = Offset(cylinderLeft, boilerTop + boilerHeight * 0.22f),
            strokeWidth = 5f,
        )
        drawLine(
            color = strongMetal,
            start = Offset(cylinderLeft, boilerTop + boilerHeight * 0.22f),
            end = Offset(cylinderLeft, midY - size.height * 0.14f),
            strokeWidth = 5f,
        )

        // === Cylinder + piston + connecting rod ===
        val cylinderTop = midY - size.height * 0.14f
        val cylinderHeight = size.height * 0.28f
        val strokeVisualPx = cylinderRight - cylinderLeft - size.height * 0.18f

        val theta = displayAngleRad.toDouble()
        val crankRadiusRatio = 0.06 // r/L ratio for the visual slider-crank approximation
        val displacementFraction =
            0.5 * (1 - cos(theta)) + (crankRadiusRatio / 4.0) * (1 - cos(2 * theta))
        val pistonCenterX = (cylinderLeft + size.height * 0.09f + displacementFraction * strokeVisualPx).toFloat()

        val cylinderFillColor = when (mode) {
            EngineVisualMode.NORMAL -> pressureToColor(cylinderPressurePa)
            EngineVisualMode.THERMAL -> thermalColor(cylinderTemperatureK)
            // No real strength-to-failure model exists for the piston/rod - shown neutral
            // rather than fabricating a stress number nothing in the physics backs.
            EngineVisualMode.STRESS -> stressBase
        }

        drawRoundRect(
            color = housingColor,
            topLeft = Offset(cylinderLeft, cylinderTop),
            size = Size(cylinderRight - cylinderLeft, cylinderHeight),
            cornerRadius = CornerRadius(cylinderHeight * 0.25f),
        )
        drawRoundRect(
            color = cylinderFillColor,
            topLeft = Offset(pistonCenterX, cylinderTop + 4f),
            size = Size((cylinderRight - pistonCenterX).coerceAtLeast(0f), cylinderHeight - 8f),
            cornerRadius = CornerRadius(cylinderHeight * 0.2f),
        )
        drawRoundRect(
            color = neutralMetal,
            topLeft = Offset(cylinderLeft, cylinderTop),
            size = Size(cylinderRight - cylinderLeft, cylinderHeight),
            cornerRadius = CornerRadius(cylinderHeight * 0.25f),
            style = Stroke(width = 3f),
        )

        val pistonWidth = size.height * 0.06f
        drawRect(
            color = strongMetal,
            topLeft = Offset(pistonCenterX - pistonWidth / 2f, cylinderTop - 3f),
            size = Size(pistonWidth, cylinderHeight + 6f),
        )

        val crankPin = Offset(
            flywheelCenter.x + (crankPinRadius * cos(theta)).toFloat(),
            flywheelCenter.y + (crankPinRadius * sin(theta)).toFloat(),
        )
        drawLine(
            color = strongMetal,
            start = Offset(pistonCenterX, flywheelCenter.y),
            end = crankPin,
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )

        // === Flywheel ===
        val flywheelRimColor = when (mode) {
            EngineVisualMode.NORMAL -> if (isFailing) errorColor else primaryColor
            // Cast iron's temperature isn't a real tracked quantity in this simulation -
            // shown neutral rather than inventing one.
            EngineVisualMode.THERMAL -> neutralMetal
            EngineVisualMode.STRESS -> lerp(stressBase, errorColor, flywheelStressFraction.coerceIn(0.0, 1.0).toFloat())
        }
        drawCircle(color = flywheelRimColor, radius = flywheelRadius, center = flywheelCenter, style = Stroke(width = 11f))
        val spokeCount = 6
        repeat(spokeCount) { i ->
            val spokeAngle = (2 * PI / spokeCount) * i + theta
            val end = Offset(
                flywheelCenter.x + (flywheelRadius * 0.9f * cos(spokeAngle)).toFloat(),
                flywheelCenter.y + (flywheelRadius * 0.9f * sin(spokeAngle)).toFloat(),
            )
            drawLine(color = spokeColor, start = flywheelCenter, end = end, strokeWidth = 5f)
        }
        drawCircle(color = housingColor, radius = flywheelRadius * 0.18f, center = flywheelCenter)
        drawCircle(color = strongMetal, radius = 6f, center = crankPin)

        // Shaft coupling flywheel to generator
        drawLine(
            color = strongMetal,
            start = Offset(flywheelCenter.x + flywheelRadius, flywheelCenter.y),
            end = Offset(generatorCenter.x - generatorRadius, generatorCenter.y),
            strokeWidth = 6f,
        )

        // === Generator ===
        val generatorColor = when (mode) {
            EngineVisualMode.NORMAL -> neutralMetal
            EngineVisualMode.THERMAL -> thermalColor(rotorWindingTemperatureK)
            EngineVisualMode.STRESS -> lerp(stressBase, errorColor, windingStressFraction.coerceIn(0.0, 1.0).toFloat())
        }
        drawCircle(color = housingColor, radius = generatorRadius, center = generatorCenter)
        drawCircle(color = generatorColor, radius = generatorRadius, center = generatorCenter, style = Stroke(width = 10f))
        // Winding coil arcs on the drum face
        repeat(4) { i ->
            val angle = (2 * PI / 4) * i + theta * 2.0
            val a = Offset(
                generatorCenter.x + (generatorRadius * 0.55f * cos(angle)).toFloat(),
                generatorCenter.y + (generatorRadius * 0.55f * sin(angle)).toFloat(),
            )
            drawCircle(color = spokeColor, radius = generatorRadius * 0.12f, center = a, style = Stroke(width = 3f))
        }
        // Brush spark: lit only while current is actually flowing to a real load
        val currentFlowing = generatorEngaged && electricalPowerW > 1e-6
        drawCircle(
            color = if (currentFlowing) Color(0xFFFFE9A6) else housingColor,
            radius = generatorRadius * 0.22f,
            center = generatorCenter,
        )
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

/**
 * A single shared absolute-temperature scale used everywhere temperature is shown, so a
 * color means the same real temperature on the boiler, the cylinder, and the generator
 * windings alike: cool blue-gray near ambient, through orange, up to near-white at a
 * real winding insulation class's typical rated ceiling.
 */
private fun thermalColor(tempK: Double, coldK: Double = 293.15, hotK: Double = 480.0): Color {
    val t = ((tempK - coldK) / (hotK - coldK)).coerceIn(0.0, 1.0).toFloat()
    return if (t < 0.5f) {
        lerp(Color(0xFF3A4A65), Color(0xFFF0703C), t / 0.5f)
    } else {
        lerp(Color(0xFFF0703C), Color(0xFFFFE9C7), (t - 0.5f) / 0.5f)
    }
}

/** Segmented view-mode picker for [EngineVisual] - which real quantity the drawing colors report. */
@Composable
fun EngineVisualModeSelector(
    mode: EngineVisualMode,
    onModeChange: (EngineVisualMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EngineVisualMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onModeChange(candidate) },
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    candidate.displayLabel(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

private fun EngineVisualMode.displayLabel(): String = when (this) {
    EngineVisualMode.NORMAL -> "Normal"
    EngineVisualMode.THERMAL -> "Thermal"
    EngineVisualMode.STRESS -> "Stress"
}
