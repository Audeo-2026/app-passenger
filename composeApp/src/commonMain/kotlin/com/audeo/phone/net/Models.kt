package com.audeo.phone.net

import kotlinx.serialization.json.JsonObject

data class Identity(val deviceId: String, val token: String, val userId: String, val name: String, val callName: String)

enum class CallStatus { RINGING, ACTIVE, ENDED }

/** 통화 하나. 상대방(peer)은 이 휴대폰 기준입니다. */
data class CallInfo(
    val id: String,
    val status: CallStatus,
    val rawStatus: String,
    val outgoing: Boolean,
    val peerUserId: String?,
    val peerName: String,
    val peerCallName: String,
    val analysisRequested: Boolean,
    val analysisOn: Boolean,
    /** 상대방의 AI 분석 동의: pending, granted, declined. */
    val peerConsent: String?,
    /** 내 AI 분석 동의: pending, granted, declined. */
    val myConsent: String?,
    val journeyId: String?,
) {
    /** 이 통화를 Audeo가 같이 들으려는지(요청했고 내가 거두지 않았는지). */
    val wantsAnalysis: Boolean get() = analysisRequested && myConsent != "declined"
}

enum class Source(val label: String) { CONVERSATION("대화"), LOOKUP("조회"), ESTIMATE("추정"), CONFIRM("확인 필요"), USER("직접 입력") }

data class Field(val key: String?, val label: String, val value: String?, val code: String?, val note: String?, val source: Source?, val updated: Boolean)

data class Reason(val text: String, val source: Source?, val label: String?)

data class ChangeRow(val label: String, val from: String, val to: String)

data class Option(val id: String, val label: String, val variant: String)

data class Figure(val label: String?, val value: String, val note: String?)

data class Proposal(
    val id: String,
    val journeyId: String,
    val kind: String,
    val status: String,
    val title: String,
    /** 탑승자에게 묻는 문장(question_by_role.passenger). */
    val question: String?,
    val context: String?,
    val reasons: List<Reason>,
    val rows: List<ChangeRow>,
    val options: List<Option>,
    val figure: Figure?,
    val footnote: String?,
    val approver: String?,
    val audience: List<String>,
    /** 이 제안을 만들 때의 여정 버전. 고를 때 같이 보내면 그사이 계획이 바뀌었는지 서버가 확인해요. */
    val baseVersion: Int?,
)

data class Place(val name: String, val lat: Double, val lng: Double)

data class Journey(
    val id: String,
    val status: String,
    val title: String,
    val fields: List<Field>,
    val summary: String?,
    val driverName: String?,
    val driverCallName: String?,
    val driverUserId: String?,
    val vehicle: String?,
    val meetPlace: Place?,
    val meetAtLabel: String?,
    val waitPlace: Place?,
    val version: Int,
    /** 위치 공유가 켜졌는지(계획·진행 중 여정). 켜져 있을 때만 차 위치를 주고 내 위치를 받아요. */
    val locationSharing: Boolean,
    /** 아직 결정되지 않은 제안 id. */
    val pendingProposalIds: List<String>,
) {
    val ended: Boolean get() = status == "completed" || status == "cancelled"

    /** 타고 오는 교통편(예: "KTX 101 · 서울역 14:30 도착"). 조회로 확인된 것(code)만. */
    val ride: Field? get() = fields.firstOrNull { it.key == "transport" && it.code != null && it.value != null }
}

/** 이 휴대폰의 위치. acc는 오차 반경(m). */
data class GeoPos(val lat: Double, val lng: Double, val acc: Double? = null)

/** 차 위치. atMillis는 그 위치를 잰 시각(epoch ms). */
data class CarPos(val lat: Double, val lng: Double, val atMillis: Long)

enum class LaneTone { VEHICLE, PASSENGER, LATE }

data class Lane(val who: String, val at: String, val note: String?, val tone: LaneTone)

data class Arrival(val kicker: String?, val title: String, val figure: String?, val figureLabel: String?)

data class Coordination(
    val start: String,
    val end: String,
    val now: String?,
    val ticks: List<String>,
    val lanes: List<Lane>,
    val verdict: String?,
    val nextAction: String?,
    val etaLabel: String?,
    val arrival: Arrival?,
)

sealed interface ServerEvent {
    val seq: Long

    data class CallIncoming(override val seq: Long, val call: CallInfo) : ServerEvent
    data class CallState(override val seq: Long, val call: CallInfo) : ServerEvent
    data class JourneyUpdated(override val seq: Long, val journey: Journey) : ServerEvent
    data class JourneyEnded(override val seq: Long, val journeyId: String, val status: String) : ServerEvent
    data class ProposalChanged(override val seq: Long, val proposal: Proposal) : ServerEvent
    data class CoordinationUpdated(override val seq: Long, val journeyId: String?, val coordination: Coordination) : ServerEvent
    data class Say(override val seq: Long, val text: String, val priority: String?) : ServerEvent

    /** 상대 기기의 WebRTC 신호. WebSocket으로만 오고 seq가 없습니다. */
    data class RtcSignal(override val seq: Long, val callId: String, val kind: String, val payload: JsonObject) : ServerEvent
    data class Other(override val seq: Long, val type: String) : ServerEvent
}

data class IceServerInfo(val urls: List<String>, val username: String?, val credential: String?)
