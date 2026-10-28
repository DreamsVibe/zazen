package com.zazen.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Every container role the app overrides also pins its matching "on" colour so
// text never falls back to a baseline M3 colour with poor contrast.
private val DarkColorScheme = darkColorScheme(
    primary = Saffron,
    onPrimary = SaffronOn,
    primaryContainer = SaffronContainer,
    onPrimaryContainer = SaffronOnContainer,
    secondary = Sand,
    onSecondary = SandOn,
    secondaryContainer = SandContainer,
    onSecondaryContainer = SandOnContainer,
    tertiary = Jade,
    onTertiary = JadeOn,
    tertiaryContainer = JadeContainer,
    onTertiaryContainer = JadeOnContainer,
    background = InkBackground,
    onBackground = Cream,
    surface = InkBackground,
    onSurface = Cream,
    surfaceVariant = InkVariant,
    onSurfaceVariant = CreamMuted,
    surfaceContainerLowest = InkContainerLowest,
    surfaceContainerLow = InkContainerLow,
    surfaceContainer = InkContainer,
    surfaceContainerHigh = InkContainerHigh,
    surfaceContainerHighest = InkContainerHighest,
    outline = InkOutline,
    outlineVariant = InkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = Ochre,
    onPrimary = Color.White,
    primaryContainer = OchreContainer,
    onPrimaryContainer = OchreOnContainer,
    secondary = Umber,
    onSecondary = Color.White,
    secondaryContainer = UmberContainer,
    onSecondaryContainer = UmberOnContainer,
    tertiary = Pine,
    onTertiary = Color.White,
    tertiaryContainer = PineContainer,
    onTertiaryContainer = PineOnContainer,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = PaperContainerLowest,
    surfaceContainerLow = PaperContainerLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
)

private val ZazenShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ZazenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZazenTypography,
        shapes = ZazenShapes,
        content = content,
    )
}
