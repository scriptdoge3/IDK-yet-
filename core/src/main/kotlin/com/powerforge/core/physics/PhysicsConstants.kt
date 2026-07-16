package com.powerforge.core.physics

/** Real-world physical constants used by the simulation (SI units throughout). */
object PhysicsConstants {
    const val GRAVITY_M_PER_S2 = 9.81
    const val WATER_SPECIFIC_HEAT_J_PER_KG_K = 4186.0
    const val WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG = 2_257_000.0
    const val STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K = 461.5
    const val ATMOSPHERIC_PRESSURE_PA = 101_325.0
    const val REFERENCE_BOILING_POINT_K = 373.15
    const val AMBIENT_TEMPERATURE_K = 293.15

    /** Static friction always exceeds kinetic friction for real bearings. */
    const val STATIC_FRICTION_MULTIPLIER = 1.6
}
