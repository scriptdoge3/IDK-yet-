package com.powerforge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powerforge.app.PlantViewModel
import com.powerforge.app.ResearchNodeUiState
import kotlin.math.roundToInt

@Composable
fun ResearchScreen(viewModel: PlantViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Research", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${state.researchPoints.roundToInt()} RP",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                "Every upgrade for every part happens here. Researching a node directly " +
                    "levels the part up - there's no separate purchase step.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.research, key = { it.partKind }) { node ->
                    ResearchBranchCard(
                        node = node,
                        canAfford = state.researchPoints >= node.nextCostRp,
                        onResearch = { node.nextNodeId?.let { viewModel.research(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResearchBranchCard(
    node: ResearchNodeUiState,
    canAfford: Boolean,
    onResearch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(node.label, fontWeight = FontWeight.SemiBold)
                Text("Lv.${node.currentLevel} / ${node.maxLevel}")
            }
            LinearProgressIndicator(
                progress = { node.currentLevel.toFloat() / node.maxLevel.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(6.dp),
            )

            if (node.isMaxed) {
                Text(
                    "Fully researched.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    node.nextDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Button(
                    onClick = onResearch,
                    enabled = canAfford,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Research ${node.nextLabel} (${node.nextCostRp} RP)")
                }
            }
        }
    }
}
