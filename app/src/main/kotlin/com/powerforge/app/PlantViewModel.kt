package com.powerforge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powerforge.core.physics.Boiler
import com.powerforge.core.physics.Flywheel
import com.powerforge.core.physics.Frame
import com.powerforge.core.physics.GeneratorRotor
import com.powerforge.core.physics.PartKind
import com.powerforge.core.physics.PistonAssembly
import com.powerforge.core.physics.SteamEnginePlant
import com.powerforge.core.research.ResearchTree
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the physics simulation, the credit economy (paid for oil and repairs), and
 * the research economy (the only way parts actually level up). The game clock runs
 * faster than a real wall clock purely for pacing - it does not change any of the
 * underlying physics, just how much simulated time elapses per real-world tick.
 */
class PlantViewModel : ViewModel() {

    companion object {
        private const val TICK_INTERVAL_MS = 150L
        private const val GAME_TIME_MULTIPLIER = 3.0
        private const val CREDITS_PER_JOULE = 1.0
        private const val BASE_RESEARCH_POINTS_PER_SECOND = 0.3
        private const val RESEARCH_POINTS_PER_WATT_SECOND = 0.01
        private const val BASE_REPAIR_COST_CREDITS = 150L
        private const val REPAIR_COST_PER_LEVEL_CREDITS = 35L
    }

    // A fresh session starts as a real plant actually does: cold, unlit, and
    // stopped - the operator has to light the burner and bring it up to pressure
    // themselves, not find it already running.
    private val plant = SteamEnginePlant().apply { ignitionOn = false }

    private var credits: Double = 0.0
    private var researchPoints: Double = 0.0
    private var researchedNodeIds: Set<String> = emptySet()

    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<PlantUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val dtSeconds = (TICK_INTERVAL_MS / 1000.0) * GAME_TIME_MULTIPLIER
                plant.step(dtSeconds)

                val status = plant.status()
                credits += status.electricalPowerW * dtSeconds * CREDITS_PER_JOULE
                val ignitedFraction = if (plant.ignitionOn) 1.0 else 0.15
                researchPoints += (BASE_RESEARCH_POINTS_PER_SECOND * ignitedFraction +
                    status.electricalPowerW * RESEARCH_POINTS_PER_WATT_SECOND) * dtSeconds

                _uiState.update { buildUiState() }
            }
        }
    }

    fun research(nodeId: String) {
        if (!ResearchTree.canResearch(nodeId, researchedNodeIds)) return
        val node = ResearchTree.byId[nodeId] ?: return
        if (researchPoints < node.costRp) return

        researchPoints -= node.costRp
        researchedNodeIds = researchedNodeIds + nodeId
        setPartLevel(node.partKind, node.toLevel)

        _uiState.update { buildUiState() }
    }

    fun repair() {
        val cost = repairCostCredits()
        if (credits < cost || !plant.isDamaged) return
        credits -= cost
        plant.repair()
        _uiState.update { buildUiState() }
    }

    fun setThrottle(fraction: Double) {
        plant.throttleFraction = fraction.coerceIn(0.0, 1.0)
        pushControls()
    }

    fun setCutoff(fraction: Double) {
        plant.cutoffFraction = fraction.coerceIn(0.05, 0.98)
        pushControls()
    }

    fun setFuelValve(fraction: Double) {
        plant.fuelValveFraction = fraction.coerceIn(0.0, 1.0)
        pushControls()
    }

    fun setIgnition(on: Boolean) {
        plant.ignitionOn = on
        pushControls()
    }

    fun setFeedwaterValve(fraction: Double) {
        plant.feedwaterValveFraction = fraction.coerceIn(0.0, 1.0)
        pushControls()
    }

    fun setSafetyValveOpen(open: Boolean) {
        plant.safetyValveOpen = open
        pushControls()
    }

    fun setExcitation(fraction: Double) {
        plant.excitationFraction = fraction.coerceIn(0.0, 1.5)
        pushControls()
    }

    fun setClutchEngaged(engaged: Boolean) {
        plant.clutchEngaged = engaged
        pushControls()
    }

    fun setEmergencyBrake(engaged: Boolean) {
        plant.emergencyBrakeEngaged = engaged
        pushControls()
    }

    fun setAirDamper(fraction: Double) {
        plant.airDamperFraction = fraction.coerceIn(0.0, 1.0)
        pushControls()
    }

    fun setLoadRheostat(ohm: Double) {
        plant.loadRheostatOhm = ohm.coerceIn(0.5, 6.0)
        pushControls()
    }

    fun setLubricatorFeedRate(fraction: Double) {
        plant.lubricatorFeedRateFraction = fraction.coerceIn(0.0, 1.0)
        pushControls()
    }

    fun setCircuitBreakerClosed(closed: Boolean) {
        plant.circuitBreakerClosed = closed
        pushControls()
    }

    fun setDrainCocksOpen(open: Boolean) {
        plant.drainCocksOpen = open
        pushControls()
    }

    fun setBlowdownValveOpen(open: Boolean) {
        plant.blowdownValveOpen = open
        pushControls()
    }

    private fun pushControls() {
        _uiState.update {
            it.copy(
                controls = it.controls.copy(
                    throttleFraction = plant.throttleFraction,
                    cutoffFraction = plant.cutoffFraction,
                    fuelValveFraction = plant.fuelValveFraction,
                    airDamperFraction = plant.airDamperFraction,
                    ignitionOn = plant.ignitionOn,
                    feedwaterValveFraction = plant.feedwaterValveFraction,
                    safetyValveOpen = plant.safetyValveOpen,
                    excitationFraction = plant.excitationFraction,
                    loadRheostatOhm = plant.loadRheostatOhm,
                    lubricatorFeedRateFraction = plant.lubricatorFeedRateFraction,
                    clutchEngaged = plant.clutchEngaged,
                    emergencyBrakeEngaged = plant.emergencyBrakeEngaged,
                    circuitBreakerClosed = plant.circuitBreakerClosed,
                    drainCocksOpen = plant.drainCocksOpen,
                    blowdownValveOpen = plant.blowdownValveOpen,
                ),
            )
        }
    }

    private fun levelOf(kind: PartKind): Int = when (kind) {
        PartKind.BOILER -> plant.boiler.level
        PartKind.PISTON -> plant.piston.level
        PartKind.FLYWHEEL -> plant.flywheel.level
        PartKind.ROTOR -> plant.rotor.level
        PartKind.FRAME -> plant.frame.level
    }

    private fun setPartLevel(kind: PartKind, level: Int) {
        when (kind) {
            PartKind.BOILER -> plant.boiler = Boiler(level)
            PartKind.PISTON -> plant.piston = PistonAssembly(level)
            PartKind.FLYWHEEL -> plant.flywheel = Flywheel(level)
            PartKind.ROTOR -> plant.rotor = GeneratorRotor(level)
            PartKind.FRAME -> plant.frame = Frame(level)
        }
    }

    private fun partLabel(kind: PartKind): String = when (kind) {
        PartKind.BOILER -> "Boiler"
        PartKind.PISTON -> "Piston & Crank"
        PartKind.FLYWHEEL -> "Flywheel"
        PartKind.ROTOR -> "Generator Rotor"
        PartKind.FRAME -> "Frame & Bearings"
    }

    private fun repairCostCredits(): Long =
        BASE_REPAIR_COST_CREDITS + PartKind.entries.sumOf { levelOf(it).toLong() } * REPAIR_COST_PER_LEVEL_CREDITS

    private fun buildUiState(): PlantUiState {
        val status = plant.status()
        return PlantUiState(
            credits = credits,
            researchPoints = researchPoints,
            boilerTemperatureK = status.boilerTemperatureK,
            boilerPressurePa = status.boilerPressurePa,
            boilerWaterLevelFraction = status.boilerWaterLevelFraction,
            cylinderPressurePa = status.cylinderPressurePa,
            cylinderTemperatureK = status.cylinderTemperatureK,
            crankAngleRad = status.crankAngleRad,
            rpm = status.rpm,
            electricalPowerW = status.electricalPowerW,
            heatInputW = status.heatInputW,
            overallEfficiency = status.overallEfficiency,
            rotatingAssemblyMassKg = status.rotatingAssemblyMassKg,
            maxSupportedRotatingMassKg = status.maxSupportedRotatingMassKg,
            rotorWindingTemperatureK = status.rotorWindingTemperatureK,
            flameActive = status.flameActive,
            flywheelStressFraction = status.flywheelStressFraction,
            boilerStressFraction = status.boilerStressFraction,
            windingStressFraction = status.windingStressFraction,
            flywheelRadiusM = plant.flywheel.radiusM,
            cylinderParticles = plant.cylinderParticles(),
            flywheelLatticePoints = plant.flywheelLatticePoints(),
            isDamaged = status.isDamaged,
            repairCostCredits = repairCostCredits(),
            failureReason = status.failureReason,
            parts = buildPartsUi(),
            research = buildResearchUi(),
            controls = ControlsUiState(
                throttleFraction = plant.throttleFraction,
                cutoffFraction = plant.cutoffFraction,
                fuelValveFraction = plant.fuelValveFraction,
                airDamperFraction = plant.airDamperFraction,
                ignitionOn = plant.ignitionOn,
                feedwaterValveFraction = plant.feedwaterValveFraction,
                safetyValveOpen = plant.safetyValveOpen,
                excitationFraction = plant.excitationFraction,
                loadRheostatOhm = plant.loadRheostatOhm,
                lubricatorFeedRateFraction = plant.lubricatorFeedRateFraction,
                clutchEngaged = plant.clutchEngaged,
                emergencyBrakeEngaged = plant.emergencyBrakeEngaged,
                circuitBreakerClosed = plant.circuitBreakerClosed,
                drainCocksOpen = plant.drainCocksOpen,
                blowdownValveOpen = plant.blowdownValveOpen,
                lubricationPercent = status.lubricationPercent,
                boilerScalePercent = status.boilerScalePercent,
            ),
        )
    }

    private fun buildPartsUi(): List<PartUiState> = listOf(
        PartUiState(
            kind = PartKind.BOILER,
            label = partLabel(PartKind.BOILER),
            level = plant.boiler.level,
            summary = "${plant.boiler.heatInputW.toInt()} W heat · ${(plant.boiler.maxPressurePa / 1000).toInt()} kPa max",
        ),
        PartUiState(
            kind = PartKind.PISTON,
            label = partLabel(PartKind.PISTON),
            level = plant.piston.level,
            summary = "${(plant.piston.boreRadiusM * 2000).toInt()} mm bore · ${(plant.piston.crankRadiusM * 2000).toInt()} mm stroke",
        ),
        PartUiState(
            kind = PartKind.FLYWHEEL,
            label = partLabel(PartKind.FLYWHEEL),
            level = plant.flywheel.level,
            summary = "${"%.2f".format(plant.flywheel.massKg)} kg · ${(plant.flywheel.radiusM * 100).toInt()} cm radius",
        ),
        PartUiState(
            kind = PartKind.ROTOR,
            label = partLabel(PartKind.ROTOR),
            level = plant.rotor.level,
            summary = "${"%.2f".format(plant.rotor.massKg)} kg · ke=${"%.2f".format(plant.rotor.backEmfConstantVSPerRad)}",
        ),
        PartUiState(
            kind = PartKind.FRAME,
            label = partLabel(PartKind.FRAME),
            level = plant.frame.level,
            summary = "supports ${"%.2f".format(plant.frame.maxSupportedRotatingMassKg)} kg rotating",
        ),
    )

    private fun buildResearchUi(): List<ResearchNodeUiState> = PartKind.entries.map { kind ->
        val currentLevel = levelOf(kind)
        val nextId = ResearchTree.nextNodeIdFor(kind, currentLevel)
        val nextNode = nextId?.let { ResearchTree.byId[it] }
        ResearchNodeUiState(
            partKind = kind,
            label = partLabel(kind),
            currentLevel = currentLevel,
            maxLevel = ResearchTree.MAX_LEVEL,
            nextNodeId = nextId,
            nextLabel = nextNode?.label ?: "",
            nextDescription = nextNode?.description ?: "Fully researched.",
            nextCostRp = nextNode?.costRp ?: 0L,
            isMaxed = nextNode == null,
        )
    }
}
