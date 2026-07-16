package com.powerforge.core.physics

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Specific heat ratio (Cp/Cv) for saturated/superheated steam. */
const val STEAM_SPECIFIC_HEAT_RATIO = 1.3

/**
 * Mass flow rate of steam through a valve/orifice of area [areaM2], from upstream
 * conditions to [downstreamPressurePa], via the standard compressible (isentropic)
 * nozzle flow equation - the same equation used to size real control valves. Below
 * the critical pressure ratio the flow chokes (goes sonic at the throat) and further
 * lowering the downstream pressure can't increase it any more, exactly like a real
 * steam valve.
 */
fun compressibleMassFlowKgPerS(
    areaM2: Double,
    dischargeCoefficient: Double,
    upstreamPressurePa: Double,
    upstreamTemperatureK: Double,
    downstreamPressurePa: Double,
): Double {
    if (areaM2 <= 0.0 || upstreamPressurePa <= 0.0) return 0.0
    val k = STEAM_SPECIFIC_HEAT_RATIO
    val criticalRatio = (2.0 / (k + 1.0)).pow(k / (k - 1.0))
    val pressureRatio = (downstreamPressurePa / upstreamPressurePa).coerceIn(0.0, 1.0)

    return if (pressureRatio <= criticalRatio) {
        dischargeCoefficient * areaM2 * upstreamPressurePa *
            sqrt(k / (upstreamTemperatureK * PhysicsConstants.STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K)) *
            (2.0 / (k + 1.0)).pow((k + 1.0) / (2.0 * (k - 1.0)))
    } else {
        val upstreamDensity = steamDensityKgPerM3(upstreamPressurePa, upstreamTemperatureK)
        val term = pressureRatio.pow(2.0 / k) - pressureRatio.pow((k + 1.0) / k)
        dischargeCoefficient * areaM2 *
            sqrt(max(0.0, (2.0 * k / (k - 1.0)) * upstreamPressurePa * upstreamDensity * term))
    }
}

/**
 * Mean effective pressure factor for a cutoff-governed cycle expanding hyperbolically
 * (PV = const) after admission stops at [cutoffRatio] (fraction of the stroke).
 * This is the standard textbook indicator-diagram formula for governed steam engines:
 * running a shorter cutoff trades peak torque for using the same steam more efficiently.
 */
fun hyperbolicExpansionMeanPressureFactor(cutoffRatio: Double): Double {
    val r = cutoffRatio.coerceIn(0.05, 0.98)
    return r + r * kotlin.math.ln(1.0 / r)
}
