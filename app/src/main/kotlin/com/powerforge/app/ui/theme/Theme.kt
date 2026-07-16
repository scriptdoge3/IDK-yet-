package com.powerforge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ember = Color(0xFFF5A623)
val Rust = Color(0xFFB0472B)
val SteelDark = Color(0xFF1B1F24)
val SteelMid = Color(0xFF2A2F36)
val SteelLight = Color(0xFFE7E9EC)
val Warning = Color(0xFFE0483C)
val Success = Color(0xFF52C97A)

private val PowerForgeDarkColors = darkColorScheme(
    primary = Ember,
    secondary = Rust,
    background = SteelDark,
    surface = SteelMid,
    onPrimary = SteelDark,
    onSecondary = SteelLight,
    onBackground = SteelLight,
    onSurface = SteelLight,
    error = Warning,
)

private val PowerForgeLightColors = lightColorScheme(
    primary = Rust,
    secondary = Ember,
    background = SteelLight,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = SteelDark,
    onBackground = SteelDark,
    onSurface = SteelDark,
    error = Warning,
)

@Composable
fun PowerForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) PowerForgeDarkColors else PowerForgeLightColors
    MaterialTheme(colorScheme = colors, content = content)
}
