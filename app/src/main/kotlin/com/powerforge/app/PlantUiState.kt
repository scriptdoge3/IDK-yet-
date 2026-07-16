package com.powerforge.app

import com.powerforge.core.physics.FailureReason
import com.powerforge.core.physics.PartKind
import com.powerforge.core.research.ResearchBranch

data class PartUiState(
    val kind: PartKind,
    val label: String,
    val level: Int,
    val maxLevel: Int,
    val upgradeCost: Long,
    val summary: String,
) {
    val isLevelCapped: Boolean get() = level >= maxLevel
}

data class ResearchNodeUiState(
    val id: String,
    val branch: ResearchBranch,
    val label: String,
    val description: String,
    val costRp: Long,
    val prerequisiteLabels: List<String>,
    val isResearched: Boolean,
    val isAvailable: Boolean,
)

data class ControlsUiState(
    val throttleUnlocked: Boolean = false,
    val throttleFraction: Double = 1.0,
    val ignitionUnlocked: Boolean = false,
    val ignitionOn: Boolean = true,
    val fuelValveFraction: Double = 1.0,
    val generatorClutchUnlocked: Boolean = false,
    val generatorEngaged: Boolean = true,
    val safetyValveUnlocked: Boolean = false,
    val safetyValveOpen: Boolean = false,
    val lubricationUnlocked: Boolean = false,
    val lubricationPercent: Double = 100.0,
) {
    /** True once at least one control has been researched - the panel only shows up then. */
    val hasAnyControl: Boolean
        get() = throttleUnlocked || ignitionUnlocked || generatorClutchUnlocked || safetyValveUnlocked || lubricationUnlocked
}

data class PlantUiState(
    val credits: Double = 0.0,
    val researchPoints: Double = 0.0,
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
    val research: List<ResearchNodeUiState> = emptyList(),
    val controls: ControlsUiState = ControlsUiState(),
) {
    val isFailing: Boolean get() = failureReason != FailureReason.NONE
}
