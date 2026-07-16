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
import com.powerforge.core.research.ControlUnlock
import com.powerforge.core.research.ResearchEffect
import com.powerforge.core.research.ResearchTree
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the physics simulation, the credit economy, and the research economy built
 * on top of it. The game clock runs faster than a real wall clock purely for pacing -
 * it does not change any of the underlying physics, just how much simulated time
 * elapses per real-world tick.
 */
class PlantViewModel : ViewModel() {

    companion object {
        private const val TICK_INTERVAL_MS = 150L
        private const val GAME_TIME_MULTIPLIER = 3.0
        private const val CREDITS_PER_JOULE = 1.0
        private const val BASE_RESEARCH_POINTS_PER_SECOND = 0.3
        private const val RESEARCH_POINTS_PER_WATT_SECOND = 0.01
    }

    private val plant = SteamEnginePlant()

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

    fun upgrade(kind: PartKind) {
        val currentLevel = levelOf(kind)
        val maxLevel = ResearchTree.maxLevelFor(kind, researchedNodeIds)
        if (currentLevel >= maxLevel) return

        val cost = costOf(kind)
        if (credits < cost) return
        credits -= cost
        bumpLevel(kind)

        _uiState.update { buildUiState() }
    }

    fun research(nodeId: String) {
        if (!ResearchTree.canResearch(nodeId, researchedNodeIds)) return
        val node = ResearchTree.byId[nodeId] ?: return
        if (researchPoints < node.costRp) return

        researchPoints -= node.costRp
        researchedNodeIds = researchedNodeIds + nodeId

        val effect = node.effect
        if (effect is ResearchEffect.UnlockControl && effect.control == ControlUnlock.LUBRICATION) {
            plant.lubricationSystemActive = true
        }

        _uiState.update { buildUiState() }
    }

    fun setThrottle(fraction: Double) {
        plant.throttleFraction = fraction.coerceIn(0.0, 1.0)
        _uiState.update { it.copy(controls = it.controls.copy(throttleFraction = plant.throttleFraction)) }
    }

    fun setFuelValve(fraction: Double) {
        plant.fuelValveFraction = fraction.coerceIn(0.0, 1.0)
        _uiState.update { it.copy(controls = it.controls.copy(fuelValveFraction = plant.fuelValveFraction)) }
    }

    fun setIgnition(on: Boolean) {
        plant.ignitionOn = on
        _uiState.update { it.copy(controls = it.controls.copy(ignitionOn = on)) }
    }

    fun setGeneratorEngaged(engaged: Boolean) {
        plant.generatorEngaged = engaged
        _uiState.update { it.copy(controls = it.controls.copy(generatorEngaged = engaged)) }
    }

    fun setSafetyValveOpen(open: Boolean) {
        plant.safetyValveOpen = open
        _uiState.update { it.copy(controls = it.controls.copy(safetyValveOpen = open)) }
    }

    fun addLubrication() {
        plant.addLubrication()
        _uiState.update { it.copy(controls = it.controls.copy(lubricationPercent = plant.lubricationPercent)) }
    }

    private fun levelOf(kind: PartKind): Int = when (kind) {
        PartKind.BOILER -> plant.boiler.level
        PartKind.PISTON -> plant.piston.level
        PartKind.FLYWHEEL -> plant.flywheel.level
        PartKind.ROTOR -> plant.rotor.level
        PartKind.FRAME -> plant.frame.level
    }

    private fun costOf(kind: PartKind): Long = when (kind) {
        PartKind.BOILER -> plant.boiler.upgradeCost()
        PartKind.PISTON -> plant.piston.upgradeCost()
        PartKind.FLYWHEEL -> plant.flywheel.upgradeCost()
        PartKind.ROTOR -> plant.rotor.upgradeCost()
        PartKind.FRAME -> plant.frame.upgradeCost()
    }

    private fun bumpLevel(kind: PartKind) {
        when (kind) {
            PartKind.BOILER -> plant.boiler = Boiler(plant.boiler.level + 1)
            PartKind.PISTON -> plant.piston = PistonAssembly(plant.piston.level + 1)
            PartKind.FLYWHEEL -> plant.flywheel = Flywheel(plant.flywheel.level + 1)
            PartKind.ROTOR -> plant.rotor = GeneratorRotor(plant.rotor.level + 1)
            PartKind.FRAME -> plant.frame = Frame(plant.frame.level + 1)
        }
    }

    private fun buildUiState(): PlantUiState {
        val status = plant.status()
        return PlantUiState(
            credits = credits,
            researchPoints = researchPoints,
            boilerTemperatureK = status.boilerTemperatureK,
            boilerPressurePa = status.boilerPressurePa,
            rpm = status.rpm,
            electricalPowerW = status.electricalPowerW,
            heatInputW = status.heatInputW,
            overallEfficiency = status.overallEfficiency,
            rotatingAssemblyMassKg = status.rotatingAssemblyMassKg,
            maxSupportedRotatingMassKg = status.maxSupportedRotatingMassKg,
            failureReason = status.failureReason,
            parts = buildPartsUi(),
            research = buildResearchUi(),
            controls = ControlsUiState(
                throttleUnlocked = ResearchTree.isControlUnlocked(ControlUnlock.THROTTLE, researchedNodeIds),
                throttleFraction = plant.throttleFraction,
                ignitionUnlocked = ResearchTree.isControlUnlocked(ControlUnlock.IGNITION, researchedNodeIds),
                ignitionOn = plant.ignitionOn,
                fuelValveFraction = plant.fuelValveFraction,
                generatorClutchUnlocked = ResearchTree.isControlUnlocked(ControlUnlock.GENERATOR_CLUTCH, researchedNodeIds),
                generatorEngaged = plant.generatorEngaged,
                safetyValveUnlocked = ResearchTree.isControlUnlocked(ControlUnlock.SAFETY_VALVE, researchedNodeIds),
                safetyValveOpen = plant.safetyValveOpen,
                lubricationUnlocked = ResearchTree.isControlUnlocked(ControlUnlock.LUBRICATION, researchedNodeIds),
                lubricationPercent = status.lubricationPercent,
            ),
        )
    }

    private fun buildPartsUi(): List<PartUiState> = listOf(
        PartUiState(
            kind = PartKind.BOILER,
            label = "Boiler",
            level = plant.boiler.level,
            maxLevel = ResearchTree.maxLevelFor(PartKind.BOILER, researchedNodeIds),
            upgradeCost = plant.boiler.upgradeCost(),
            summary = "${plant.boiler.heatInputW.toInt()} W heat · ${(plant.boiler.maxPressurePa / 1000).toInt()} kPa max",
        ),
        PartUiState(
            kind = PartKind.PISTON,
            label = "Piston & Crank",
            level = plant.piston.level,
            maxLevel = ResearchTree.maxLevelFor(PartKind.PISTON, researchedNodeIds),
            upgradeCost = plant.piston.upgradeCost(),
            summary = "${(plant.piston.boreRadiusM * 2000).toInt()} mm bore · ${(plant.piston.crankRadiusM * 2000).toInt()} mm stroke",
        ),
        PartUiState(
            kind = PartKind.FLYWHEEL,
            label = "Flywheel",
            level = plant.flywheel.level,
            maxLevel = ResearchTree.maxLevelFor(PartKind.FLYWHEEL, researchedNodeIds),
            upgradeCost = plant.flywheel.upgradeCost(),
            summary = "${"%.2f".format(plant.flywheel.massKg)} kg · ${(plant.flywheel.radiusM * 100).toInt()} cm radius",
        ),
        PartUiState(
            kind = PartKind.ROTOR,
            label = "Generator Rotor",
            level = plant.rotor.level,
            maxLevel = ResearchTree.maxLevelFor(PartKind.ROTOR, researchedNodeIds),
            upgradeCost = plant.rotor.upgradeCost(),
            summary = "${"%.2f".format(plant.rotor.massKg)} kg · ke=${"%.2f".format(plant.rotor.backEmfConstantVSPerRad)}",
        ),
        PartUiState(
            kind = PartKind.FRAME,
            label = "Frame & Bearings",
            level = plant.frame.level,
            maxLevel = ResearchTree.maxLevelFor(PartKind.FRAME, researchedNodeIds),
            upgradeCost = plant.frame.upgradeCost(),
            summary = "supports ${"%.2f".format(plant.frame.maxSupportedRotatingMassKg)} kg rotating",
        ),
    )

    private fun buildResearchUi(): List<ResearchNodeUiState> = ResearchTree.nodes.map { node ->
        ResearchNodeUiState(
            id = node.id,
            branch = node.branch,
            label = node.label,
            description = node.description,
            costRp = node.costRp,
            prerequisiteLabels = node.prerequisiteIds.mapNotNull { ResearchTree.byId[it]?.label },
            isResearched = node.id in researchedNodeIds,
            isAvailable = ResearchTree.canResearch(node.id, researchedNodeIds),
        )
    }
}
