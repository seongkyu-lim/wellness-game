package io.github.seongkyulim.wellnessgame.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 셀 애니메이션('애니') 스타일 토큰. 값은 iOS `ios/Shared/ThemePalette.swift`, 웹 `theme.css`, `docs/DESIGN.md`와 같다.
 * 라이트 = 크림 종이 + 하늘, 다크 = 밤하늘 + 네온 보라 그림자.
 */
@Immutable
data class WellnessColors(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val popShadow: Color,
    val track: Color,
    val fieldBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primary: Color,
    val dangerBackground: Color,
    val dangerText: Color,
    val sky: Color,
    val hill: Color,
    val hillDeep: Color,
    val isDark: Boolean,
) {
    // 라이트·다크 공통 채색(위 글자는 onPop)
    val onPop = Color(0xFF1F2340)
    val xp = Color(0xFF9BE15D)
    val yellow = Color(0xFFFFE066)
    val pink = Color(0xFFFFB3CF)
    val mint = Color(0xFFCFF3B5)
    val peach = Color(0xFFFFD9C7)
    val lilac = Color(0xFFE3D9FF)
    val sparklePink = Color(0xFFFF9EC4)
    val statStrength = Color(0xFFFF9A6C)
    val statVitality = Color(0xFF7DD484)
    val statDiscipline = Color(0xFF7FB8F7)
    val statRecovery = Color(0xFFB9A2F5)
    val kakao = Color(0xFFFEE500)
    val naver = Color(0xFF03C75A)
}

val LightWellnessColors = WellnessColors(
    background = Color(0xFFFFF7E8),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF1F2340),
    popShadow = Color(0xFF1F2340),
    track = Color(0xFFE6ECFA),
    fieldBorder = Color(0xFF1F2340),
    textPrimary = Color(0xFF1F2340),
    textSecondary = Color(0xFF4A5080),
    primary = Color(0xFF3F8F3A),
    dangerBackground = Color(0xFFFFD6D0),
    dangerText = Color(0xFF8A1F12),
    sky = Color(0xFFBFE6FF),
    hill = Color(0xFF9BDB6A),
    hillDeep = Color(0xFF7CC44E),
    isDark = false,
)

val DarkWellnessColors = WellnessColors(
    background = Color(0xFF14172B),
    surface = Color(0xFF262B4D),
    ink = Color(0xFF070914),
    popShadow = Color(0xFF6C5CE7),
    track = Color(0xFF3A4070),
    fieldBorder = Color(0xFF8A90C8),
    textPrimary = Color(0xFFF3F0FF),
    textSecondary = Color(0xFFB4B9E0),
    primary = Color(0xFF8ED65A),
    dangerBackground = Color(0xFF5A2230),
    dangerText = Color(0xFFFFD6D0),
    sky = Color(0xFF2B3470),
    hill = Color(0xFF3E7A4A),
    hillDeep = Color(0xFF2F6239),
    isDark = true,
)

/** 수치 토큰(docs/DESIGN.md §4). */
object WellnessMetrics {
    val line = 3.dp
    val thinLine = 2.5.dp
    val cardRadius = 22.dp
    val heroRadius = 24.dp
    val buttonRadius = 16.dp
    val chipRadius = 14.dp
    val cardShadow = 4.dp
    val heroShadow = 6.dp
    val sectionSpacing = 18.dp
    val minTouch = 44.dp
}

val LocalWellnessColors = staticCompositionLocalOf { LightWellnessColors }

private val Display = FontWeight.Black

private val WellnessTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = Display, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = Display, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun WellnessTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkWellnessColors else LightWellnessColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.onPop,
            secondary = colors.yellow, onSecondary = colors.onPop,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.surface, onSurface = colors.textPrimary,
            surfaceVariant = colors.track, onSurfaceVariant = colors.textSecondary,
            outline = colors.fieldBorder, error = colors.dangerText, errorContainer = colors.dangerBackground,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = Color.White,
            secondary = colors.yellow, onSecondary = colors.onPop,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.surface, onSurface = colors.textPrimary,
            surfaceVariant = colors.track, onSurfaceVariant = colors.textSecondary,
            outline = colors.fieldBorder, error = colors.dangerText, errorContainer = colors.dangerBackground,
        )
    }
    CompositionLocalProvider(LocalWellnessColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = WellnessTypography, content = content)
    }
}

object Wellness {
    val colors: WellnessColors
        @Composable get() = LocalWellnessColors.current
}
