package app.cockpitmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cockpitmanager.R
import app.cockpitmanager.data.ThemeMode

/** How much heavier "Bold text" draws everything: the same +300 Android's own Bold text setting adds. */
private const val BOLD_SHIFT = 300

/** The heaviest weight Bold text reaches, as with Android's setting on the app's original Lexend family. */
private const val BOLD_CAP = 700

/**
 * Lexend, the app's typeface (SIL Open Font License; see licenses/Lexend-OFL.txt).
 * One variable font file covers every weight. With [bold], each weight is
 * drawn heavier (Regular as Bold, Light as SemiBold, titles as Bold), matching
 * how the app looks under Android's Bold text setting, so the look no longer
 * depends on that setting (MainActivity switches the system adjustment off).
 */
fun lexend(bold: Boolean): FontFamily = FontFamily(
    (100..900 step 100).map { weight ->
        val drawn = if (bold) (weight + BOLD_SHIFT).coerceAtMost(BOLD_CAP) else weight
        Font(R.font.lexend, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(drawn)))
    },
)

val Lexend: FontFamily = lexend(bold = false)
val LexendBold: FontFamily = lexend(bold = true)

/** The light scheme: teal and cyan accents over the same cool slate neutrals as the other apps. */
val BrandLightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF3EC),
    onPrimaryContainer = Color(0xFF053B36),
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2EEF3),
    onSecondaryContainer = Color(0xFF07323C),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6C7),
    onTertiaryContainer = Color(0xFF4A2304),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE4E9F2),
    onSurfaceVariant = Color(0xFF4B5565),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF2F8),
    surfaceContainerHighest = Color(0xFFE6EBF3),
    outline = Color(0xFFB8C1CF),
    outlineVariant = Color(0xFFDDE3EC),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val BrandDarkColors = darkColorScheme(
    primary = Color(0xFF5EDBC8),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F5A52),
    onPrimaryContainer = Color(0xFFCCF3EC),
    secondary = Color(0xFF6CD3E8),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF14525F),
    onSecondaryContainer = Color(0xFFD2EEF3),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2304),
    tertiaryContainer = Color(0xFF6B3A0C),
    onTertiaryContainer = Color(0xFFFFE6C7),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5E9F0),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFA9B4C6),
    surfaceContainerLowest = Color(0xFF070C16),
    surfaceContainerLow = Color(0xFF0F1828),
    surfaceContainer = Color(0xFF131D30),
    surfaceContainerHigh = Color(0xFF1A2539),
    surfaceContainerHighest = Color(0xFF223047),
    outline = Color(0xFF3B4A63),
    outlineVariant = Color(0xFF263349),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0000),
    errorContainer = Color(0xFF6B1515),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Base = Typography()

/** Material's scale with the same weights as the other apps, all in Lexend. */
private val BaseTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Light),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val RegularTypography = BaseTypography.withFamily(Lexend)
private val BoldTypography = BaseTypography.withFamily(LexendBold)

fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

/** Softer, rounder corners throughout, as in the other apps. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colors for states: running, failed, warning, idle. Tuned per light/dark for contrast. */
@Immutable
data class StatusColors(val good: Color, val bad: Color, val warn: Color, val idle: Color, val info: Color)

private val LightStatus = StatusColors(
    good = Color(0xFF15803D), bad = Color(0xFFC62828), warn = Color(0xFFB45309), idle = Color(0xFF8A94A6), info = Color(0xFF2563EB),
)
private val DarkStatus = StatusColors(
    good = Color(0xFF4ADE80), bad = Color(0xFFFF8A80), warn = Color(0xFFFFB86B), idle = Color(0xFF6B7891), info = Color(0xFF8DB4FF),
)

val LocalIsDark = staticCompositionLocalOf { false }
val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** The gradient behind every screen: a faint wash of the accent at the top. */
val LocalBackdrop = staticCompositionLocalOf { Brush.verticalGradient(listOf(Color(0xFFE3F2EF), Color(0xFFF5F7FB))) }

object AppTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun CockpitTheme(darkTheme: Boolean, dynamicColor: Boolean = false, boldText: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val wallpaper = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        wallpaper && darkTheme -> dynamicDarkColorScheme(context)
        wallpaper -> dynamicLightColorScheme(context)
        darkTheme -> BrandDarkColors
        else -> BrandLightColors
    }
    val backdrop = when {
        wallpaper -> Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.35f).compositeOver(colors.background), colors.background))
        darkTheme -> Brush.verticalGradient(listOf(Color(0xFF0C1E27), Color(0xFF0B1220)))
        else -> Brush.verticalGradient(listOf(Color(0xFFE3F2EF), Color(0xFFF5F7FB)))
    }
    CompositionLocalProvider(
        LocalIsDark provides darkTheme,
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
        LocalBackdrop provides backdrop,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = if (boldText) BoldTypography else RegularTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
