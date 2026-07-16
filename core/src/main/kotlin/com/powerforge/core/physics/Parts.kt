package com.powerforge.core.physics

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

/**
 * All part properties are derived from an integer [level] so the UI, the save
 * system, and the physics only ever need to agree on one number per part.
 * Levels only ever come from researching the matching node in the tech tree -
 * there is no separate purchase step.
 */

/** Burns fuel to heat the boiler water; also the pressure vessel and water tank itself. */
data class Boiler(val level: Int) {
    // Piston swept volume grows roughly cubically with level (bore and stroke both scale
    // up), so heat generation has to compound too or an upgraded boiler paired with an
    // upgraded piston would starve the cylinder worse at every level instead of better.
    val heatInputW: Double = 2400.0 * 1.4.pow(level - 1)
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

    val pistonAreaM2: Double get() = PI * boreRadiusM * boreRadiusM
    val strokeLengthM: Double get() = 2.0 * crankRadiusM
    val sweptVolumeM3: Double get() = pistonAreaM2 * strokeLengthM

    /** Throttle/cutoff valve throat scales with cylinder size - a bigger engine needs a bigger valve. */
    val maxValveAreaM2: Double get() = pistonAreaM2 * 0.05

    /** Real cylinders never fully empty at TDC - a small dead volume stays trapped. */
    val clearanceVolumeM3: Double get() = sweptVolumeM3 * 0.08

    /** Connecting rod length; a 4:1 rod/crank ratio is typical of real reciprocating engines. */
    val connectingRodLengthM: Double get() = crankRadiusM * 4.0

    /**
     * A solid steel cylinder roughly one bore-diameter long is a standard real
     * proportion for a piston (real piston length-to-bore ratios cluster close to
     * 1:1 for this class of engine) - so its mass is just real steel density times
     * that real geometry, not a chosen number. This is the reciprocating mass gravity
     * acts on every revolution, and what the piston/cylinder metal itself expands by
     * with real thermal expansion as it heats up.
     */
    val pistonMassKg: Double get() = PhysicsConstants.STEEL_DENSITY_KG_PER_M3 * pistonAreaM2 * (2.0 * boreRadiusM)
}

/**
 * Smooths torque delivery, but adds rotating mass and inertia to the shaft. Cast iron,
 * the traditional real material for flywheels - [PhysicsConstants.CAST_IRON_DENSITY_KG_PER_M3]
 * and [PhysicsConstants.CAST_IRON_TENSILE_STRENGTH_PA] are what actually determine how
 * fast it can spin before it bursts (see [SteamEnginePlant]'s damage check), not a
 * separate per-level number: a bigger rim at the same tip speed carries the same real
 * hoop stress, so the only thing raising the practical burst RPM at higher levels is the
 * player choosing to spin it less, or upgrading the Frame so a smaller flywheel can carry
 * the same rotating mass.
 */
data class Flywheel(val level: Int) {
    val massKg: Double = 2.2 + (level - 1) * 0.42
    val radiusM: Double = 0.095 + (level - 1) * 0.014

    /** Solid disk: I = 1/2 m r^2. */
    val momentOfInertiaKgM2: Double get() = 0.5 * massKg * radiusM * radiusM
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
    val maxSupportedRotatingMassKg: Double = 3.0 + (level - 1) * 0.7
    val bearingFrictionCoeff: Double = max(0.012, 0.045 - (level - 1) * 0.004)
    val viscousFrictionCoeffNmSPerRad: Double = max(0.00015, 0.00065 - (level - 1) * 0.00005)
    val bearingRadiusM: Double = 0.008
}
