package com.powerforge.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A physical throw switch/lever, not a Material toggle - a housing slot with a lever
 * that snaps up (on) or down (off), styled like something bolted to a control panel.
 */
@Composable
fun ToggleLever(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val leverPosition by animateFloatAsState(if (checked) 1f else 0f, label = "leverPosition")
    val onColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val housingColor = Color(0xFF15181C)
    val leverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = modifier
                .size(width = 34.dp, height = 56.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCheckedChange(!checked) },
        ) {
            val housingWidth = size.width * 0.6f
            val housingLeft = (size.width - housingWidth) / 2f
            val housingTop = size.height * 0.08f
            val housingHeight = size.height * 0.84f

            drawRoundRect(
                color = housingColor,
                topLeft = Offset(housingLeft, housingTop),
                size = androidx.compose.ui.geometry.Size(housingWidth, housingHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(housingWidth / 2f),
            )

            val leverRadius = housingWidth * 0.62f
            val travel = housingHeight - leverRadius * 2f
            val leverY = housingTop + housingHeight - leverRadius - travel * leverPosition
            val leverCenter = Offset(size.width / 2f, leverY)

            drawLine(
                color = leverColor,
                start = Offset(size.width / 2f, housingTop + housingHeight - leverRadius * 0.4f),
                end = leverCenter,
                strokeWidth = housingWidth * 0.28f,
            )
            drawCircle(color = if (checked) onColor else leverColor, radius = leverRadius, center = leverCenter)
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = leverRadius,
                center = leverCenter,
                style = Stroke(width = 2f),
            )
        }
        Text(
            if (checked) "ON" else "OFF",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (checked) onColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
