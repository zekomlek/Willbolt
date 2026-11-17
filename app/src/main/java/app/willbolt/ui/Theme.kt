package app.willbolt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's colours: aqua like the logo's W, deep teal like its tile, and a warm cream for text on dark cards. */
object Palette {
    val Aqua = Color(0xFF76DBD1)
    val Deep = Color(0xFF12566C)
    val Cream = Color(0xFFF6EDD8)

    /** Deep teal, darker: background of the "on" card and of the full-screen cards. */
    val Abyss = Color(0xFF0A3240)
}

private val Dark = darkColorScheme(
    primary = Palette.Aqua,
    onPrimary = Color(0xFF00302B),
    primaryContainer = Palette.Deep,
    onPrimaryContainer = Color(0xFFD2F4EF),
    inversePrimary = Palette.Deep,
    secondary = Palette.Cream,
    onSecondary = Color(0xFF2F2717),
    secondaryContainer = Color(0xFF1B3B46),
    onSecondaryContainer = Palette.Cream,
    tertiary = Color(0xFFF0C77E),
    onTertiary = Color(0xFF3D2B00),
    tertiaryContainer = Color(0xFF4A3912),
    onTertiaryContainer = Color(0xFFFFE6B8),
    background = Color(0xFF071B23),
    onBackground = Color(0xFFF1EADB),
    surface = Color(0xFF071B23),
    onSurface = Color(0xFFF1EADB),
    surfaceVariant = Color(0xFF16323C),
    onSurfaceVariant = Color(0xFF9CB4B7),
    surfaceTint = Palette.Aqua,
    inverseSurface = Color(0xFFF1EADB),
    inverseOnSurface = Color(0xFF0D2731),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF5F150D),
    errorContainer = Color(0xFF6E2219),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFF4B6971),
    outlineVariant = Color(0xFF1F3C46),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1E3D48),
    surfaceDim = Color(0xFF071B23),
    surfaceContainerLowest = Color(0xFF041319),
    surfaceContainerLow = Color(0xFF0A222B),
    surfaceContainer = Color(0xFF0D2731),
    surfaceContainerHigh = Color(0xFF122E39),
    surfaceContainerHighest = Color(0xFF173642),
)

private val Light = lightColorScheme(
    primary = Palette.Deep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEFEA),
    onPrimaryContainer = Color(0xFF0B3443),
    inversePrimary = Palette.Aqua,
    secondary = Color(0xFF6B5E3E),
    onSecondary = Color.White,
    secondaryContainer = Palette.Cream,
    onSecondaryContainer = Color(0xFF3A3020),
    tertiary = Color(0xFF8B5D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2AE),
    onTertiaryContainer = Color(0xFF2C1C00),
    background = Color(0xFFFBF8F1),
    onBackground = Color(0xFF0E2A33),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF0E2A33),
    surfaceVariant = Color(0xFFEAE3D2),
    onSurfaceVariant = Color(0xFF52666B),
    surfaceTint = Palette.Deep,
    inverseSurface = Color(0xFF0E2A33),
    inverseOnSurface = Palette.Cream,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8C9B9D),
    outlineVariant = Color(0xFFDDD5C2),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFFDF8),
    surfaceDim = Color(0xFFE6DFCF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F3E9),
    surfaceContainer = Color(0xFFF4EEE0),
    surfaceContainerHigh = Color(0xFFEFE8D7),
    surfaceContainerHighest = Color(0xFFE9E1CD),
)

private val base = Typography()

private val SeaType = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val SeaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Willbolt's own colours in light and dark; deliberately not the wallpaper colours (Material You). */
@Composable
fun WillboltTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = SeaType,
        shapes = SeaShapes,
        content = content,
    )
}
