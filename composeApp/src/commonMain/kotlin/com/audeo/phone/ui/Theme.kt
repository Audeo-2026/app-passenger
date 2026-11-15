package com.audeo.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Audeo 디자인 시스템 색. 차량 앱과 같은 값이고, 휴대폰도 낮/밤을 시스템 설정에 맞춥니다. */
@Immutable
data class AudeoColors(
    val ground: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val dialogLine: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val brand: Color,
    val onBrand: Color,
    val brandInk: Color,
    val brandSoft: Color,
    val caution: Color,
    val cautionSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDanger: Color,
    val mapLand: Color,
    val isNight: Boolean,
)

val DayColors = AudeoColors(
    ground = Color(0xFFF2F3F5), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE7E9ED), line = Color(0xFFCDD2D9),
    dialogLine = Color(0xFF8D95A0), ink = Color(0xFF0E1013), inkMuted = Color(0xFF525A65), inkFaint = Color(0xFF7E8590),
    brand = Color(0xFF2347F5), onBrand = Color.White, brandInk = Color(0xFF1F3FD8), brandSoft = Color(0xFFE3E9FF),
    caution = Color(0xFF8A5600), cautionSoft = Color(0xFFFFF0D4), danger = Color(0xFFB8261A), dangerSoft = Color(0xFFFDE4E0),
    onDanger = Color.White, mapLand = Color(0xFFE6E8EC), isNight = false,
)

val NightColors = AudeoColors(
    ground = Color(0xFF0A0B0D), surface = Color(0xFF131519), surface2 = Color(0xFF1C1F24), line = Color(0xFF2B3038),
    dialogLine = Color(0xFF5B6370), ink = Color(0xFFF3F4F6), inkMuted = Color(0xFFA0A7B2), inkFaint = Color(0xFF6E7581),
    brand = Color(0xFF2347F5), onBrand = Color.White, brandInk = Color(0xFF8FA6FF), brandSoft = Color(0xFF141C45),
    caution = Color(0xFFF2B33D), cautionSoft = Color(0xFF2E2410), danger = Color(0xFFFF7A6E), dangerSoft = Color(0xFF36150F),
    onDanger = Color(0xFF2A0A06), mapLand = Color(0xFF0F1114), isNight = true,
)

private val LocalAudeoColors = staticCompositionLocalOf { DayColors }

object Audeo {
    val colors: AudeoColors
        @Composable @ReadOnlyComposable get() = LocalAudeoColors.current
}

/** 휴대폰 밀도 글자 크기(디자인 시스템 data-platform="phone"). */
object AudeoType {
    private val sans = FontFamily.Default
    val eta = TextStyle(fontFamily = sans, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
    val figure = TextStyle(fontFamily = sans, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
    val figureSm = TextStyle(fontFamily = sans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val display = TextStyle(fontFamily = sans, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = sans, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val bodyLg = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 23.sp)
    val bodySm = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
}

object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
}

object Radius {
    val sm = 6.dp
    val md = 12.dp
    val lg = 20.dp
}

object Dimens {
    val touchMin = 44.dp
    val controlH = 52.dp
    val iconMd = 22.dp
    val iconSm = 16.dp
    val border = 1.dp
}

@Composable
fun AudeoTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (night) NightColors else DayColors
    val scheme = if (night) {
        darkColorScheme(primary = colors.brand, onPrimary = colors.onBrand, background = colors.ground, surface = colors.surface, onSurface = colors.ink)
    } else {
        lightColorScheme(primary = colors.brand, onPrimary = colors.onBrand, background = colors.ground, surface = colors.surface, onSurface = colors.ink)
    }
    CompositionLocalProvider(LocalAudeoColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
