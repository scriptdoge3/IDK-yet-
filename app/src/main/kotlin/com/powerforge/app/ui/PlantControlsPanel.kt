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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powerforge.app.ControlsUiState
import kotlin.math.roundToInt

/**
 * The full operator control panel - always present, exactly like the real machine
 * always has these. Nothing here is gated by research; the tree only levels parts.
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
            Text("Controls", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

            SectionLabel("Combustion")
            SwitchRow("Ignition", controls.ignitionOn, onIgnitionChange)
            SliderRow("Fuel valve", controls.fuelValveFraction, 0f..1f, onFuelValveChange)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("Steam")
            SliderRow("Throttle", controls.throttleFraction, 0f..1f, onThrottleChange)
            SliderRow("Cutoff", controls.cutoffFraction, 0.05f..0.98f, onCutoffChange)
            SliderRow("Feedwater valve", controls.feedwaterValveFraction, 0f..1f, onFeedwaterValveChange)
            SwitchRow("Manual relief valve", controls.safetyValveOpen, onSafetyValveChange)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("Drivetrain & electrical")
            SwitchRow("Clutch engaged", controls.clutchEngaged, onClutchChange)
            SliderRow("Field excitation", controls.excitationFraction, 0f..1.5f, onExcitationChange)
            SwitchRow("Emergency brake", controls.emergencyBrakeEngaged, onEmergencyBrakeChange, danger = true)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("Maintenance")
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lubrication: ${controls.lubricationPercent.roundToInt()}%")
                TextButton(onClick = onAddOil) { Text("Add oil") }
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
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, danger: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        if (danger) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.error),
            )
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onValueChange: (Double) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label)
            Text("${(value * 100).roundToInt()}%")
        }
        Slider(value = value.toFloat(), valueRange = range, onValueChange = { onValueChange(it.toDouble()) })
    }
}
