package com.cystem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    background = Color(0xFF06070B),
    surface = Color(0xFF0B0D14),
    surfaceContainer = Color(0xFF111520),
    primary = Color(0xFF7C5CFC),
    secondary = Color(0xFF3DE3FF),
    tertiary = Color(0xFF8BFFB1),
)

private val Light = lightColorScheme(
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceContainer = Color(0xFFEEF1F8),
    primary = Color(0xFF5B3CD6),
    secondary = Color(0xFF006E86),
    tertiary = Color(0xFF0D6E34),
)

@Composable
fun CystemTheme(
    accentArgb: Long,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) Dark else Light
    val accent = Color(accentArgb.toULong())
    MaterialTheme(
        colorScheme = base.copy(
            primary = accent,
            primaryContainer = accent.copy(alpha = if (darkTheme) 0.20f else 0.12f),
        ),
        content = content,
    )
}
