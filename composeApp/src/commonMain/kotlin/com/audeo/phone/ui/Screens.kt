package com.audeo.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.audeo.phone.AppController
import com.audeo.phone.AudeoConfig
import com.audeo.phone.UiState
import com.audeo.phone.josa
import com.audeo.phone.net.CallStatus
import com.audeo.phone.net.Field
import com.audeo.phone.net.Journey
import com.audeo.phone.net.Proposal

/** 화면 바탕: 가운데 420dp 폭으로 세로로 쌓고, 넘치면 스크롤합니다(탑승자 웹의 main). */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s5),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            content = content,
        )
    }
}

private fun duration(seconds: Int) = "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"

// ---------- 등록(처음 한 번) ----------

@Composable
fun OnboardingScreen(s: UiState, app: AppController) {
    val c = Audeo.colors
    var name by remember { mutableStateOf(AudeoConfig.DEFAULT_NAME) }
    var short by remember { mutableStateOf(AudeoConfig.DEFAULT_SHORT_NAME) }
    var phone by remember { mutableStateOf(AudeoConfig.DEFAULT_PHONE) }
    var invite by remember { mutableStateOf(AudeoConfig.INVITE_CODE) }
    Page {
        Spacer(Modifier.padding(top = Space.s6))
        Text("Audeo", style = AudeoType.display, color = c.brandInk)
        Text("데리러 오는 사람과 통화하면 AI가 픽업 약속을 정리해 줘요", style = AudeoType.bodyLg, color = c.inkMuted)
        AudeoDialog(
            tone = Tone.BRAND,
            kicker = "시작하기",
            title = "이 휴대폰을 등록할게요",
            footnote = "같은 전화번호로 등록하면 같은 사람으로 묶어요.",
            actions = { AudeoButton("등록하기", { app.register(name, short, phone, invite) }, variant = ButtonVariant.PRIMARY, full = true, enabled = !s.busy) },
        ) {
            Input("이름", name) { name = it }
            Input("부를 이름(예: 민지)", short) { short = it }
            Input("전화번호", phone, KeyboardType.Phone) { phone = it }
            Input("초대 코드", invite) { invite = it }
        }
    }
}

@Composable
private fun Input(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val c = Audeo.colors
    Labeled(label) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = AudeoType.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.brand),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.inputBox(),
        )
    }
}

// ---------- 탑승자 화면 ----------

/**
 * 보여 주는 것은 네 가지뿐이에요: 통화(수신·AI 분석 동의·상태), 서버가 보낸 다이얼로그, 지도.
 * 차가 가까워지면 지도 대신 나침반이 떠서 차 쪽을 가리켜요.
 */
@Composable
fun MainScreen(s: UiState, app: AppController) {
    val c = Audeo.colors
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audeo", style = AudeoType.display, color = c.brandInk, modifier = Modifier.weight(1f))
            StatusPill(if (s.online) "연결됨" else "연결 중")
        }
        CallCard(s, app)
        s.proposal?.let { ProposalCard(it, s, app) }
        val j = s.journey
        when {
            s.finder != null -> FinderCard(s.finder)
            j?.meetPlace != null -> WhereCard(j, s)
        }
    }
}

/** 통화 카드: 기다림 → 수신(동의) → 거는 중 → 통화 중. 늦는다는 말 같은 건 '운전자에게 전화'로 해요. */
@Composable
private fun CallCard(s: UiState, app: AppController) {
    val call = s.call
    val peer = call?.peerCallName ?: app.peerName()
    when {
        call == null -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "통화",
            title = if (s.journey != null) "${peer.josa("이", "가")} 데리러 오고 있어요" else "전화를 기다리는 중",
            subtitle = s.endedNote ?: when {
                !s.online -> "서버에 다시 연결하는 중이에요"
                s.journey == null -> "${s.myName ?: ""}님 · 운전자가 전화하면 여기서 받아요"
                else -> null
            },
            voice = s.say,
            actions = if (s.journey != null && app.driverId() != null) {
                { AudeoButton("${peer}에게 전화", app::callDriver, variant = ButtonVariant.PRIMARY, icon = AudeoIcon.Phone, enabled = !s.busy) }
            } else {
                null
            },
        )

        // 상대가 Audeo와 같이 듣자고 건 전화: 동의를 묻습니다.
        call.status == CallStatus.RINGING && !call.outgoing && call.analysisRequested -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "전화가 왔어요",
            title = "${call.peerCallName} 전화예요",
            subtitle = "동의하면 Audeo가 통화를 같이 듣고 만날 시간과 장소를 정리해요. 통화가 끝나면 듣기를 멈춰요.",
            actions = {
                AudeoButton("동의하고 받기", { app.answer(true) }, variant = ButtonVariant.PRIMARY, icon = AudeoIcon.Phone, enabled = !s.busy)
                AudeoButton("그냥 받기", { app.answer(false) }, enabled = !s.busy)
                AudeoButton("거절", app::reject, variant = ButtonVariant.DANGER, enabled = !s.busy)
            },
        )

        // 상대가 AI 없이 건 전화: 동의를 묻지 않고 일반 통화로 받아요.
        call.status == CallStatus.RINGING && !call.outgoing -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "전화가 왔어요",
            title = "${call.peerCallName} 전화예요",
            subtitle = "일반 통화예요 · Audeo는 듣지 않아요",
            actions = {
                AudeoButton("받기", { app.answer(false) }, variant = ButtonVariant.PRIMARY, icon = AudeoIcon.Phone, enabled = !s.busy)
                AudeoButton("거절", app::reject, variant = ButtonVariant.DANGER, enabled = !s.busy)
            },
        )

        call.status == CallStatus.RINGING -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "통화",
            title = "${peer}에게 거는 중",
            subtitle = if (call.wantsAnalysis) "받으면 Audeo가 같이 들어요" else "일반 통화로 걸고 있어요 · Audeo는 듣지 않아요",
            actions = {
                if (call.wantsAnalysis) AudeoButton("AI 없이 걸기", app::callWithoutAi, enabled = !s.busy)
                AudeoButton("취소", app::hangup, variant = ButtonVariant.DANGER, icon = AudeoIcon.PhoneEnd, enabled = !s.busy)
            },
        )

        else -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "통화 중",
            title = "${peer.josa("과", "와")} 통화 중",
            subtitle = (if (call.analysisOn) "Audeo가 같이 듣고 약속을 정리해요" else "일반 통화예요 · Audeo는 듣지 않아요") +
                " · 통화 중에는 내 위치를 운전자에게 보여 줘요",
            status = when (s.callAudio) {
                "connected" -> duration(s.callSeconds)
                "failed", "disconnected" -> "음성 연결 끊김"
                else -> "음성 연결 중"
            },
            voice = s.say,
            actions = {
                AudeoButton(null, app::toggleMute, icon = if (s.muted) AudeoIcon.MicOff else AudeoIcon.Mic, description = if (s.muted) "마이크 켜기" else "마이크 끄기")
                AudeoButton(
                    null,
                    app::toggleSpeaker,
                    variant = if (s.speaker) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
                    icon = AudeoIcon.Speaker,
                    description = if (s.speaker) "스피커 끄기" else "스피커 켜기",
                )
                AudeoButton("통화 끊기", app::hangup, variant = ButtonVariant.DANGER, icon = AudeoIcon.PhoneEnd, enabled = !s.busy)
            },
        )
    }
}

/** 다이얼로그: 결정자가 탑승자인 제안만 버튼을 둬요. 운전자가 정하는 제안은 내용만 보여 줘요. */
@Composable
private fun ProposalCard(p: Proposal, s: UiState, app: AppController) {
    val mine = p.approver == "passenger"
    AudeoDialog(
        tone = if (mine) Tone.CAUTION else Tone.BRAND,
        kicker = if (mine) "골라 주세요" else "운전자가 확인하고 있어요",
        title = p.question ?: p.title,
        actions = if (mine && p.options.isNotEmpty()) {
            { p.options.forEach { o -> AudeoButton(o.label, { app.choose(p, o) }, variant = if (o.variant == "primary") ButtonVariant.PRIMARY else ButtonVariant.SECONDARY, enabled = !s.busy) } }
        } else {
            null
        },
    ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }
}

/** 지도: 만날 곳·차·내 위치. */
@Composable
private fun WhereCard(j: Journey, s: UiState) {
    val meet = j.meetPlace ?: return
    val bits = listOfNotNull(j.meetAtLabel?.let { "${it}에 만나요" }, s.etaLabel?.let { "차 도착까지 $it" }, s.locNote)
    AudeoDialog(
        tone = Tone.BRAND,
        kicker = "만날 곳",
        title = "${meet.name}에서 만나요",
        subtitle = bits.joinToString(" · ").ifEmpty { null },
        footnote = if (j.locationSharing) "픽업하는 동안 내 위치를 운전자와 공유해요 · 픽업이 끝나면 멈춰요" else null,
    ) {
        j.ride?.let { RideRow(it) }
        TileMap(meet, s.car, s.me, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp)))
    }
}

/** 타고 오는 열차(조회로 확인된 교통편). "KTX 101 · 서울역 14:30 도착" → 열차 번호를 굵게. */
@Composable
private fun RideRow(f: Field) {
    val c = Audeo.colors
    val parts = f.value.orEmpty().split('·').map { it.trim() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface2).padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("타고 오는 열차", style = AudeoType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Text(parts.first(), style = AudeoType.code, color = c.brandInk)
        parts.getOrNull(1)?.let { Text(it, style = AudeoType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1) }
    }
}
