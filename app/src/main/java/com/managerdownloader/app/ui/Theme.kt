package com.managerdownloader.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF146BFF)
val Success = Color(0xFF17A673)
val Warning = Color(0xFFD98A21)
val Danger = Color(0xFFD94B5B)
val Violet = Color(0xFF8064D9)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF122033),
    surface = Color.White,
    onSurface = Color(0xFF122033),
    surfaceVariant = Color(0xFFEAF0F7),
    onSurfaceVariant = Color(0xFF718096),
    outline = Color(0xFFE1E8F0),
    error = Danger
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF69A0FF),
    onPrimary = Color(0xFF0F1724),
    background = Color(0xFF0F1724),
    onBackground = Color(0xFFF3F7FC),
    surface = Color(0xFF172235),
    onSurface = Color(0xFFF3F7FC),
    surfaceVariant = Color(0xFF223149),
    onSurfaceVariant = Color(0xFF93A5BC),
    outline = Color(0xFF2A3A50),
    error = Color(0xFFF07987)
)

@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
