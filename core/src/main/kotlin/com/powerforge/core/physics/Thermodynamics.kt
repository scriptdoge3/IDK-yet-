package com.powerforge.core.physics

import kotlin.math.exp
import kotlin.math.ln

/**
 * Saturation vapor pressure of water at [temperatureK], via the Clausius-Clapeyron
 * relation anchored at standard atmospheric boiling (373.15 K -> 101,325 Pa):
 *
 *   P(T) = P0 * exp( (L / R) * (1/T0 - 1/T) )
 *
 * L is treated as constant, so this drifts from real steam tables far from 100C,
 * but stays accurate enough for the boiler pressures this plant reaches.
 */
fun saturationPressurePa(temperatureK: Double): Double {
    val l = PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
    val r = PhysicsConstants.STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K
    val t0 = PhysicsConstants.REFERENCE_BOILING_POINT_K
    val p0 = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
    return p0 * exp((l / r) * (1.0 / t0 - 1.0 / temperatureK))
}

/**
 * The exact algebraic inverse of [saturationPressurePa]: the temperature at which
 * water's saturation vapor pressure equals [pressurePa]. Solving
 * P = P0*exp((L/R)(1/T0 - 1/T)) for T gives:
 *
 *   1/T = 1/T0 - (R/L) * ln(P/P0)
 */
fun saturationTemperatureK(pressurePa: Double): Double {
    val l = PhysicsConstants.WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG
    val r = PhysicsConstants.STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K
    val t0 = PhysicsConstants.REFERENCE_BOILING_POINT_K
    val p0 = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA
    val inverseT = 1.0 / t0 - (r / l) * ln(pressurePa / p0)
    return 1.0 / inverseT
}

/** Ideal-gas density of the steam at the given pressure/temperature. */
fun steamDensityKgPerM3(pressurePa: Double, temperatureK: Double): Double {
    val r = PhysicsConstants.STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K
    return pressurePa / (r * temperatureK)
}
