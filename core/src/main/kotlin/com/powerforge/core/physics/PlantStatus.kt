package com.powerforge.core.physics

enum class FailureReason {
    NONE,

    /** Flywheel + rotor + shaft mass exceeds what the frame/bearings can carry: it cannot move at all. */
    STRUCTURAL_OVERLOAD,

    /** Drive torque never exceeds static friction, so the shaft never starts turning. */
    STALLED_INSUFFICIENT_TORQUE,
}

/** A read-only snapshot of the plant for the UI/save layer to consume. */
data class PlantStatus(
    val boilerTemperatureK: Double,
    val boilerPressurePa: Double,
    val angularVelocityRadPerS: Double,
    val rpm: Double,
    val electricalPowerW: Double,
    val heatInputW: Double,
    val overallEfficiency: Double,
    val rotatingAssemblyMassKg: Double,
    val maxSupportedRotatingMassKg: Double,
    val failureReason: FailureReason,
) {
    val isFailing: Boolean get() = failureReason != FailureReason.NONE
}
