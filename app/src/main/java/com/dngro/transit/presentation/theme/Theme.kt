package com.dngro.transit.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Pastel MTA Color Palette
val MtaPastelBlue = Color(0xFF5C9CE6)     // MTA Bus / Line Blue
val MtaPastelGold = Color(0xFFFFD166)     // Subway / Express Gold
val MtaPastelGreen = Color(0xFF70C1B3)    // Crosstown / Arriving Green
val MtaPastelPurple = Color(0xFFB39DDB)   // Flushing Line Purple
val MtaPastelRed = Color(0xFFFF8A80)      // Crowded / Full
val MtaDarkSurface = Color(0xFF1E242E)    // Dark Navy Surface
val MtaDarkBackground = Color(0xFF000000) // OLED True Black

val TransitColors = Colors(
    primary = MtaPastelBlue,
    primaryVariant = Color(0xFF3B7CD3),
    secondary = MtaPastelGold,
    secondaryVariant = Color(0xFFE5B94C),
    background = MtaDarkBackground,
    surface = MtaDarkSurface,
    onPrimary = Color(0xFF0A1A2F),
    onSecondary = Color(0xFF2A2000),
    onBackground = Color(0xFFF0F4F8),
    onSurface = Color(0xFFE1E8ED),
    onError = Color(0xFFFF8A80),
)

@Composable
fun TransitTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colors = TransitColors,
        content = content,
    )
}
