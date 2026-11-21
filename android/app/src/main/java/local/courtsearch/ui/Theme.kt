package local.courtsearch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF1E3A5F)
val Paper = Color(0xFFFAFAF9)
val Stone = Color(0xFF1C1917)
val Muted = Color(0xFF78716C)
val Line = Color(0xFFE7E5E4)
val Amber = Color(0xFFB45309)
val Danger = Color(0xFFB91C1C)

private val Colors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Stone,
    surface = Color.White,
    onSurface = Stone,
    error = Danger,
)

@Composable
fun CourtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
