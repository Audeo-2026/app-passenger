package com.audeo.phone.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.ifEmpty { null }
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
internal fun JsonObject.objs(key: String): List<JsonObject> = arr(key).filterIsInstance<JsonObject>()
internal fun JsonObject.strings(key: String): List<String> = arr(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** 서버 JSON → 앱 모델. 차량 앱(com.audeo.app.server.Protocol)과 같은 규칙입니다. */
object Protocol {

    fun call(o: JsonObject, myUserId: String?): CallInfo {
        val caller = o.obj("caller") ?: o.obj("from")
        val callee = o.obj("callee") ?: o.obj("to")
        val outgoing = myUserId != null && caller?.str("user_id") == myUserId
        val peer = if (outgoing) callee else caller
        val peerId = peer?.str("user_id")
        val raw = o.str("status") ?: "ended"
        return CallInfo(
            id = o.str("id").orEmpty(),
            status = when (raw) {
                "ringing", "dialing", "pending" -> CallStatus.RINGING
                "active", "answered" -> CallStatus.ACTIVE
                else -> CallStatus.ENDED
            },
            rawStatus = raw,
            outgoing = outgoing,
            peerUserId = peerId,
            peerName = peer?.str("name") ?: "상대방",
            peerCallName = peer?.str("call_name") ?: peer?.str("name") ?: "상대방",
            analysisRequested = o.bool("analysis_requested"),
            analysisOn = o.bool("analysis_on"),
            peerConsent = o.objs("participants").firstOrNull { it.str("user_id") == peerId }?.str("consent"),
            journeyId = o.str("journey_id"),
        )
    }

    fun source(s: String?): Source? = when (s) {
        "conversation" -> Source.CONVERSATION
        "lookup" -> Source.LOOKUP
        "estimate" -> Source.ESTIMATE
        "confirm", "unconfirmed" -> Source.CONFIRM
        "user" -> Source.USER
        else -> null
    }

    private val missingLabels = mapOf(
        "person" to "만날 사람", "transport" to "교통편", "place" to "장소", "exit" to "출구", "meet_time" to "만남 시각", "luggage" to "짐",
    )

    fun journey(o: JsonObject): Journey {
        val plan = o.obj("plan") ?: o.obj("draft_plan")
        val fields = o.objs("fields").map { f ->
            Field(
                key = f.str("key"),
                label = f.str("label") ?: f.str("key").orEmpty(),
                value = f.str("value"),
                code = f.str("code"),
                note = f.str("note"),
                source = if (f.containsKey("confirmed") && !f.bool("confirmed")) Source.CONFIRM else source(f.str("source")),
                updated = f.bool("updated"),
            )
        }
        val known = fields.mapNotNull { it.key }.toSet()
        val missing = o.strings("missing").filter { it !in known }.mapNotNull { k ->
            missingLabels[k]?.let { Field(k, it, null, null, null, Source.CONFIRM, false) }
        }
        val driver = o.obj("driver")
        return Journey(
            id = o.str("id").orEmpty(),
            status = o.str("status") ?: "draft",
            title = o.str("title") ?: "픽업",
            fields = fields + missing,
            summary = o.str("summary"),
            driverName = driver?.str("name"),
            driverCallName = driver?.str("call_name"),
            driverUserId = driver?.str("user_id"),
            vehicle = driver?.str("vehicle"),
            meetPlace = plan?.obj("meet_place")?.let(::place),
            meetAtLabel = plan?.str("meet_at_label"),
            waitPlace = plan?.obj("wait_place")?.let(::place),
        )
    }

    /** GET /journeys/{id}/car 의 car: {lat, lng} 또는 {location: {lat, lng}}, observed_at(ISO). */
    fun car(o: JsonObject, nowMillis: Long, parseTime: (String) -> Long?): CarPos? {
        val loc = o.obj("location") ?: o
        val lat = loc.dbl("lat") ?: return null
        val lng = loc.dbl("lng") ?: return null
        return CarPos(lat, lng, o.str("observed_at")?.let(parseTime) ?: nowMillis)
    }

    private fun place(o: JsonObject): Place? {
        val lat = o.dbl("lat") ?: return null
        val lng = o.dbl("lng") ?: return null
        return Place(o.str("name") ?: o.str("address") ?: "만날 곳", lat, lng)
    }

    fun proposal(o: JsonObject): Proposal = Proposal(
        id = o.str("id").orEmpty(),
        journeyId = o.str("journey_id").orEmpty(),
        kind = o.str("kind") ?: "plan",
        status = o.str("status") ?: "pending",
        title = o.str("title").orEmpty(),
        question = o.obj("question_by_role")?.str("passenger") ?: o.str("question"),
        context = o.str("context"),
        reasons = o.objs("reasons").map { Reason(it.str("text").orEmpty(), source(it.str("source")), it.str("label")) },
        rows = o.objs("rows").map { ChangeRow(it.str("label").orEmpty(), it.str("from") ?: "-", it.str("to") ?: "-") },
        options = o.objs("options").map { Option(it.str("id").orEmpty(), it.str("label") ?: it.str("id").orEmpty(), it.str("variant") ?: "secondary") },
        figure = o.obj("figure")?.let { f -> f.str("value")?.let { Figure(f.str("label"), it, f.str("note")) } },
        footnote = o.str("footnote"),
        approver = o.str("approver"),
        audience = o.strings("audience"),
    )

    fun coordination(o: JsonObject): Coordination {
        val w = o.obj("window")
        val ticks = w?.strings("ticks").orEmpty()
        return Coordination(
            start = w?.str("start") ?: "00:00",
            end = w?.str("end") ?: w?.str("start") ?: "00:00",
            now = w?.str("now"),
            ticks = if (ticks.size <= 3) ticks else listOf(ticks.first(), ticks[ticks.size / 2], ticks.last()),
            lanes = o.objs("lanes").filter { !it.str("at").isNullOrBlank() }.map { l ->
                Lane(
                    who = l.str("who").orEmpty(),
                    at = l.str("at").orEmpty(),
                    note = l.str("note")?.let { if (l.bool("stale")) "$it · 오래된 정보" else it },
                    tone = when (l.str("tone")) {
                        "vehicle" -> LaneTone.VEHICLE
                        "late" -> LaneTone.LATE
                        else -> LaneTone.PASSENGER
                    },
                )
            },
            verdict = o.str("verdict"),
            nextAction = o.str("next_action"),
            etaLabel = o.str("eta_label"),
            arrival = o.obj("arrival")?.let { a -> Arrival(a.str("kicker"), a.str("title").orEmpty(), a.str("figure"), a.str("figure_label")) },
        )
    }

    fun event(o: JsonObject, myUserId: String?): ServerEvent {
        val seq = (o["seq"] as? JsonPrimitive)?.longOrNull ?: 0L
        val type = o.str("type").orEmpty()
        val d = o.obj("data") ?: JsonObject(emptyMap())
        return runCatching {
            when (type) {
                "call.incoming" -> ServerEvent.CallIncoming(seq, call(d, myUserId))
                "call.state" -> ServerEvent.CallState(seq, call(d, myUserId))
                "journey.updated" -> ServerEvent.JourneyUpdated(seq, journey(d))
                "journey.ended" -> ServerEvent.JourneyEnded(seq, d.str("journey_id") ?: o.str("journey_id").orEmpty(), d.str("status") ?: "completed")
                "proposal.created", "proposal.updated" -> ServerEvent.ProposalChanged(seq, proposal(d))
                "coordination.updated" -> ServerEvent.CoordinationUpdated(seq, o.str("journey_id"), coordination(d))
                "agent.say" -> if (d.str("role") == null || d.str("role") == "passenger") {
                    ServerEvent.Say(seq, d.str("text").orEmpty(), d.str("priority"))
                } else {
                    ServerEvent.Other(seq, type)
                }
                "rtc.signal" -> ServerEvent.RtcSignal(seq, d.str("call_id").orEmpty(), d.str("kind").orEmpty(), d.obj("payload") ?: JsonObject(emptyMap()))
                else -> ServerEvent.Other(seq, type)
            }
        }.getOrElse { ServerEvent.Other(seq, "$type(읽기 실패)") }
    }
}
