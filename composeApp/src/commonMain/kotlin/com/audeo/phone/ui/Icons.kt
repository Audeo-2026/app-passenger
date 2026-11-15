package com.audeo.phone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 차량 앱과 같은 24px 격자, 2px 선 아이콘. 버튼과 지도 표시에만 씁니다. */
enum class AudeoIcon(private val paths: List<String>) {
    Mic(listOf("M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")),
    MicOff(listOf("M15 9.5V6a3 3 0 0 0-5.6-1.5", "M9 9v3a3 3 0 0 0 4.6 2.5", "M5 11a7 7 0 0 0 11.5 5.4", "M12 18v3", "M4 4l16 16")),
    Phone(listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2")),
    PhoneEnd(listOf("M3.5 14.5c4.7-4 12.3-4 17 0l-1.8 2.6-3.7-1.3v-2.6c-2-.7-4-.7-6 0v2.6l-3.7 1.3z")),
    Check(listOf("M5 12.5l4.5 4.5L19 7.5")),
    Close(listOf("M6 6l12 12", "M18 6L6 18")),
    Navigate(listOf("M12 3l7 18-7-4-7 4z")),
    ArrowRight(listOf("M4 12h15", "M13 6l6 6-6 6")),
    Speaker(listOf("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6", "M18 6.5a7.5 7.5 0 0 1 0 11")),
    ;

    val vector: ImageVector by lazy {
        val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        builder.build()
    }
}
