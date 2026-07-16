package com.powerforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.powerforge.app.PartUiState
import com.powerforge.app.PlantUiState
import com.powerforge.app.PlantViewModel
import com.powerforge.core.physics.FailureReason
import kotlin.math.roundToInt

@Composable
fun PlantScreen(viewModel: PlantViewModel = viewModel(), modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val latestState = rememberUpdatedState(state)
    var visualMode by remember { mutableStateOf(EngineVisualMode.NORMAL) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(state)

            EngineVisualModeSelector(
                mode = visualMode,
                onModeChange = { visualMode = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            EngineVisual(
                mode = visualMode,
                rpmProvider = { latestState.value.rpm },
                crankAngleRadProvider = { latestState.value.crankAngleRad },
                cylinderPressurePa = state.cylinderPressurePa,
                cylinderTemperatureK = state.cylinderTemperatureK,
                boilerTemperatureK = state.boilerTemperatureK,
                boilerWaterLevelFraction = state.boilerWaterLevelFraction,
                boilerStressFraction = state.boilerStressFraction,
                flywheelStressFraction = state.flywheelStressFraction,
                windingStressFraction = state.windingStressFraction,
                rotorWindingTemperatureK = state.rotorWindingTemperatureK,
                flameActive = state.flameActive,
                generatorEngaged = state.controls.clutchEngaged && state.controls.circuitBreakerClosed,
                electricalPowerW = state.electricalPowerW,
                isFailing = state.isFailing,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            EngineVisualLegend(mode = visualMode, state = state)

            if (state.isDamaged) {
                DamageBanner(state, onRepair = viewModel::repair)
            } else if (state.isFailing) {
                WarningBanner(state)
            }

            GaugeCluster(state)
            SecondaryReadouts(state)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    PlantControlsPanel(
                        controls = state.controls,
                        onThrottleChange = viewModel::setThrottle,
                        onCutoffChange = viewModel::setCutoff,
                        onFuelValveChange = viewModel::setFuelValve,
                        onAirDamperChange = viewModel::setAirDamper,
                        onIgnitionChange = viewModel::setIgnition,
                        onFeedwaterValveChange = viewModel::setFeedwaterValve,
                        onSafetyValveChange = viewModel::setSafetyValveOpen,
                        onExcitationChange = viewModel::setExcitation,
                        onLoadRheostatChange = viewModel::setLoadRheostat,
                        onLubricatorFeedRateChange = viewModel::setLubricatorFeedRate,
                        onClutchChange = viewModel::setClutchEngaged,
                        onEmergencyBrakeChange = viewModel::setEmergencyBrake,
                        onCircuitBreakerChange = viewModel::setCircuitBreakerClosed,
                        onDrainCocksChange = viewModel::setDrainCocksOpen,
                        onBlowdownValveChange = viewModel::setBlowdownValveOpen,
                    )
                }
                item {
                    Text(
                        "Plant",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    )
                }
                items(state.parts, key = { it.kind }) { part ->
                    PartSummaryCard(part = part, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(state: PlantUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("PowerForge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${state.credits.roundToInt()} cr",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${state.researchPoints.roundToInt()} RP",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun DamageBanner(state: PlantUiState, onRepair: () -> Unit) {
    val message = when (state.failureReason) {
        FailureReason.FLYWHEEL_BURST -> "FLYWHEEL BURST: it was spun past its rated tip speed and let go."
        FailureReason.BOILER_RUPTURED -> "BOILER RUPTURED: pressure ran past its rated limit faster than relief could bleed it."
        FailureReason.BOILER_DRY_FIRE -> "BOILER FIRED DRY: it was fired with no water left in it."
        FailureReason.BEARING_SEIZED -> "BEARINGS SEIZED: they ran dry too long and galled solid."
        FailureReason.ROTOR_BURNOUT -> "ROTOR BURNED OUT: windings overheated from too much excitation/current."
        else -> "PLANT DAMAGED."
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRepair, modifier = Modifier.padding(top = 8.dp)) {
                Text("Repair (${state.repairCostCredits} cr)")
            }
        }
    }
}

@Composable
private fun WarningBanner(state: PlantUiState) {
    val message = when (state.failureReason) {
        FailureReason.STRUCTURAL_OVERLOAD ->
            "TOO HEAVY: the flywheel + rotor (${"%.2f".format(state.rotatingAssemblyMassKg)} kg) " +
                "exceed what the frame can carry (${"%.2f".format(state.maxSupportedRotatingMassKg)} kg). " +
                "Research the Frame & Bearings to support it."
        FailureReason.STALLED_INSUFFICIENT_TORQUE ->
            "Building steam pressure... the boiler hasn't reached enough pressure to overcome friction yet."
        FailureReason.IGNITION_OFF -> "Burner is shut off. Switch ignition on to build pressure again."
        else -> ""
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EngineVisualLegend(mode: EngineVisualMode, state: PlantUiState) {
    val text = when (mode) {
        EngineVisualMode.NORMAL ->
            "Steam ${(state.cylinderPressurePa / 1000).roundToInt()} kPa · Water ${(state.boilerWaterLevelFraction * 100).roundToInt()}%"
        EngineVisualMode.THERMAL ->
            "Boiler ${(state.boilerTemperatureK - 273.15).roundToInt()}°C · " +
                "Cylinder ${(state.cylinderTemperatureK - 273.15).roundToInt()}°C · " +
                "Windings ${(state.rotorWindingTemperatureK - 273.15).roundToInt()}°C"
        EngineVisualMode.STRESS ->
            "Flywheel ${(state.flywheelStressFraction * 100).roundToInt()}% of burst · " +
                "Boiler ${(state.boilerStressFraction * 100).roundToInt()}% of rupture · " +
                "Windings ${(state.windingStressFraction * 100).roundToInt()}% of max"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun GaugeCluster(state: PlantUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        GaugeDial(
            label = "Pressure",
            value = (state.boilerPressurePa / 1000.0).toFloat(),
            minValue = 0f,
            maxValue = 900f,
            unit = " kPa",
            dangerValue = 600f,
        )
        GaugeDial(
            label = "RPM",
            value = state.rpm.toFloat(),
            minValue = 0f,
            maxValue = 3000f,
            unit = "",
            dangerValue = 2500f,
        )
        GaugeDial(
            label = "Boiler",
            value = (state.boilerTemperatureK - 273.15).toFloat(),
            minValue = 0f,
            maxValue = 300f,
            unit = "°C",
            dangerValue = 220f,
        )
        SightGlass(waterLevelFraction = state.boilerWaterLevelFraction.toFloat())
    }
}

@Composable
private fun SecondaryReadouts(state: PlantUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatItem("Output", "${"%.1f".format(state.electricalPowerW)} W")
        StatItem("Efficiency", "${"%.1f".format(state.overallEfficiency * 100)}%")
        StatItem("Winding", "${(state.rotorWindingTemperatureK - 273.15).roundToInt()}°C")
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun PartSummaryCard(part: PartUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("${part.label}  Lv.${part.level}", fontWeight = FontWeight.SemiBold)
            Text(
                part.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
