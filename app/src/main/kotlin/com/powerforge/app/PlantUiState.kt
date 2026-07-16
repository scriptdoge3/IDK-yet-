package com.powerforge.app

import com.powerforge.core.physics.FailureReason
import com.powerforge.core.physics.PartKind

data class PartUiState(
    val kind: PartKind,
    val label: String,
    val level: Int,
    val summary: String,
)

/** The research frontier for one part: what's already unlocked and what's next. */
data class ResearchNodeUiState(
    val partKind: PartKind,
    val label: String,
    val currentLevel: Int,
    val maxLevel: Int,
    val nextNodeId: String?,
    val nextLabel: String,
    val nextDescription: String,
    val nextCostRp: Long,
    val isMaxed: Boolean,
)

data class ControlsUiState(
    val throttleFraction: Double = 1.0,
    val cutoffFraction: Double = 0.75,
    val fuelValveFraction: Double = 1.0,
    val ignitionOn: Boolean = true,
    val feedwaterValveFraction: Double = 1.0,
    val safetyValveOpen: Boolean = false,
    val excitationFraction: Double = 1.0,
    val clutchEngaged: Boolean = true,
    val emergencyBrakeEngaged: Boolean = false,
    val lubricationPercent: Double = 100.0,
)

data class PlantUiState(
    val credits: Double = 0.0,
    val researchPoints: Double = 0.0,
    val boilerTemperatureK: Double = 0.0,
    val boilerPressurePa: Double = 0.0,
    val boilerWaterLevelFraction: Double = 1.0,
    val cylinderPressurePa: Double = 101_325.0,
    val crankAngleRad: Double = 0.0,
    val rpm: Double = 0.0,
    val electricalPowerW: Double = 0.0,
    val heatInputW: Double = 0.0,
    val overallEfficiency: Double = 0.0,
    val rotatingAssemblyMassKg: Double = 0.0,
    val maxSupportedRotatingMassKg: Double = 0.0,
    val rotorWindingTemperatureK: Double = 0.0,
    val isDamaged: Boolean = false,
    val repairCostCredits: Long = 0,
    val failureReason: FailureReason = FailureReason.NONE,
    val parts: List<PartUiState> = emptyList(),
    val research: List<ResearchNodeUiState> = emptyList(),
    val controls: ControlsUiState = ControlsUiState(),
) {
    val isFailing: Boolean get() = failureReason != FailureReason.NONE
}
