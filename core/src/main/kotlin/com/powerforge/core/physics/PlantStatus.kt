package com.powerforge.core.physics

enum class FailureReason {
    NONE,

    /** Flywheel + rotor + shaft mass exceeds what the frame/bearings can carry: it cannot move at all. */
    STRUCTURAL_OVERLOAD,

    /** Drive torque never exceeds static friction, so the shaft never starts turning. */
    STALLED_INSUFFICIENT_TORQUE,

    /** The operator has shut off the burner. Not a malfunction, just parked. */
    IGNITION_OFF,

    /** Flywheel rim tip speed exceeded what it can structurally hold together at - it let go. */
    FLYWHEEL_BURST,

    /** Boiler pressure ran well past its rated limit faster than any relief path could bleed it off. */
    BOILER_RUPTURED,

    /** Boiler was fired with no water left in it - the classic, catastrophic real steam failure. */
    BOILER_DRY_FIRE,

    /** Bearings ran dry long enough to gall and lock solid. */
    BEARING_SEIZED,

    /** Rotor windings overheated from running too much current/excitation for too long. */
    ROTOR_BURNOUT,
}

/** A read-only snapshot of the plant for the UI/save layer to consume. */
data class PlantStatus(
    val boilerTemperatureK: Double,
    val boilerPressurePa: Double,
    val boilerWaterLevelFraction: Double,
    val boilerScalePercent: Double,
    val cylinderPressurePa: Double,
    val crankAngleRad: Double,
    val angularVelocityRadPerS: Double,
    val rpm: Double,
    val electricalPowerW: Double,
    val heatInputW: Double,
    val overallEfficiency: Double,
    val rotatingAssemblyMassKg: Double,
    val maxSupportedRotatingMassKg: Double,
    val lubricationPercent: Double,
    val rotorWindingTemperatureK: Double,
    val generatorEngaged: Boolean,
    val circuitBreakerClosed: Boolean,
    val drainCocksOpen: Boolean,
    val blowdownValveOpen: Boolean,
    val isDamaged: Boolean,
    val failureReason: FailureReason,
) {
    val isFailing: Boolean get() = failureReason != FailureReason.NONE
}
