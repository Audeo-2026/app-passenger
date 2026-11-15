package com.audeo.phone.net

import com.audeo.phone.AudeoConfig
import com.audeo.phone.Prefs
import com.audeo.phone.createHttpClient
import com.audeo.phone.isoNow
import com.audeo.phone.nowMillis
import com.audeo.phone.parseIsoMillis
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ApiError(val status: Int, val code: String?, message: String) : Exception(message)

data class Registration(val name: String, val shortName: String?, val phone: String?)

/**
 * Audeo 서버 v1(탑승자 기기). 초대 코드로 phone 기기를 등록하고, 받은 기기 토큰을 Bearer로 붙입니다.
 * 실시간 이벤트와 통화 신호(rtc.signal)는 WebSocket(/ws?token=…)으로 받습니다.
 * 탑승자 웹(tools/web_passenger)과 같은 API만 씁니다.
 */
class AudeoApi {
    private val base = AudeoConfig.BASE_URL.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val registerLock = Mutex()
    private val client = createHttpClient().config {
        install(WebSockets) { pingIntervalMillis = 20_000 }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    val identity: Identity?
        get() {
            val token = Prefs.get(K_TOKEN) ?: return null
            return Identity(Prefs.get(K_DEVICE).orEmpty(), token, Prefs.get(K_USER).orEmpty(), Prefs.get(K_NAME).orEmpty(), Prefs.get(K_CALL).orEmpty())
        }

    /** 마지막으로 받은 이벤트 번호. 서버에 다시 붙을 때 이 뒤부터 받습니다. */
    var lastSeq: Long
        get() = Prefs.get(K_SEQ)?.toLongOrNull() ?: 0L
        set(v) = Prefs.set(K_SEQ, v.toString())

    suspend fun register(r: Registration, invite: String = AudeoConfig.INVITE_CODE): Identity = registerLock.withLock {
        val body = buildJsonObject {
            put("invite_code", invite)
            put("kind", "phone")
            put("display_name", r.name)
            r.shortName?.takeIf { it.isNotBlank() }?.let { put("short_name", it) }
            r.phone?.takeIf { it.isNotBlank() }?.let { put("phone", it) }
        }
        val o = request(HttpMethod.Post, "devices", body, null)
        val user = o.obj("user")
        val id = Identity(o.str("device_id").orEmpty(), o.str("device_token").orEmpty(), user?.str("user_id").orEmpty(), user?.str("name").orEmpty(), user?.str("call_name").orEmpty())
        Prefs.set(K_TOKEN, id.token)
        Prefs.set(K_DEVICE, id.deviceId)
        Prefs.set(K_USER, id.userId)
        Prefs.set(K_NAME, id.name)
        Prefs.set(K_CALL, id.callName)
        Prefs.set(K_SEQ, null)
        id
    }

    fun signOut() = listOf(K_TOKEN, K_DEVICE, K_USER, K_NAME, K_CALL, K_SEQ).forEach { Prefs.set(it, null) }

    /** 지금 서버의 마지막 이벤트 번호. 앱을 켤 때 지난 이벤트(끝난 통화 등)를 다시 받지 않으려고 씁니다. */
    suspend fun latestSeq(): Long =
        (authed(HttpMethod.Get, "events?after_seq=0&wait_s=0")["last_seq"] as? JsonPrimitive)?.longOrNull ?: 0L

    /** 전화 걸기. 픽업 중에 거는 전화면 journeyId를 같이 보내 어느 약속 이야기인지 서버가 알게 해요. */
    suspend fun startCall(calleeId: String, analysis: Boolean, journeyId: String? = null): CallInfo = call(
        authed(HttpMethod.Post, "calls", buildJsonObject {
            put("callee_id", calleeId)
            put("analysis_requested", analysis)
            put("caller_role", "passenger")
            journeyId?.let { put("journey_id", it) }
        }),
    )

    suspend fun getCall(callId: String): CallInfo = call(authed(HttpMethod.Get, "calls/$callId"))

    /** 내 AI 분석 동의를 바꿔요(거는 중 'AI 없이 걸기'). */
    suspend fun consent(callId: String, granted: Boolean): CallInfo =
        call(authed(HttpMethod.Put, "calls/$callId/consent", buildJsonObject { put("granted", granted) }))

    suspend fun answer(callId: String, consent: Boolean): CallInfo =
        call(authed(HttpMethod.Post, "calls/$callId/answer", buildJsonObject { put("analysis_consent", consent) }))

    suspend fun reject(callId: String): CallInfo = call(authed(HttpMethod.Post, "calls/$callId/reject"))
    suspend fun endCall(callId: String): CallInfo = call(authed(HttpMethod.Post, "calls/$callId/end"))

    suspend fun signal(callId: String, kind: String, payload: JsonObject) {
        authed(HttpMethod.Post, "calls/$callId/signal", buildJsonObject {
            put("kind", kind)
            put("payload", payload)
        })
    }

    suspend fun rtcConfig(): List<IceServerInfo> = authed(HttpMethod.Get, "rtc/config").objs("ice_servers").map { o ->
        val urls = when (val u = o["urls"]) {
            is JsonPrimitive -> listOfNotNull(u.content)
            else -> o.strings("urls")
        }
        IceServerInfo(urls, o.str("username"), o.str("credential"))
    }

    suspend fun journeys(): List<Journey> = authed(HttpMethod.Get, "journeys").objs("journeys").map(Protocol::journey)
    suspend fun journey(id: String): Journey = Protocol.journey(authed(HttpMethod.Get, "journeys/$id"))

    suspend fun coordination(journeyId: String): Coordination? =
        authed(HttpMethod.Get, "journeys/$journeyId/coordination").obj("coordination")?.let(Protocol::coordination)

    /** 차 위치(운전자가 위치를 공유하는 동안만). 픽업이 끝나면 서버가 409로 닫습니다. */
    suspend fun car(journeyId: String): CarPos? =
        authed(HttpMethod.Get, "journeys/$journeyId/car").obj("car")?.let { Protocol.car(it, nowMillis(), ::parseIsoMillis) }

    /** 아직 결정되지 않은 제안. 앱을 켜기 전에 만들어진 제안도 보여 주려고 씁니다. */
    suspend fun proposals(journeyId: String): List<Proposal> =
        authed(HttpMethod.Get, "journeys/$journeyId/proposals?status=pending").objs("proposals").map(Protocol::proposal)

    /** 제안 고르기. baseVersion을 같이 보내면 그사이 계획이 바뀐 경우 서버가 409로 알려 줘요. */
    suspend fun decide(proposalId: String, optionId: String, baseVersion: Int?): Proposal =
        Protocol.proposal(
            authed(HttpMethod.Post, "proposals/$proposalId/decision", buildJsonObject {
                put("option_id", optionId)
                put("via", "button")
                baseVersion?.let { put("base_version", it) }
            }),
        )

    /**
     * 탑승자 상태 보고(정의서 2-①-1). 역할은 토큰으로 정해져요. 서버가 탑승자 위치를 알아야
     * 조율(누가 언제 도착하는지)에 탑승자 줄을 채울 수 있어요. clientSeq는 기기마다 늘 커져야 해요.
     */
    suspend fun reportStatus(journeyId: String, clientSeq: Long, pos: GeoPos, heading: Double?) {
        authed(HttpMethod.Post, "journeys/$journeyId/status", buildJsonObject {
            put("client_seq", clientSeq)
            put("observed_at", isoNow())
            put("location", buildJsonObject {
                put("lat", pos.lat)
                put("lng", pos.lng)
                heading?.let { put("heading", it) }
            })
        })
    }

    /** WebSocket을 못 쓸 때의 long-poll. 통화 신호(rtc.signal)는 WebSocket으로만 와요. */
    suspend fun events(afterSeq: Long, waitSeconds: Int): Pair<List<ServerEvent>, Long> {
        val o = authed(HttpMethod.Get, "events?after_seq=$afterSeq&wait_s=$waitSeconds", timeoutMs = (waitSeconds + 15) * 1000L)
        val me = identity?.userId
        val events = o.objs("events").map { Protocol.event(it, me) }
        val last = (o["last_seq"] as? JsonPrimitive)?.longOrNull ?: events.maxOfOrNull { it.seq } ?: afterSeq
        return events to last
    }

    /** WebSocket 이벤트. 연결이 끊기면 돌아오고, 실패는 예외로 알립니다. */
    suspend fun streamEvents(afterSeq: Long, onOpen: () -> Unit, onEvent: suspend (ServerEvent) -> Unit) {
        val token = identity?.token ?: throw ApiError(401, "no_device", "등록된 기기가 없어요")
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=$token&after_seq=$afterSeq"
        val me = identity?.userId
        client.webSocket(urlString = wsUrl) {
            onOpen()
            // 탑승자 웹처럼 20초마다 앱 수준 ping을 보내 서버가 연결을 살려 두게 합니다.
            val pinger = launch {
                while (isActive) {
                    delay(20_000)
                    send(Frame.Text("""{"type":"ping"}"""))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val o = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                    when (o.str("type")) {
                        "pong" -> Unit
                        "error" -> throw ApiError(0, "ws_error", "WebSocket: ${o.obj("data")?.str("message")}")
                        else -> onEvent(Protocol.event(o, me))
                    }
                }
            } finally {
                pinger.cancel()
            }
        }
    }

    private fun call(o: JsonObject) = Protocol.call(o, identity?.userId)

    private suspend fun authed(method: HttpMethod, path: String, body: JsonObject? = null, timeoutMs: Long? = null): JsonObject {
        val token = identity?.token ?: throw ApiError(401, "no_device", "등록된 기기가 없어요")
        return request(method, path, body, token, timeoutMs)
    }

    private suspend fun request(method: HttpMethod, path: String, body: JsonObject?, token: String?, timeoutMs: Long? = null): JsonObject {
        val res = client.request("$base/${path.trimStart('/')}") {
            this.method = method
            timeoutMs?.let { ms -> timeout { requestTimeoutMillis = ms } }
            header("Accept", "application/json")
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val text = res.bodyAsText()
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (res.status.isSuccess()) return o ?: JsonObject(emptyMap())
        val err = o?.get("error")
        val (code, message) = when (err) {
            is JsonObject -> err.str("code") to err.str("message")
            is JsonPrimitive -> err.content to null
            else -> null to null
        }
        throw ApiError(res.status.value, code, message ?: "서버 오류 ${res.status.value}")
    }

    private companion object {
        const val K_TOKEN = "audeo.token"
        const val K_DEVICE = "audeo.device"
        const val K_USER = "audeo.user"
        const val K_NAME = "audeo.name"
        const val K_CALL = "audeo.call_name"
        const val K_SEQ = "audeo.last_seq"
    }
}
