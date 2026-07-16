package com.powerforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powerforge.app.ControlsUiState
import kotlin.math.roundToInt

/**
 * Only shows up once at least one Operations research node has been unlocked, and
 * only shows the individual controls that have actually been researched - a fresh
 * plant runs exactly like it did before any of this existed (always-on, wide open).
 */
@Composable
fun PlantControlsPanel(
    controls: ControlsUiState,
    onThrottleChange: (Double) -> Unit,
    onFuelValveChange: (Double) -> Unit,
    onIgnitionChange: (Boolean) -> Unit,
    onGeneratorEngagedChange: (Boolean) -> Unit,
    onSafetyValveChange: (Boolean) -> Unit,
    onAddLubrication: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!controls.hasAnyControl) return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Controls", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

            if (controls.ignitionUnlocked) {
                SwitchRow("Ignition", controls.ignitionOn, onIgnitionChange)
            }
            if (controls.throttleUnlocked) {
                SliderRow("Throttle", controls.throttleFraction, onThrottleChange)
            }
            if (controls.throttleUnlocked) {
                SliderRow("Fuel valve", controls.fuelValveFraction, onFuelValveChange)
            }
            if (controls.generatorClutchUnlocked) {
                SwitchRow("Generator engaged", controls.generatorEngaged, onGeneratorEngagedChange)
            }
            if (controls.safetyValveUnlocked) {
                SwitchRow("Manual relief valve", controls.safetyValveOpen, onSafetyValveChange)
            }
            if (controls.lubricationUnlocked) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Lubrication: ${controls.lubricationPercent.roundToInt()}%")
                    androidx.compose.material3.TextButton(onClick = onAddLubrication) {
                        Text("Add oil")
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Double, onValueChange: (Double) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label)
            Text("${(value * 100).roundToInt()}%")
        }
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.toDouble()) })
    }
}
