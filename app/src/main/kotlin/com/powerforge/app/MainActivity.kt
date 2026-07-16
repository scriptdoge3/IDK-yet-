package com.powerforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.powerforge.app.ui.PlantScreen
import com.powerforge.app.ui.theme.PowerForgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PowerForgeTheme {
                PlantScreen()
            }
        }
    }
}
