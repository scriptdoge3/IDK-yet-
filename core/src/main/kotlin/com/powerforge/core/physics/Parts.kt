package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.max

/**
 * All part properties are derived from an integer [level] so the UI, the save
 * system, and the physics only ever need to agree on one number per part.
 * Levels only ever come from researching the matching node in the tech tree -
 * there is no separate purchase step.
 */

/** Burns fuel to heat the boiler water; also the pressure vessel and water tank itself. */
data class Boiler(val level: Int) {
    val heatInputW: Double = 1030.0 + (level - 1) * 320.0
    val waterCapacityKg: Double = 0.03 + (level - 1) * 0.045
    val insulationLossWPerK: Double = max(0.5, 2.6 - (level - 1) * 0.18)
    val maxPressurePa: Double = 300_000.0 + (level - 1) * 45_000.0

    /** Max rate the feedwater pump/valve can push fresh water into the boiler. */
    val feedwaterMaxFlowKgPerS: Double = 0.01 + (level - 1) * 0.003
}

/** Converts boiler pressure into torque via a piston, crank and connecting rod. */
data class PistonAssembly(val level: Int) {
    val boreRadiusM: Double = 0.011 + (level - 1) * 0.0018
    val crankRadiusM: Double = 0.017 + (level - 1) * 0.0024
    val exhaustPressurePa: Double = PhysicsConstants.ATMOSPHERIC_PRESSURE_PA

    val pistonAreaM2: Double get() = PI * boreRadiusM * boreRadiusM
    val strokeLengthM: Double get() = 2.0 * crankRadiusM
    val sweptVolumeM3: Double get() = pistonAreaM2 * strokeLengthM

    /** Throttle/cutoff valve throat scales with cylinder size - a bigger engine needs a bigger valve. */
    val maxValveAreaM2: Double get() = pistonAreaM2 * 0.004
}

/** Smooths torque delivery, but adds rotating mass and inertia to the shaft. */
data class Flywheel(val level: Int) {
    val massKg: Double = 0.35 + (level - 1) * 0.42
    val radiusM: Double = 0.045 + (level - 1) * 0.014

    /** Solid disk: I = 1/2 m r^2. */
    val momentOfInertiaKgM2: Double get() = 0.5 * massKg * radiusM * radiusM

    /** Better rim materials/manufacturing at higher levels raise the safe tip speed before it bursts. */
    val maxSafeTipSpeedMPerS: Double = 20.0 + (level - 1) * 4.0
}

/** DC generator equivalent circuit: EMF = ke*omega, current limited by internal + load resistance. */
data class GeneratorRotor(val level: Int) {
    val massKg: Double = 0.14 + (level - 1) * 0.22
    val radiusM: Double = 0.018 + (level - 1) * 0.005
    val backEmfConstantVSPerRad: Double = 0.11 + (level - 1) * 0.045
    val internalResistanceOhm: Double = max(0.12, 0.55 - (level - 1) * 0.035)

    /** Insulation class improves with level: higher levels tolerate more winding heat before burnout. */
    val maxWindingTemperatureK: Double = 420.0 + (level - 1) * 25.0
    val thermalMassJPerK: Double = 60.0 + (level - 1) * 12.0

    val momentOfInertiaKgM2: Double get() = 0.5 * massKg * radiusM * radiusM
}

/**
 * The chassis and bearings the drivetrain is mounted on. This is what puts a hard
 * ceiling on how big the flywheel + rotor can get: exceed [maxSupportedRotatingMassKg]
 * and the assembly is physically too heavy for the mounts to carry, full stop.
 */
data class Frame(val level: Int) {
    val maxSupportedRotatingMassKg: Double = 1.0 + (level - 1) * 0.7
    val bearingFrictionCoeff: Double = max(0.012, 0.045 - (level - 1) * 0.004)
    val viscousFrictionCoeffNmSPerRad: Double = max(0.00015, 0.00065 - (level - 1) * 0.00005)
    val bearingRadiusM: Double = 0.008
}
