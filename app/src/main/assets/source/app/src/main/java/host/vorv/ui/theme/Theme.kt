package host.vorv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VorvColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDim,
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
fun VorvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VorvColorScheme,
        typography = Typography,
        content = content,
    )
}
