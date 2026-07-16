package com.powerforge.app

import com.powerforge.core.physics.FailureReason

enum class PartKind { BOILER, PISTON, FLYWHEEL, ROTOR, FRAME }

data class PartUiState(
    val kind: PartKind,
    val label: String,
    val level: Int,
    val upgradeCost: Long,
    val summary: String,
)

data class PlantUiState(
    val credits: Double = 0.0,
    val boilerTemperatureK: Double = 0.0,
    val boilerPressurePa: Double = 0.0,
    val rpm: Double = 0.0,
    val electricalPowerW: Double = 0.0,
    val heatInputW: Double = 0.0,
    val overallEfficiency: Double = 0.0,
    val rotatingAssemblyMassKg: Double = 0.0,
    val maxSupportedRotatingMassKg: Double = 0.0,
    val failureReason: FailureReason = FailureReason.NONE,
    val parts: List<PartUiState> = emptyList(),
) {
    val isFailing: Boolean get() = failureReason != FailureReason.NONE
}
