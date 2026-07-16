package com.powerforge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powerforge.core.physics.Boiler
import com.powerforge.core.physics.Flywheel
import com.powerforge.core.physics.Frame
import com.powerforge.core.physics.GeneratorRotor
import com.powerforge.core.physics.PistonAssembly
import com.powerforge.core.physics.SteamEnginePlant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the physics simulation and the credit economy built on top of it.
 * The game clock runs faster than a real wall clock purely for pacing -
 * it does not change any of the underlying physics, just how much
 * simulated time elapses per real-world tick.
 */
class PlantViewModel : ViewModel() {

    companion object {
        private const val TICK_INTERVAL_MS = 150L
        private const val GAME_TIME_MULTIPLIER = 3.0
        private const val CREDITS_PER_JOULE = 1.0
    }

    private val plant = SteamEnginePlant()

    private val _uiState = MutableStateFlow(PlantUiState(parts = buildPartsUi(plant, 0.0)))
    val uiState: StateFlow<PlantUiState> = _uiState.asStateFlow()

    private var credits: Double = 0.0

    init {
        viewModelScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val dtSeconds = (TICK_INTERVAL_MS / 1000.0) * GAME_TIME_MULTIPLIER
                plant.step(dtSeconds)

                val status = plant.status()
                credits += status.electricalPowerW * dtSeconds * CREDITS_PER_JOULE

                _uiState.update {
                    PlantUiState(
                        credits = credits,
                        boilerTemperatureK = status.boilerTemperatureK,
                        boilerPressurePa = status.boilerPressurePa,
                        rpm = status.rpm,
                        electricalPowerW = status.electricalPowerW,
                        heatInputW = status.heatInputW,
                        overallEfficiency = status.overallEfficiency,
                        rotatingAssemblyMassKg = status.rotatingAssemblyMassKg,
                        maxSupportedRotatingMassKg = status.maxSupportedRotatingMassKg,
                        failureReason = status.failureReason,
                        parts = buildPartsUi(plant, credits),
                    )
                }
            }
        }
    }

    fun upgrade(kind: PartKind) {
        val cost = when (kind) {
            PartKind.BOILER -> plant.boiler.upgradeCost()
            PartKind.PISTON -> plant.piston.upgradeCost()
            PartKind.FLYWHEEL -> plant.flywheel.upgradeCost()
            PartKind.ROTOR -> plant.rotor.upgradeCost()
            PartKind.FRAME -> plant.frame.upgradeCost()
        }
        if (credits < cost) return
        credits -= cost

        when (kind) {
            PartKind.BOILER -> plant.boiler = Boiler(plant.boiler.level + 1)
            PartKind.PISTON -> plant.piston = PistonAssembly(plant.piston.level + 1)
            PartKind.FLYWHEEL -> plant.flywheel = Flywheel(plant.flywheel.level + 1)
            PartKind.ROTOR -> plant.rotor = GeneratorRotor(plant.rotor.level + 1)
            PartKind.FRAME -> plant.frame = Frame(plant.frame.level + 1)
        }

        val status = plant.status()
        _uiState.update {
            it.copy(
                credits = credits,
                rotatingAssemblyMassKg = status.rotatingAssemblyMassKg,
                maxSupportedRotatingMassKg = status.maxSupportedRotatingMassKg,
                failureReason = status.failureReason,
                parts = buildPartsUi(plant, credits),
            )
        }
    }
}

private fun buildPartsUi(plant: SteamEnginePlant, credits: Double): List<PartUiState> = listOf(
    PartUiState(
        kind = PartKind.BOILER,
        label = "Boiler",
        level = plant.boiler.level,
        upgradeCost = plant.boiler.upgradeCost(),
        summary = "${plant.boiler.heatInputW.toInt()} W heat · ${(plant.boiler.maxPressurePa / 1000).toInt()} kPa max",
    ),
    PartUiState(
        kind = PartKind.PISTON,
        label = "Piston & Crank",
        level = plant.piston.level,
        upgradeCost = plant.piston.upgradeCost(),
        summary = "${(plant.piston.boreRadiusM * 2000).toInt()} mm bore · ${(plant.piston.crankRadiusM * 2000).toInt()} mm stroke",
    ),
    PartUiState(
        kind = PartKind.FLYWHEEL,
        label = "Flywheel",
        level = plant.flywheel.level,
        upgradeCost = plant.flywheel.upgradeCost(),
        summary = "${"%.2f".format(plant.flywheel.massKg)} kg · ${(plant.flywheel.radiusM * 100).toInt()} cm radius",
    ),
    PartUiState(
        kind = PartKind.ROTOR,
        label = "Generator Rotor",
        level = plant.rotor.level,
        upgradeCost = plant.rotor.upgradeCost(),
        summary = "${"%.2f".format(plant.rotor.massKg)} kg · ke=${"%.2f".format(plant.rotor.backEmfConstantVSPerRad)}",
    ),
    PartUiState(
        kind = PartKind.FRAME,
        label = "Frame & Bearings",
        level = plant.frame.level,
        upgradeCost = plant.frame.upgradeCost(),
        summary = "supports ${"%.2f".format(plant.frame.maxSupportedRotatingMassKg)} kg rotating",
    ),
)
