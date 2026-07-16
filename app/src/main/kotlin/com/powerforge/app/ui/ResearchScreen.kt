package com.powerforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powerforge.app.PlantViewModel
import com.powerforge.app.ResearchNodeUiState
import com.powerforge.core.research.ResearchBranch
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

            val branchOrder = listOf(
                ResearchBranch.OPERATIONS,
                ResearchBranch.BOILER,
                ResearchBranch.PISTON,
                ResearchBranch.FLYWHEEL,
                ResearchBranch.ROTOR,
                ResearchBranch.FRAME,
            )
            val grouped = state.research.groupBy { it.branch }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                branchOrder.forEach { branch ->
                    val nodes = grouped[branch].orEmpty()
                    if (nodes.isEmpty()) return@forEach
                    item(key = "header_${branch.name}") {
                        Text(
                            branchLabel(branch),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    items(nodes, key = { it.id }) { node ->
                        ResearchNodeRow(
                            node = node,
                            isLast = node == nodes.last(),
                            canAfford = state.researchPoints >= node.costRp,
                            onResearch = { viewModel.research(node.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun branchLabel(branch: ResearchBranch): String = when (branch) {
    ResearchBranch.OPERATIONS -> "Operations & Controls"
    ResearchBranch.BOILER -> "Boiler"
    ResearchBranch.PISTON -> "Piston & Crank"
    ResearchBranch.FLYWHEEL -> "Flywheel"
    ResearchBranch.ROTOR -> "Generator Rotor"
    ResearchBranch.FRAME -> "Frame & Bearings"
}

@Composable
private fun ResearchNodeRow(
    node: ResearchNodeUiState,
    isLast: Boolean,
    canAfford: Boolean,
    onResearch: () -> Unit,
) {
    val dotColor = when {
        node.isResearched -> MaterialTheme.colorScheme.primary
        node.isAvailable -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Box(modifier = Modifier.width(28.dp).fillMaxHeight()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                if (!isLast) {
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.35f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 4f,
                    )
                }
                drawCircle(color = dotColor, radius = 12f, center = Offset(cx, 28f))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(node.label, fontWeight = FontWeight.SemiBold)
                    if (!node.isResearched) {
                        Text("${node.costRp} RP", color = MaterialTheme.colorScheme.secondary)
                    }
                }
                Text(
                    node.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (node.prerequisiteLabels.isNotEmpty() && !node.isResearched) {
                    Text(
                        "Requires: ${node.prerequisiteLabels.joinToString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                when {
                    node.isResearched -> Text(
                        "Researched",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    node.isAvailable -> Button(
                        onClick = onResearch,
                        enabled = canAfford,
                        modifier = Modifier.padding(top = 6.dp),
                    ) { Text("Research") }
                    else -> Text(
                        "Locked",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
