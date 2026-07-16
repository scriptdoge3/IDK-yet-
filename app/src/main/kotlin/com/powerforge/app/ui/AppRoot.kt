package com.powerforge.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.powerforge.app.PlantViewModel

private enum class AppTab(val label: String, val glyph: String) {
    PLANT("Plant", "⚡"),
    RESEARCH("Research", "🔬"),
}

@Composable
fun AppRoot(viewModel: PlantViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(AppTab.PLANT) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == AppTab.PLANT,
                    onClick = { selectedTab = AppTab.PLANT },
                    icon = { Text(AppTab.PLANT.glyph) },
                    label = { Text(AppTab.PLANT.label) },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.RESEARCH,
                    onClick = { selectedTab = AppTab.RESEARCH },
                    icon = { Text(AppTab.RESEARCH.glyph) },
                    label = { Text(AppTab.RESEARCH.label) },
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            AppTab.PLANT -> PlantScreen(viewModel, modifier = Modifier.padding(padding))
            AppTab.RESEARCH -> ResearchScreen(viewModel, modifier = Modifier.padding(padding))
        }
    }
}
