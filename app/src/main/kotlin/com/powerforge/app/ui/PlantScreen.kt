package com.powerforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberUpdatedState
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

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(state)

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FlywheelVisual(
                    rpmProvider = { latestState.value.rpm },
                    isFailing = state.isFailing,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            if (state.isFailing) {
                WarningBanner(state)
            }

            StatsPanel(state)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    PlantControlsPanel(
                        controls = state.controls,
                        onThrottleChange = viewModel::setThrottle,
                        onFuelValveChange = viewModel::setFuelValve,
                        onIgnitionChange = viewModel::setIgnition,
                        onGeneratorEngagedChange = viewModel::setGeneratorEngaged,
                        onSafetyValveChange = viewModel::setSafetyValveOpen,
                        onAddLubrication = viewModel::addLubrication,
                    )
                }
                items(state.parts, key = { it.kind }) { part ->
                    PartUpgradeCard(
                        part = part,
                        canAfford = state.credits >= part.upgradeCost,
                        onUpgrade = { viewModel.upgrade(part.kind) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
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
private fun WarningBanner(state: PlantUiState) {
    val message = when (state.failureReason) {
        FailureReason.STRUCTURAL_OVERLOAD ->
            "TOO HEAVY: the flywheel + rotor (${"%.2f".format(state.rotatingAssemblyMassKg)} kg) " +
                "exceed what the frame can carry (${"%.2f".format(state.maxSupportedRotatingMassKg)} kg). " +
                "Upgrade the Frame & Bearings to support it."
        FailureReason.STALLED_INSUFFICIENT_TORQUE ->
            "Building steam pressure... the boiler hasn't reached enough pressure to overcome friction yet."
        FailureReason.IGNITION_OFF -> "Burner is shut off. Switch ignition on to build pressure again."
        FailureReason.NONE -> ""
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
private fun StatsPanel(state: PlantUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatItem("Output", "${"%.1f".format(state.electricalPowerW)} W")
        StatItem("RPM", "${state.rpm.roundToInt()}")
        StatItem("Boiler", "${(state.boilerTemperatureK - 273.15).roundToInt()}°C")
        StatItem("Pressure", "${(state.boilerPressurePa / 1000).roundToInt()} kPa")
        StatItem("Efficiency", "${"%.1f".format(state.overallEfficiency * 100)}%")
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
private fun PartUpgradeCard(
    part: PartUiState,
    canAfford: Boolean,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${part.label}  Lv.${part.level} / ${part.maxLevel}", fontWeight = FontWeight.SemiBold)
                Text(
                    part.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (part.isLevelCapped) {
                Text(
                    "Research required",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            } else {
                Button(onClick = onUpgrade, enabled = canAfford) {
                    Text("${part.upgradeCost} cr")
                }
            }
        }
    }
}
