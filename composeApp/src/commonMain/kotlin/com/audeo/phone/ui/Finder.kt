package com.audeo.phone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audeo.phone.FinderUi
import com.audeo.phone.distanceParts

private val Night = Color(0xFF0E1013)
private val KickerOff = Color(0xFFAAB4FF)
private val Faint = Color.White.copy(alpha = 0.75f)

/**
 * 차가 가까워지면 지도 대신 뜨는 나침반(iPhone '나의 찾기'처럼). 화살표가 차 쪽을 가리키고,
 * 차가 앞쪽에 오면 카드가 파랗게 바뀌어요. GPS 오차 안쪽이면 화살표 대신 퍼지는 원을 보여 줘요.
 */
@Composable
fun FinderCard(f: FinderUi) {
    val c = Audeo.colors
    val bg by animateColorAsState(if (f.aligned) c.brand else Night, tween(300))
    val (num, unit) = distanceParts(f.distanceM)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(bg).padding(start = Space.s4, end = Space.s4, top = 20.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(f.kicker, style = AudeoType.label, color = if (f.aligned) Color.White else KickerOff, modifier = Modifier.align(Alignment.Start))
        Box(Modifier.padding(vertical = 4.dp).size(220.dp), contentAlignment = Alignment.Center) {
            if (f.close) Rings() else Arrow(f.arrowAngle, f.hint)
        }
        if (f.close) {
            Text("바로 근처", fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, color = Color.White)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(num, fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(unit, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
        }
        Text(f.hint, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        f.carLine?.let { Text(it, style = AudeoType.bodySm, color = Faint, textAlign = TextAlign.Center) }
        if (f.notes.isNotEmpty()) Text(f.notes.joinToString(" · "), style = AudeoType.bodySm, color = Faint, textAlign = TextAlign.Center)
    }
}

/** 탑승자 웹과 같은 화살표 모양(100×100 안의 경로). */
@Composable
private fun Arrow(angle: Float, hint: String) {
    val a by animateFloatAsState(angle, tween(350, easing = LinearOutSlowInEasing))
    Canvas(Modifier.size(190.dp).graphicsLayer { rotationZ = a }.semantics { contentDescription = "차 방향: $hint" }) {
        val s = size.width / 100f
        val path = Path().apply {
            moveTo(50 * s, 6 * s)
            lineTo(84 * s, 74 * s)
            quadraticTo(86 * s, 79 * s, 81 * s, 77 * s)
            lineTo(50 * s, 62 * s)
            lineTo(19 * s, 77 * s)
            quadraticTo(14 * s, 79 * s, 16 * s, 74 * s)
            close()
        }
        drawPath(path, Color.White)
    }
}

/** 바로 근처일 때 퍼지는 원 두 개(0.8초 차이). */
@Composable
private fun Rings() {
    val t = rememberInfiniteTransition()
    listOf(0, 800).forEach { delayMs ->
        val p by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing), RepeatMode.Restart, StartOffset(delayMs)),
        )
        Box(
            Modifier.size(160.dp)
                .graphicsLayer {
                    val scale = 0.35f + 0.8f * p
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - p
                }
                .border(3.dp, Faint, CircleShape),
        )
    }
}
