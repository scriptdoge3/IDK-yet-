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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * The whole drivetrain in one connected drawing - boiler (with firebox, chimney, and
 * exhaust), cylinder/piston/crosshead/crank, flywheel, and generator, all mounted on one
 * real shaft and baseplate, driven by the same tracked [crankAngleRadProvider] the physics
 * itself advances. [mode] doesn't add any new physics, it just chooses which real quantity
 * already computed by the simulation each part's fill color reports: pressure/normal
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
    var animationPhase by remember { mutableFloatStateOf(0f) }
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
            animationPhase += dtSeconds.toFloat()
        }
    }

    val palette = EnginePalette(
        neutralMetal = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        strongMetal = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        spokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        primaryColor = MaterialTheme.colorScheme.primary,
        errorColor = MaterialTheme.colorScheme.error,
        stressBase = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        housingColor = Color(0xFF15181C),
        waterColor = Color(0xFF3E7CB1),
        flameColor = Color(0xFFFFA23C),
        smokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
    )

    Canvas(modifier = modifier.fillMaxWidth().height(230.dp)) {
        val theta = displayAngleRad.toDouble()
        val midY = size.height * 0.60f
        val baseY = size.height * 0.90f

        // --- Zone layout: boiler | cylinder+piston | flywheel | generator, left to right ---
        val boilerLeft = size.width * 0.02f
        val boilerRight = size.width * 0.27f
        val cylinderLeft = size.width * 0.31f
        val cylinderRight = size.width * 0.62f
        val flywheelCenter = Offset(size.width * 0.75f, midY)
        val flywheelRadius = size.height * 0.26f
        val crankPinRadius = flywheelRadius * 0.55f
        val generatorCenter = Offset(size.width * 0.93f, midY)
        val generatorRadius = size.height * 0.175f

        drawBaseplate(baseY, size.width * 0.01f, size.width * 0.99f, palette)

        val boilerTop = midY - size.height * 0.30f
        val boilerHeight = size.height * 0.38f
        drawBoiler(
            left = boilerLeft,
            right = boilerRight,
            top = boilerTop,
            heightPx = boilerHeight,
            baseY = baseY,
            waterLevelFraction = boilerWaterLevelFraction,
            flameActive = flameActive,
            animationPhase = animationPhase,
            shellColor = when (mode) {
                EngineVisualMode.NORMAL -> palette.neutralMetal
                EngineVisualMode.THERMAL -> thermalColor(boilerTemperatureK)
                EngineVisualMode.STRESS -> lerp(palette.stressBase, palette.errorColor, boilerStressFraction.coerceIn(0.0, 1.0).toFloat())
            },
            palette = palette,
        )

        val cylinderTop = midY - size.height * 0.14f
        val cylinderHeight = size.height * 0.28f
        drawSteamPipe(
            fromX = boilerRight,
            toX = cylinderLeft,
            atY = boilerTop + boilerHeight * 0.20f,
            downToY = cylinderTop,
            palette = palette,
        )

        val pistonCenterX = drawCylinderAndPiston(
            cylinderLeft = cylinderLeft,
            cylinderRight = cylinderRight,
            cylinderTop = cylinderTop,
            cylinderHeight = cylinderHeight,
            theta = theta,
            fillColor = when (mode) {
                EngineVisualMode.NORMAL -> pressureToColor(cylinderPressurePa)
                EngineVisualMode.THERMAL -> thermalColor(cylinderTemperatureK)
                // No real strength-to-failure model exists for the piston/rod - shown
                // neutral rather than fabricating a stress number nothing in the physics
                // backs.
                EngineVisualMode.STRESS -> palette.stressBase
            },
            palette = palette,
        )

        val crankPin = Offset(
            flywheelCenter.x + (crankPinRadius * cos(theta)).toFloat(),
            flywheelCenter.y + (crankPinRadius * sin(theta)).toFloat(),
        )
        drawCrossheadAndRod(
            pistonCenterX = pistonCenterX,
            railY = flywheelCenter.y,
            crankPin = crankPin,
            palette = palette,
        )

        drawFlywheel(
            center = flywheelCenter,
            radius = flywheelRadius,
            theta = theta,
            crankPin = crankPin,
            rimColor = when (mode) {
                EngineVisualMode.NORMAL -> if (isFailing) palette.errorColor else palette.primaryColor
                // Cast iron's temperature isn't a real tracked quantity in this
                // simulation - shown neutral rather than inventing one.
                EngineVisualMode.THERMAL -> palette.neutralMetal
                EngineVisualMode.STRESS -> lerp(palette.stressBase, palette.errorColor, flywheelStressFraction.coerceIn(0.0, 1.0).toFloat())
            },
            palette = palette,
        )

        drawLine(
            color = palette.strongMetal,
            start = Offset(flywheelCenter.x + flywheelRadius, flywheelCenter.y),
            end = Offset(generatorCenter.x - generatorRadius, generatorCenter.y),
            strokeWidth = 6f,
        )

        drawGenerator(
            center = generatorCenter,
            radius = generatorRadius,
            theta = theta,
            baseY = baseY,
            currentFlowing = generatorEngaged && electricalPowerW > 1e-6,
            drumColor = when (mode) {
                EngineVisualMode.NORMAL -> palette.neutralMetal
                EngineVisualMode.THERMAL -> thermalColor(rotorWindingTemperatureK)
                EngineVisualMode.STRESS -> lerp(palette.stressBase, palette.errorColor, windingStressFraction.coerceIn(0.0, 1.0).toFloat())
            },
            palette = palette,
        )
    }
}

private class EnginePalette(
    val neutralMetal: Color,
    val strongMetal: Color,
    val spokeColor: Color,
    val primaryColor: Color,
    val errorColor: Color,
    val stressBase: Color,
    val housingColor: Color,
    val waterColor: Color,
    val flameColor: Color,
    val smokeColor: Color,
)

private fun DrawScope.drawBaseplate(baseY: Float, left: Float, right: Float, palette: EnginePalette) {
    drawRoundRect(
        color = palette.housingColor,
        topLeft = Offset(left, baseY),
        size = Size(right - left, size.height * 0.05f),
        cornerRadius = CornerRadius(3f),
    )
    // Foundation bolts along the baseplate
    var x = left + 12f
    while (x < right - 12f) {
        drawCircle(color = palette.neutralMetal, radius = 2.5f, center = Offset(x, baseY + size.height * 0.025f))
        x += 26f
    }
}

private fun DrawScope.drawBoiler(
    left: Float,
    right: Float,
    top: Float,
    heightPx: Float,
    baseY: Float,
    waterLevelFraction: Double,
    flameActive: Boolean,
    animationPhase: Float,
    shellColor: Color,
    palette: EnginePalette,
) {
    // Support legs down to the baseplate
    drawRect(color = palette.strongMetal, topLeft = Offset(left + 4f, top + heightPx), size = Size(5f, baseY - (top + heightPx)))
    drawRect(color = palette.strongMetal, topLeft = Offset(right - 9f, top + heightPx), size = Size(5f, baseY - (top + heightPx)))

    drawRoundRect(
        color = palette.housingColor,
        topLeft = Offset(left, top),
        size = Size(right - left, heightPx),
        cornerRadius = CornerRadius(heightPx * 0.22f),
    )
    // Water fill, from the bottom, real level fraction
    val waterFillHeight = (heightPx - 6f) * waterLevelFraction.toFloat()
    drawRoundRect(
        color = palette.waterColor,
        topLeft = Offset(left + 3f, top + heightPx - 3f - waterFillHeight),
        size = Size(right - left - 6f, waterFillHeight),
        cornerRadius = CornerRadius(heightPx * 0.16f),
    )
    drawRoundRect(
        color = shellColor,
        topLeft = Offset(left, top),
        size = Size(right - left, heightPx),
        cornerRadius = CornerRadius(heightPx * 0.22f),
        style = Stroke(width = 4f),
    )
    // Rivet seams: two rows along the shell, a real riveted-boiler-plate detail
    val rivetRowYs = listOf(top + heightPx * 0.28f, top + heightPx * 0.72f)
    for (rowY in rivetRowYs) {
        var x = left + heightPx * 0.22f
        while (x < right - heightPx * 0.18f) {
            drawCircle(color = palette.housingColor.copy(alpha = 0.6f), radius = 2f, center = Offset(x, rowY))
            x += (right - left) * 0.14f
        }
    }
    // Dome on top with a relief-valve nub
    val domeCenterX = left + (right - left) * 0.32f
    drawCircle(color = palette.housingColor, radius = heightPx * 0.14f, center = Offset(domeCenterX, top))
    drawCircle(color = shellColor, radius = heightPx * 0.14f, center = Offset(domeCenterX, top), style = Stroke(width = 3f))
    drawRect(
        color = palette.strongMetal,
        topLeft = Offset(domeCenterX - 3f, top - heightPx * 0.14f - 8f),
        size = Size(6f, 8f),
    )

    // Chimney rising from the firebox end, with rising exhaust wisps while lit
    val chimneyX = left + heightPx * 0.16f
    val chimneyTop = top - heightPx * 0.30f
    drawRect(color = palette.housingColor, topLeft = Offset(chimneyX - 5f, chimneyTop), size = Size(10f, top - chimneyTop))
    drawRect(color = palette.neutralMetal, topLeft = Offset(chimneyX - 5f, chimneyTop), size = Size(10f, top - chimneyTop), style = Stroke(width = 2f))
    if (flameActive) {
        repeat(4) { i ->
            val loopLength = 46f
            val speed = 14f
            val phase = (animationPhase * speed + i * (loopLength / 4f)) % loopLength
            val puffAlpha = (1f - phase / loopLength).coerceIn(0f, 1f)
            val drift = sin((animationPhase + i) * 1.7f) * 5f
            drawCircle(
                color = palette.smokeColor.copy(alpha = palette.smokeColor.alpha * puffAlpha),
                radius = 4f + phase * 0.12f,
                center = Offset(chimneyX + drift, chimneyTop - phase),
            )
        }
    }

    // Firebox flame, a real status cue (lit or not), gently flickering while lit
    val fireboxCenterX = (left + right) / 2f
    val fireboxY = top + heightPx + size.height * 0.03f
    if (flameActive) {
        val flicker = 0.75f + 0.25f * sin(animationPhase * 15f)
        val flameHeight = size.height * 0.08f * flicker
        drawCircle(
            color = palette.flameColor.copy(alpha = 0.35f * flicker),
            radius = flameHeight * 1.4f,
            center = Offset(fireboxCenterX, fireboxY),
        )
        drawCircle(color = palette.flameColor, radius = flameHeight * 0.7f, center = Offset(fireboxCenterX, fireboxY))
    } else {
        drawCircle(color = palette.housingColor, radius = size.height * 0.045f, center = Offset(fireboxCenterX, fireboxY))
    }
}

private fun DrawScope.drawSteamPipe(fromX: Float, toX: Float, atY: Float, downToY: Float, palette: EnginePalette) {
    // Drawn as a double line (an outer housing line plus a thinner inner highlight) so
    // the pipe reads as a hollow tube rather than a flat wire.
    drawLine(color = palette.housingColor, start = Offset(fromX, atY), end = Offset(toX, atY), strokeWidth = 7f, cap = StrokeCap.Round)
    drawLine(color = palette.strongMetal, start = Offset(fromX, atY), end = Offset(toX, atY), strokeWidth = 4f, cap = StrokeCap.Round)
    drawLine(color = palette.housingColor, start = Offset(toX, atY), end = Offset(toX, downToY), strokeWidth = 7f, cap = StrokeCap.Round)
    drawLine(color = palette.strongMetal, start = Offset(toX, atY), end = Offset(toX, downToY), strokeWidth = 4f, cap = StrokeCap.Round)
    // Flange rings at both ends
    drawCircle(color = palette.strongMetal, radius = 5f, center = Offset(fromX, atY), style = Stroke(width = 2f))
    drawCircle(color = palette.strongMetal, radius = 5f, center = Offset(toX, atY), style = Stroke(width = 2f))
}

/** Returns the piston's current center X, so the crosshead/rod drawing can follow it. */
private fun DrawScope.drawCylinderAndPiston(
    cylinderLeft: Float,
    cylinderRight: Float,
    cylinderTop: Float,
    cylinderHeight: Float,
    theta: Double,
    fillColor: Color,
    palette: EnginePalette,
): Float {
    val strokeVisualPx = cylinderRight - cylinderLeft - size.height * 0.18f
    val crankRadiusRatio = 0.06 // r/L ratio for the visual slider-crank approximation
    val displacementFraction = 0.5 * (1 - cos(theta)) + (crankRadiusRatio / 4.0) * (1 - cos(2 * theta))
    val pistonCenterX = (cylinderLeft + size.height * 0.09f + displacementFraction * strokeVisualPx).toFloat()

    // Steam chest on top of the cylinder (the valve box real double-acting engines have)
    val chestHeight = cylinderHeight * 0.32f
    drawRoundRect(
        color = palette.housingColor,
        topLeft = Offset(cylinderLeft + cylinderHeight * 0.3f, cylinderTop - chestHeight),
        size = Size((cylinderRight - cylinderLeft) * 0.55f, chestHeight),
        cornerRadius = CornerRadius(4f),
    )
    drawRoundRect(
        color = palette.neutralMetal,
        topLeft = Offset(cylinderLeft + cylinderHeight * 0.3f, cylinderTop - chestHeight),
        size = Size((cylinderRight - cylinderLeft) * 0.55f, chestHeight),
        cornerRadius = CornerRadius(4f),
        style = Stroke(width = 2f),
    )

    drawRoundRect(
        color = palette.housingColor,
        topLeft = Offset(cylinderLeft, cylinderTop),
        size = Size(cylinderRight - cylinderLeft, cylinderHeight),
        cornerRadius = CornerRadius(cylinderHeight * 0.25f),
    )
    drawRoundRect(
        color = fillColor,
        topLeft = Offset(pistonCenterX, cylinderTop + 4f),
        size = Size((cylinderRight - pistonCenterX).coerceAtLeast(0f), cylinderHeight - 8f),
        cornerRadius = CornerRadius(cylinderHeight * 0.2f),
    )
    drawRoundRect(
        color = palette.neutralMetal,
        topLeft = Offset(cylinderLeft, cylinderTop),
        size = Size(cylinderRight - cylinderLeft, cylinderHeight),
        cornerRadius = CornerRadius(cylinderHeight * 0.25f),
        style = Stroke(width = 3f),
    )
    // Cylinder head bolt rings at both ends, a real machined-flange detail
    for (endX in listOf(cylinderLeft + 6f, cylinderRight - 6f)) {
        for (frac in listOf(0.2f, 0.5f, 0.8f)) {
            drawCircle(
                color = palette.spokeColor,
                radius = 1.6f,
                center = Offset(endX, cylinderTop + cylinderHeight * frac),
            )
        }
    }

    val pistonWidth = size.height * 0.055f
    drawRect(
        color = palette.strongMetal,
        topLeft = Offset(pistonCenterX - pistonWidth / 2f, cylinderTop - 3f),
        size = Size(pistonWidth, cylinderHeight + 6f),
    )
    return pistonCenterX
}

private fun DrawScope.drawCrossheadAndRod(pistonCenterX: Float, railY: Float, crankPin: Offset, palette: EnginePalette) {
    // Guide rails the crosshead slides between - real double-acting engines don't let
    // the connecting rod push straight on the piston, a crosshead takes the side load.
    val railHalfSpan = size.height * 0.05f
    drawLine(
        color = palette.spokeColor,
        start = Offset(pistonCenterX, railY - railHalfSpan),
        end = Offset(crankPin.x, railY - railHalfSpan),
        strokeWidth = 1.5f,
    )
    drawLine(
        color = palette.spokeColor,
        start = Offset(pistonCenterX, railY + railHalfSpan),
        end = Offset(crankPin.x, railY + railHalfSpan),
        strokeWidth = 1.5f,
    )
    drawRect(
        color = palette.strongMetal,
        topLeft = Offset(pistonCenterX - 5f, railY - railHalfSpan * 0.8f),
        size = Size(10f, railHalfSpan * 1.6f),
    )
    drawLine(
        color = palette.strongMetal,
        start = Offset(pistonCenterX, railY),
        end = crankPin,
        strokeWidth = 4f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawFlywheel(
    center: Offset,
    radius: Float,
    theta: Double,
    crankPin: Offset,
    rimColor: Color,
    palette: EnginePalette,
) {
    // Outer shadow ring for depth, then the real colored rim on top
    drawCircle(color = palette.housingColor, radius = radius + 3f, center = center, style = Stroke(width = 4f))
    drawCircle(color = rimColor, radius = radius, center = center, style = Stroke(width = 11f))

    val spokeCount = 6
    repeat(spokeCount) { i ->
        val spokeAngle = (2 * PI / spokeCount) * i + theta
        val end = Offset(
            center.x + (radius * 0.88f * cos(spokeAngle)).toFloat(),
            center.y + (radius * 0.88f * sin(spokeAngle)).toFloat(),
        )
        // Tapered look: a wide stroke near the hub, thin highlight line for depth
        drawLine(color = palette.spokeColor, start = center, end = end, strokeWidth = 6f, cap = StrokeCap.Round)
        drawLine(color = palette.neutralMetal, start = center, end = end, strokeWidth = 1.5f)
    }

    // Hub boss with bolt-hole ring
    val hubRadius = radius * 0.2f
    drawCircle(color = palette.housingColor, radius = hubRadius, center = center)
    drawCircle(color = palette.strongMetal, radius = hubRadius, center = center, style = Stroke(width = 2f))
    repeat(5) { i ->
        val a = (2 * PI / 5) * i + theta
        drawCircle(
            color = palette.spokeColor,
            radius = 1.6f,
            center = Offset(center.x + (hubRadius * 0.6f * cos(a)).toFloat(), center.y + (hubRadius * 0.6f * sin(a)).toFloat()),
        )
    }
    drawCircle(color = palette.strongMetal, radius = 6f, center = crankPin)

    // Counterweight opposite the crank pin, real practice to balance a reciprocating load
    val counterAngle = theta + PI
    val counterOuter = Offset(
        center.x + (radius * 0.92f * cos(counterAngle)).toFloat(),
        center.y + (radius * 0.92f * sin(counterAngle)).toFloat(),
    )
    drawCircle(color = palette.housingColor, radius = radius * 0.16f, center = counterOuter)
}

private fun DrawScope.drawGenerator(
    center: Offset,
    radius: Float,
    theta: Double,
    baseY: Float,
    currentFlowing: Boolean,
    drumColor: Color,
    palette: EnginePalette,
) {
    // Mounting feet down to the baseplate
    drawRect(color = palette.strongMetal, topLeft = Offset(center.x - radius * 0.7f, center.y + radius * 0.6f), size = Size(6f, baseY - (center.y + radius * 0.6f)))
    drawRect(color = palette.strongMetal, topLeft = Offset(center.x + radius * 0.6f, center.y + radius * 0.6f), size = Size(6f, baseY - (center.y + radius * 0.6f)))

    drawCircle(color = palette.housingColor, radius = radius, center = center)
    drawCircle(color = drumColor, radius = radius, center = center, style = Stroke(width = 10f))

    // Cooling fins around the housing edge
    repeat(14) { i ->
        val angle = (2 * PI / 14) * i
        val inner = Offset(center.x + (radius * 0.98f * cos(angle)).toFloat(), center.y + (radius * 0.98f * sin(angle)).toFloat())
        val outer = Offset(center.x + (radius * 1.1f * cos(angle)).toFloat(), center.y + (radius * 1.1f * sin(angle)).toFloat())
        drawLine(color = palette.neutralMetal, start = inner, end = outer, strokeWidth = 1.5f)
    }

    // Winding coil arcs on the drum face, rotating with the shaft
    repeat(4) { i ->
        val angle = (2 * PI / 4) * i + theta * 2.0
        val a = Offset(
            center.x + (radius * 0.55f * cos(angle)).toFloat(),
            center.y + (radius * 0.55f * sin(angle)).toFloat(),
        )
        drawCircle(color = palette.spokeColor, radius = radius * 0.12f, center = a, style = Stroke(width = 3f))
    }

    // Terminal posts and wires to a small terminal block below
    val postY = center.y - radius * 0.55f
    val leftPost = Offset(center.x - radius * 0.35f, postY)
    val rightPost = Offset(center.x + radius * 0.35f, postY)
    drawCircle(color = palette.strongMetal, radius = 3f, center = leftPost)
    drawCircle(color = palette.strongMetal, radius = 3f, center = rightPost)
    val blockTop = center.y - radius * 1.35f
    drawRect(color = palette.housingColor, topLeft = Offset(center.x - radius * 0.4f, blockTop), size = Size(radius * 0.8f, radius * 0.28f))
    drawLine(color = if (currentFlowing) Color(0xFFFFE9A6) else palette.neutralMetal, start = leftPost, end = Offset(leftPost.x, blockTop + radius * 0.28f), strokeWidth = 2f)
    drawLine(color = if (currentFlowing) Color(0xFFFFE9A6) else palette.neutralMetal, start = rightPost, end = Offset(rightPost.x, blockTop + radius * 0.28f), strokeWidth = 2f)

    // Brush spark: lit only while current is actually flowing to a real load
    drawCircle(
        color = if (currentFlowing) Color(0xFFFFE9A6) else palette.housingColor,
        radius = radius * 0.22f,
        center = center,
    )
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
