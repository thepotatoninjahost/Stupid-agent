package com.nova.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NovaColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Border,
    error = Error,
)

@Composable
fun NOVATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NovaColorScheme,
        typography = Typography,
        content = content,
    )
}
