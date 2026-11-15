package com.audeo.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.audeo.phone.resources.Res
import com.audeo.phone.resources.map_t2_day
import com.audeo.phone.resources.map_t2_night
import org.jetbrains.compose.resources.imageResource
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 인천공항 2터미널 지도(차량 앱과 같은 OpenStreetMap 그림)를 픽업 지점 근처로 확대해 보여 주고,
 * 차가 오는 길·차 위치·만날 곳·나를 그립니다. 지도는 그림이라 실제 위치가 아니라 진행 정도(progress)로 그립니다.
 */
@Composable
fun MapHeader(progress: Float, pickupLabel: String?, etaLabel: String?, height: Dp = 300.dp) {
    val c = Audeo.colors
    val image = imageResource(if (c.isNight) Res.drawable.map_t2_night else Res.drawable.map_t2_day)
    val measurer = rememberTextMeasurer()
    val chipStyle = AudeoType.label.copy(color = c.ink)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        // 지도 그림 좌표(0~1)를 화면에 맞춥니다. 픽업 지점이 위쪽 가운데쯤 오도록 확대합니다.
        val scale = max(size.width / image.width, size.height / image.height) * 2.1f
        val w = image.width * scale
        val h = image.height * scale
        val focus = Offset(0.77f, 0.45f)
        val dx = size.width / 2 - focus.x * w
        val dy = size.height / 2 - focus.y * h
        fun at(p: Offset) = Offset(dx + p.x * w, dy + p.y * h)
        drawImage(image, dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))

        val pts = ROUTE.map(::at)
        val split = (progress.coerceIn(0f, 1f) * (pts.size - 1))
        val idx = split.toInt().coerceAtMost(pts.size - 2)
        val frac = split - idx
        val car = pts[idx] + (pts[idx + 1] - pts[idx]) * frac
        val done = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..idx) lineTo(pts[i].x, pts[i].y); lineTo(car.x, car.y) }
        val rest = Path().apply { moveTo(car.x, car.y); for (i in idx + 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        drawPath(done, c.inkFaint, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))))
        drawPath(rest, c.surface, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(rest, c.brandInk, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val pickup = pts.last()
        drawCircle(c.ink, 10.dp.toPx(), pickup)
        drawCircle(c.surface, 4.dp.toPx(), pickup)
        val me = at(Offset(0.8f, 0.2f))
        drawCircle(c.surface, 9.dp.toPx(), me)
        drawCircle(c.ink, 9.dp.toPx(), me, style = Stroke(3.dp.toPx()))
        drawCircle(c.surface, 15.dp.toPx(), car)
        drawCircle(c.brand, 13.dp.toPx(), car)

        fun chip(text: String, anchor: Offset, style: TextStyle = chipStyle) {
            val layout = measurer.measure(text, style)
            val pad = 6.dp.toPx()
            val tl = Offset(anchor.x - layout.size.width / 2f - pad, anchor.y - layout.size.height - pad * 2)
            drawRoundRect(c.surface, tl, androidx.compose.ui.geometry.Size(layout.size.width + pad * 2, layout.size.height + pad * 1.4f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawRoundRect(c.dialogLine, tl, androidx.compose.ui.geometry.Size(layout.size.width + pad * 2, layout.size.height + pad * 1.4f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawText(layout, topLeft = Offset(tl.x + pad, tl.y + pad * 0.7f))
        }
        pickupLabel?.let { chip(it, Offset(pickup.x, pickup.y - 12.dp.toPx())) }
        chip("나", Offset(me.x, me.y - 10.dp.toPx()))
        etaLabel?.let { chip(it, Offset(car.x, car.y - 16.dp.toPx()), chipStyle.copy(color = c.brandInk)) }
    }
}

/** 차량 앱 TestData.route와 같은 길(지도 그림 기준 0~1 좌표). 남쪽 도로에서 2터미널 3번 출구까지. */
private val ROUTE = listOf(
    Offset(0.74f, 1.04f), Offset(0.7581f, 1.0286f), Offset(0.7733f, 1.0137f), Offset(0.7872f, 0.9941f),
    Offset(0.7975f, 0.9741f), Offset(0.806f, 0.9498f), Offset(0.8108f, 0.9272f), Offset(0.815f, 0.8681f),
    Offset(0.8049f, 0.7587f), Offset(0.7568f, 0.4261f), Offset(0.7596f, 0.3993f), Offset(0.7848f, 0.3386f),
    Offset(0.7853f, 0.3f), Offset(0.7788f, 0.2801f), Offset(0.77f, 0.2709f), Offset(0.7585f, 0.2681f),
    Offset(0.74f, 0.27f),
)
