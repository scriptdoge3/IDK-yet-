package com.powerforge.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The water gauge glass every real boiler has: a vertical tube tapped into the boiler
 * so the operator can see the actual water line, not just a percentage readout.
 */
@Composable
fun SightGlass(
    waterLevelFraction: Float,
    modifier: Modifier = Modifier,
) {
    val animatedLevel by animateFloatAsState(waterLevelFraction.coerceIn(0f, 1f), label = "waterLevel")
    val isLow = waterLevelFraction < 0.2f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = modifier.size(width = 22.dp, height = 90.dp)) {
            val tubeInsetX = size.width * 0.18f
            val tubeWidth = size.width - tubeInsetX * 2
            val tubeTop = 4f
            val tubeBottom = size.height - 4f
            val tubeHeight = tubeBottom - tubeTop

            drawRoundRect(
                color = Color(0xFF0D0F12),
                topLeft = Offset(tubeInsetX, tubeTop),
                size = Size(tubeWidth, tubeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeWidth / 2f),
            )

            val waterTop = tubeBottom - tubeHeight * animatedLevel
            val waterColor = if (isLow) Color(0xFFE0483C) else Color(0xFF6FB7E0)
            drawRoundRect(
                color = waterColor.copy(alpha = 0.85f),
                topLeft = Offset(tubeInsetX, waterTop),
                size = Size(tubeWidth, tubeBottom - waterTop),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeWidth / 2f),
            )

            drawRoundRect(
                color = Color.White.copy(alpha = 0.15f),
                topLeft = Offset(tubeInsetX, tubeTop),
                size = Size(tubeWidth, tubeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeWidth / 2f),
                style = Stroke(width = 2f),
            )
        }
        Text(
            "Water",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}
