package com.powerforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powerforge.app.ControlsUiState
import kotlin.math.roundToInt

/**
 * The full operator control panel - every control the plant has, all present at
 * once, exactly like the real machine always has these. Nothing here is gated by
 * research; the tree only levels parts. Every control has a real actuator behind
 * it (a slew-limited valve, an electrically-lagged rheostat, or a latched switch),
 * so the panel doesn't respond instantly - the mechanism has to actually move.
 */
@Composable
fun PlantControlsPanel(
    controls: ControlsUiState,
    onThrottleChange: (Double) -> Unit,
    onCutoffChange: (Double) -> Unit,
    onFuelValveChange: (Double) -> Unit,
    onAirDamperChange: (Double) -> Unit,
    onIgnitionChange: (Boolean) -> Unit,
    onFeedwaterValveChange: (Double) -> Unit,
    onSafetyValveChange: (Boolean) -> Unit,
    onExcitationChange: (Double) -> Unit,
    onLoadRheostatChange: (Double) -> Unit,
    onLubricatorFeedRateChange: (Double) -> Unit,
    onClutchChange: (Boolean) -> Unit,
    onEmergencyBrakeChange: (Boolean) -> Unit,
    onCircuitBreakerChange: (Boolean) -> Unit,
    onDrainCocksChange: (Boolean) -> Unit,
    onBlowdownValveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Control panel", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

            SectionLabel("Combustion")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToggleLever("Ignition", controls.ignitionOn, onIgnitionChange)
                RotaryValveControl("Fuel valve", controls.fuelValveFraction, onFuelValveChange)
                RotaryValveControl("Air damper", controls.airDamperFraction, onAirDamperChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionLabel("Steam")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RotaryValveControl("Throttle", controls.throttleFraction, onThrottleChange)
                RotaryValveControl("Cutoff", controls.cutoffFraction, onCutoffChange, valueRange = 0.05..0.98)
                RotaryValveControl("Feedwater", controls.feedwaterValveFraction, onFeedwaterValveChange)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToggleLever("Relief valve", controls.safetyValveOpen, onSafetyValveChange)
                ToggleLever("Drain cocks", controls.drainCocksOpen, onDrainCocksChange)
                ToggleLever("Blowdown", controls.blowdownValveOpen, onBlowdownValveChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionLabel("Drivetrain & electrical")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToggleLever("Clutch", controls.clutchEngaged, onClutchChange)
                ToggleLever("Breaker", controls.circuitBreakerClosed, onCircuitBreakerChange)
                ToggleLever("E-brake", controls.emergencyBrakeEngaged, onEmergencyBrakeChange, danger = true)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                RotaryValveControl("Excitation", controls.excitationFraction, onExcitationChange, valueRange = 0.0..1.5)
                RotaryValveControl(
                    "Load",
                    controls.loadRheostatOhm,
                    onLoadRheostatChange,
                    valueRange = 0.5..6.0,
                    valueText = "${"%.1f".format(controls.loadRheostatOhm)}Ω",
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionLabel("Maintenance")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RotaryValveControl("Lubricator", controls.lubricatorFeedRateFraction, onLubricatorFeedRateChange)
                MaintenanceReadout("Oil", controls.lubricationPercent)
                MaintenanceReadout("Scale", controls.boilerScalePercent, inverted = true)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** A quick numeric health readout (oil remaining, or scale built up) next to the controls that affect it. */
@Composable
private fun MaintenanceReadout(label: String, percent: Double, inverted: Boolean = false) {
    val healthy = if (inverted) percent < 40.0 else percent > 40.0
    Column {
        Text(
            "${percent.roundToInt()}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (healthy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
