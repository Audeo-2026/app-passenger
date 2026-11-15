package com.audeo.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audeo.phone.net.ChangeRow
import com.audeo.phone.net.Field
import com.audeo.phone.net.Lane
import com.audeo.phone.net.LaneTone
import com.audeo.phone.net.Reason
import com.audeo.phone.net.Source

enum class Tone { BRAND, CAUTION, NEUTRAL }

enum class ButtonVariant { PRIMARY, SECONDARY, QUIET, DANGER }

fun variantOf(server: String) = when (server) {
    "primary" -> ButtonVariant.PRIMARY
    "quiet" -> ButtonVariant.QUIET
    "danger" -> ButtonVariant.DANGER
    else -> ButtonVariant.SECONDARY
}

/** 떠 있는 카드: 그림자 + 1dp 테두리(dialog-line). 모든 정보는 이 카드로 보여 줍니다. */
@Composable
fun Modifier.card(): Modifier {
    val c = Audeo.colors
    val shape = RoundedCornerShape(Radius.lg)
    return this
        .shadow(if (c.isNight) 0.dp else 8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
        .border(Dimens.border, c.dialogLine, shape)
        .clip(shape)
        .background(c.surface)
}

@Composable
fun AudeoButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.SECONDARY,
    icon: AudeoIcon? = null,
    enabled: Boolean = true,
    full: Boolean = false,
    description: String? = null,
) {
    val c = Audeo.colors
    val (bg, fg) = when {
        !enabled -> c.surface2 to c.inkFaint
        variant == ButtonVariant.PRIMARY -> c.brand to c.onBrand
        variant == ButtonVariant.DANGER -> c.danger to c.onDanger
        variant == ButtonVariant.QUIET -> Color.Transparent to c.brandInk
        else -> c.surface2 to c.ink
    }
    val iconOnly = text == null
    var m = modifier.height(Dimens.controlH)
    m = when {
        full -> m.fillMaxWidth()
        iconOnly -> m.width(Dimens.controlH)
        else -> m
    }
    Row(
        m.clip(if (iconOnly) CircleShape else RoundedCornerShape(Radius.md))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (iconOnly) 0.dp else Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it.vector, contentDescription = description ?: text, tint = fg, modifier = Modifier.size(Dimens.iconMd)) }
        text?.let { Text(it, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1) }
    }
}

/** 값의 출처. 각진 모서리, 글자만. 추정은 점선. */
@Composable
fun SourceTag(source: Source, label: String? = null) {
    val c = Audeo.colors
    val (bg, fg) = when (source) {
        Source.CONVERSATION, Source.USER -> c.surface2 to c.ink
        Source.LOOKUP -> c.brandSoft to c.brandInk
        Source.ESTIMATE -> Color.Transparent to c.inkMuted
        Source.CONFIRM -> c.cautionSoft to c.caution
    }
    var m = Modifier.height(26.dp).clip(RoundedCornerShape(Radius.sm)).background(bg)
    if (source == Source.ESTIMATE) {
        m = m.drawBehind {
            val s = 1.5.dp.toPx()
            drawRoundRect(
                color = c.inkMuted,
                topLeft = Offset(s / 2, s / 2),
                size = Size(size.width - s, size.height - s),
                cornerRadius = CornerRadius(Radius.sm.toPx()),
                style = Stroke(width = s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
    }
    Box(m.padding(horizontal = Space.s2), contentAlignment = Alignment.Center) {
        Text(label ?: source.label, style = AudeoType.label, color = fg, maxLines = 1)
    }
}

/** 다이얼로그 카드: kicker(톤 색) · 제목 · 보조 · 큰 숫자 · 근거 · 본문 · 버튼 · 각주. 헤더에 아이콘은 넣지 않습니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudeoDialog(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    kicker: String? = null,
    title: String? = null,
    subtitle: String? = null,
    status: String? = null,
    figureLabel: String? = null,
    figure: String? = null,
    figureNote: String? = null,
    reasons: List<Reason> = emptyList(),
    voice: String? = null,
    footnote: String? = null,
    actions: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Audeo.colors
    Column(modifier.fillMaxWidth().card().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        if (kicker != null || title != null || status != null) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    kicker?.let {
                        Text(
                            it,
                            style = AudeoType.label,
                            color = when (tone) {
                                Tone.BRAND -> c.brandInk
                                Tone.CAUTION -> c.caution
                                Tone.NEUTRAL -> c.inkMuted
                            },
                        )
                    }
                    title?.let { Text(it, style = AudeoType.title, color = c.ink) }
                    subtitle?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
                }
                status?.let { StatusPill(it) }
            }
        }
        if (figure != null) {
            Column {
                figureLabel?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
                Text(figure, style = AudeoType.eta, color = c.ink)
                figureNote?.let { Text(it, style = AudeoType.body, color = c.ink) }
            }
        }
        reasons.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                r.source?.let { SourceTag(it, r.label) }
                Text(r.text, style = AudeoType.body, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
        body?.invoke(this)
        voice?.let { VoiceLine(it) }
        if (actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) { actions() }
        }
        footnote?.let {
            HorizontalDivider(color = c.line)
            Text(it, style = AudeoType.bodySm, color = c.inkMuted)
        }
    }
}

@Composable
fun StatusPill(text: String) {
    val c = Audeo.colors
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(c.surface2).padding(horizontal = Space.s3),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AudeoType.label, color = c.inkMuted) }
}

/** Audeo가 하는 말. 4~6개 막대만 보여 주는 작은 파형과 굵은 따옴표 문장. */
@Composable
fun VoiceLine(text: String, who: String = "Audeo") {
    val c = Audeo.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface2).padding(Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.brand), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0.45f, 0.8f, 1f, 0.7f, 0.4f).forEach { h ->
                    Box(Modifier.width(3.dp).height((16 * h).dp).clip(RoundedCornerShape(2.dp)).background(c.onBrand))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(who, style = AudeoType.label, color = c.brandInk)
            Text("“$text”", style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        }
    }
}

/** 약속 항목 표. 값이 없으면 placeholder, 방금 바뀐 값은 파란 점. */
@Composable
fun FieldRows(fields: List<Field>) {
    val c = Audeo.colors
    Column {
        fields.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.width(76.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
                        if (f.updated) Box(Modifier.size(6.dp).clip(CircleShape).background(c.brand))
                        f.code?.let { Text(it, style = AudeoType.code, color = c.ink) }
                        Text(
                            f.value ?: "확인 필요",
                            style = AudeoType.body.copy(fontWeight = if (f.value != null) FontWeight.Medium else FontWeight.Normal),
                            color = when {
                                f.value == null -> c.inkFaint
                                f.updated -> c.brandInk
                                else -> c.ink
                            },
                        )
                    }
                    f.note?.let { Text(it, style = AudeoType.label, color = c.inkMuted) }
                }
                f.source?.let { SourceTag(it) }
            }
        }
    }
}

@Composable
fun ChangeRows(rows: List<ChangeRow>) {
    val c = Audeo.colors
    Column {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.width(76.dp))
                Text(r.from, style = AudeoType.body.copy(textDecoration = TextDecoration.LineThrough), color = c.inkMuted, modifier = Modifier.weight(1f))
                Icon(AudeoIcon.ArrowRight.vector, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.iconSm))
                Spacer(Modifier.width(Space.s2))
                Text(r.to, style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1.1f))
            }
        }
    }
}

/** 차와 나의 도착 시각을 같은 시간축에 놓습니다. */
@Composable
fun EtaLanes(start: String, end: String, now: String?, lanes: List<Lane>, ticks: List<String>, verdict: String?) {
    val c = Audeo.colors
    fun minutes(t: String?): Int? = t?.split(":")?.takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.times(60)?.plus(m.toIntOrNull() ?: 0) }
    val s = minutes(start) ?: 0
    val e = (minutes(end) ?: (s + 30)).coerceAtLeast(s + 1)
    fun pct(t: String?) = (((minutes(t) ?: s) - s).toFloat() / (e - s)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        lanes.forEach { lane ->
            val color = when (lane.tone) {
                LaneTone.VEHICLE -> c.brandInk
                LaneTone.LATE -> c.caution
                LaneTone.PASSENGER -> c.ink
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(92.dp)) {
                    Text(lane.who, style = AudeoType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = color)
                    lane.note?.let { Text(it, style = AudeoType.label, color = c.inkMuted, maxLines = 2) }
                }
                BoxWithConstraints(Modifier.weight(1f).height(40.dp)) {
                    val w = maxWidth
                    val p = pct(lane.at)
                    Box(Modifier.align(Alignment.BottomStart).padding(bottom = 7.dp).fillMaxWidth().height(2.dp).background(c.line))
                    Box(Modifier.align(Alignment.BottomStart).padding(bottom = 7.dp).width(w * p).height(3.dp).background(color))
                    Box(Modifier.align(Alignment.BottomStart).offset(x = w * p - 7.dp).padding(bottom = 1.dp).size(14.dp).clip(CircleShape).background(c.surface).border(3.dp, color, CircleShape))
                    Text(
                        lane.at,
                        style = AudeoType.figureSm,
                        color = color,
                        modifier = Modifier.align(Alignment.TopStart).offset(x = (w * p - 22.dp).coerceIn(0.dp, w - 44.dp)),
                    )
                }
            }
        }
        if (ticks.isNotEmpty()) {
            Row(Modifier.padding(start = 92.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ticks.forEach { Text(it, style = AudeoType.label, color = c.inkMuted) }
            }
        }
        verdict?.let { Text(it, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink) }
        if (now != null) Spacer(Modifier.height(0.dp))
    }
}

/** 통화 바: 상대, 상태, 시간, 음소거, 끊기. */
@Composable
fun CallBar(name: String, role: String, duration: String, muted: Boolean, onMute: () -> Unit, onEnd: () -> Unit) {
    val c = Audeo.colors
    Row(
        Modifier.fillMaxWidth().card().padding(Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1)
            Text("$role · $duration", style = AudeoType.label, color = c.inkMuted, maxLines = 1)
        }
        AudeoButton(null, onMute, icon = if (muted) AudeoIcon.MicOff else AudeoIcon.Mic, description = if (muted) "마이크 켜기" else "마이크 끄기")
        AudeoButton(null, onEnd, variant = ButtonVariant.DANGER, icon = AudeoIcon.PhoneEnd, description = "통화 끊기")
    }
}

@Composable
fun Avatar(name: String, size: Dp) {
    val c = Audeo.colors
    Box(Modifier.size(size).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = if (size > 60.dp) AudeoType.display else AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
    }
}

@Composable
fun BannerCard(ok: Boolean, text: String, onClose: () -> Unit) {
    val c = Audeo.colors
    Row(
        Modifier.fillMaxWidth().card().padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (ok) c.brand else c.danger), contentAlignment = Alignment.Center) {
            Icon(if (ok) AudeoIcon.Check.vector else AudeoIcon.Close.vector, null, tint = if (ok) c.onBrand else c.onDanger, modifier = Modifier.size(Dimens.iconSm))
        }
        Text(text, style = AudeoType.body, color = c.ink, modifier = Modifier.weight(1f))
        Box(Modifier.size(Dimens.touchMin).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(AudeoIcon.Close.vector, "닫기", tint = c.inkMuted, modifier = Modifier.size(Dimens.iconSm))
        }
    }
}

/** 한 줄 입력칸(등록 화면). */
@Composable
fun Labeled(label: String, content: @Composable () -> Unit) {
    val c = Audeo.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(label, style = AudeoType.label, color = c.inkMuted)
        content()
    }
}

@Composable
fun Modifier.inputBox(): Modifier {
    val c = Audeo.colors
    return this.fillMaxWidth().heightIn(min = Dimens.controlH).clip(RoundedCornerShape(Radius.md)).background(c.surface2).padding(horizontal = Space.s3, vertical = Space.s3)
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Canvas(Modifier.size(size)) { drawCircle(color) }
