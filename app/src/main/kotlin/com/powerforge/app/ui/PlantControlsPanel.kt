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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powerforge.app.ControlsUiState
import kotlin.math.roundToInt

/**
 * The full operator control panel - a bank of physical valve wheels and throw
 * switches, always present exactly like the real machine always has these. Nothing
 * here is gated by research; the tree only levels parts.
 */
@Composable
fun PlantControlsPanel(
    controls: ControlsUiState,
    onThrottleChange: (Double) -> Unit,
    onCutoffChange: (Double) -> Unit,
    onFuelValveChange: (Double) -> Unit,
    onIgnitionChange: (Boolean) -> Unit,
    onFeedwaterValveChange: (Double) -> Unit,
    onSafetyValveChange: (Boolean) -> Unit,
    onExcitationChange: (Double) -> Unit,
    onClutchChange: (Boolean) -> Unit,
    onEmergencyBrakeChange: (Boolean) -> Unit,
    onAddOil: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Control panel", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

            SectionLabel("Combustion")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToggleLever("Ignition", controls.ignitionOn, onIgnitionChange)
                RotaryValveControl("Fuel valve", controls.fuelValveFraction, onFuelValveChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionLabel("Steam")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RotaryValveControl("Throttle", controls.throttleFraction, onThrottleChange)
                RotaryValveControl(
                    "Cutoff",
                    controls.cutoffFraction,
                    onCutoffChange,
                    valueRange = 0.05..0.98,
                )
                RotaryValveControl("Feedwater", controls.feedwaterValveFraction, onFeedwaterValveChange)
                ToggleLever("Relief valve", controls.safetyValveOpen, onSafetyValveChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionLabel("Drivetrain & electrical")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToggleLever("Clutch", controls.clutchEngaged, onClutchChange)
                RotaryValveControl(
                    "Excitation",
                    controls.excitationFraction,
                    onExcitationChange,
                    valueRange = 0.0..1.5,
                )
                ToggleLever("E-brake", controls.emergencyBrakeEngaged, onEmergencyBrakeChange, danger = true)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionLabel("Maintenance")
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lubrication: ${controls.lubricationPercent.roundToInt()}%")
                OutlinedButton(onClick = onAddOil) { Text("Add oil") }
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
