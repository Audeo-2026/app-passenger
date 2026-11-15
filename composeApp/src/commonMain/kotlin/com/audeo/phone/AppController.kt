package com.audeo.phone

import com.audeo.phone.call.CallSession
import com.audeo.phone.net.ApiError
import com.audeo.phone.net.AudeoApi
import com.audeo.phone.net.CallInfo
import com.audeo.phone.net.CallStatus
import com.audeo.phone.net.CarPos
import com.audeo.phone.net.GeoPos
import com.audeo.phone.net.IceServerInfo
import com.audeo.phone.net.Journey
import com.audeo.phone.net.Option
import com.audeo.phone.net.Proposal
import com.audeo.phone.net.Registration
import com.audeo.phone.net.ServerEvent
import com.audeo.phone.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Banner(val ok: Boolean, val text: String)

/** 차가 가까울 때 지도 대신 띄우는 나침반(iPhone '나의 찾기'처럼 화살표가 차 쪽을 가리켜요). */
data class FinderUi(
    val distanceM: Double,
    /** GPS 오차 안쪽이라 화살표 대신 '바로 근처'로 보여 줘요. */
    val close: Boolean,
    /** 차가 앞쪽(±20°)이거나 바로 근처면 카드가 파랗게 바뀌어요. */
    val aligned: Boolean,
    /** 화살표 각도. 359°→0°로 넘어갈 때 한 바퀴 돌지 않게 누적한 값이에요. */
    val arrowAngle: Float,
    val kicker: String,
    val hint: String,
    val carLine: String?,
    val notes: List<String>,
)

/**
 * 탑승자 화면 상태. 보여 주는 것은 탑승자 웹과 같이 네 가지뿐이에요:
 * 통화(수신·AI 분석 동의·통화 상태), 서버가 보낸 다이얼로그, 지도, 차가 가까우면 나침반.
 */
data class UiState(
    val registered: Boolean = false,
    val myName: String? = null,
    val online: Boolean = false,
    val call: CallInfo? = null,
    val callSeconds: Int = 0,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    /** WebRTC 연결 상태(new, connecting, connected, failed …). */
    val callAudio: String? = null,
    /** Audeo나 차가 탑승자에게 하는 말(agent.say, 데이터 채널 note). */
    val say: String? = null,
    /** 분석한 통화가 끝난 뒤 잠깐 보여 주는 문장. */
    val endedNote: String? = null,
    val journey: Journey? = null,
    val etaLabel: String? = null,
    val proposal: Proposal? = null,
    val me: GeoPos? = null,
    val car: CarPos? = null,
    val locNote: String? = null,
    val finder: FinderUi? = null,
    val banner: Banner? = null,
    val busy: Boolean = false,
)

/**
 * 탑승자 앱의 흐름(tools/web_passenger/index.html을 옮긴 것).
 * - 서버 이벤트(WebSocket)로 통화·픽업·제안·조율을 받습니다.
 * - 통화가 연결되면 WebRTC로 차와 음성을 잇고, 같은 연결의 데이터 채널로 내 정밀 위치를 보내요.
 * - 만날 곳이 정해지면 지도를 보여 주고, 차가 300m 안으로 오면 나침반으로 바꿔요.
 */
class AppController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api = AudeoApi()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var eventJob: Job? = null
    private var timerJob: Job? = null
    private var carJob: Job? = null
    private var carJobFor: String? = null
    private var locJob: Job? = null
    private var noteJob: Job? = null
    private var iceServers: List<IceServerInfo>? = null

    /** 마지막 통화 상대(픽업이 아직 없을 때 '운전자에게 전화'용). */
    private var lastPeer: Pair<String, String>? = null
    private var locating = false
    private var heading: Double? = null
    private var finderOn = false
    private var arrowAngle = 0f

    private val callSession = CallSession(
        scope = scope,
        sendSignal = { kind, payload ->
            val id = _state.value.call?.id
            if (id != null) runCatching { api.signal(id, kind, payload) }.onFailure { log("신호 전송 실패($kind): ${it.message}") }
        },
        onState = { s ->
            _state.update { it.copy(callAudio = s) }
            log("통화 음성: $s")
            if (s == "connected") CallAudioRoute.setSpeaker(_state.value.speaker)
        },
        onChannelOpen = ::onChannelOpen,
        onChannelClosed = { locJob?.cancel() },
        onPeerMessage = ::onPeerMessage,
        log = ::log,
    )

    init {
        val id = api.identity
        _state.update { it.copy(registered = id != null, myName = id?.name) }
        if (id != null) start()
    }

    // ---------- 등록·연결 ----------

    fun register(name: String, shortName: String, phone: String, invite: String) {
        if (name.isBlank()) return showBanner(false, "이름을 넣어 주세요")
        if (invite.isBlank()) return showBanner(false, "초대 코드를 넣어 주세요")
        _state.update { it.copy(busy = true) }
        scope.launch {
            try {
                val id = api.register(Registration(name.trim(), shortName.trim().ifEmpty { null }, phone.trim().ifEmpty { null }), invite.trim())
                _state.update { it.copy(registered = true, myName = id.name, busy = false) }
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                showBanner(false, "등록하지 못했어요: ${e.message}")
            }
        }
    }

    /** 지난 이벤트는 건너뛰고(끝난 통화가 다시 울리지 않게) 지금부터 받습니다. */
    private fun start() {
        scope.launch {
            val seq = runCatching { api.latestSeq() }.getOrElse { api.lastSeq }
            startEvents(seq)
            refreshJourney()
        }
    }

    private fun startEvents(fromSeq: Long) {
        eventJob?.cancel()
        eventJob = scope.launch {
            var after = fromSeq
            var failures = 0
            while (isActive) {
                try {
                    api.streamEvents(
                        afterSeq = after,
                        onOpen = {
                            failures = 0
                            _state.update { it.copy(online = true) }
                        },
                    ) { e ->
                        if (e.seq in 1..after) return@streamEvents // 다시 붙으며 또 온 지난 이벤트
                        // WebSocket은 엔진 스레드에서 읽힐 수 있어서, 화면 상태·위치·통화는 메인 스레드에서 다룹니다.
                        withContext(Dispatchers.Main) { handle(e) }
                        if (e.seq > after) {
                            after = e.seq
                            api.lastSeq = after
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiError && e.status == 401) {
                        api.signOut()
                        _state.update { UiState() }
                        return@launch
                    }
                    log("실시간 연결 끊김: ${e.message}")
                }
                failures++
                _state.update { it.copy(online = false) }
                delay(minOf(30_000L, 1_500L * failures))
            }
        }
    }

    // ---------- 이벤트 ----------

    private fun handle(e: ServerEvent) {
        when (e) {
            is ServerEvent.CallIncoming -> {
                e.call.peerUserId?.let { lastPeer = it to e.call.peerCallName }
                _state.update { it.copy(call = e.call) }
            }
            is ServerEvent.CallState -> onCallState(e.call)
            is ServerEvent.RtcSignal -> if (e.callId == _state.value.call?.id) callSession.onRemoteSignal(e.kind, e.payload)
            is ServerEvent.Say -> _state.update { it.copy(say = e.text) }
            is ServerEvent.ProposalChanged -> onProposal(e.proposal)
            is ServerEvent.JourneyUpdated -> setJourney(e.journey)
            is ServerEvent.JourneyEnded -> {
                val cur = _state.value.journey
                if (cur == null || cur.id == e.journeyId) {
                    _state.update { it.copy(proposal = null) }
                    setJourney(null)
                }
            }
            is ServerEvent.CoordinationUpdated -> {
                val j = _state.value.journey
                if (j == null || e.journeyId == null || e.journeyId == j.id) _state.update { it.copy(etaLabel = e.coordination.etaLabel) }
            }
            is ServerEvent.Other -> Unit
        }
    }

    private fun onCallState(call: CallInfo) {
        val cur = _state.value.call
        if (cur != null && cur.id != call.id && call.status == CallStatus.ENDED) return
        val prev = cur?.takeIf { it.id == call.id }?.status
        call.peerUserId?.let { lastPeer = it to call.peerCallName }
        when (call.status) {
            CallStatus.RINGING -> _state.update { it.copy(call = call) }
            CallStatus.ACTIVE -> {
                _state.update { it.copy(call = call) }
                if (prev != CallStatus.ACTIVE) {
                    startTimer()
                    startCallAudio(call)
                    startLocating() // 통화 중에는 내 위치를 운전자에게 보여 줘요
                }
            }
            CallStatus.ENDED -> {
                val analysed = prev == CallStatus.ACTIVE && call.analysisOn
                val missed = prev == CallStatus.RINGING && call.outgoing
                timerJob?.cancel()
                closeCall()
                _state.update {
                    it.copy(
                        call = null,
                        callAudio = null,
                        muted = false,
                        say = null,
                        endedNote = if (analysed) "통화가 끝났어요 · Audeo가 약속을 정리하고 있어요" else null,
                    )
                }
                if (analysed) {
                    noteJob?.cancel()
                    noteJob = scope.launch {
                        delay(8_000)
                        _state.update { it.copy(endedNote = null) }
                    }
                }
                if (missed) showBanner(false, "${call.peerCallName.josa("이", "가")} 받지 않았어요")
                scope.launch {
                    delay(3_000)
                    refreshJourney()
                }
                if (_state.value.journey?.meetPlace == null) stopLocating()
            }
        }
        val jid = call.journeyId
        if (jid != null && _state.value.journey?.id != jid) {
            scope.launch { runCatching { api.journey(jid) }.onSuccess(::setJourney) }
        }
    }

    /**
     * 다이얼로그: 받는 사람(audience)에 탑승자가 있는 제안만 보여 줍니다.
     * 고르는 버튼은 결정자가 탑승자(approver=passenger)일 때만 둬요(아니면 서버가 403으로 막습니다).
     */
    private fun onProposal(p: Proposal) {
        if (p.status != "pending") {
            if (_state.value.proposal?.id == p.id) _state.update { it.copy(proposal = null) }
            return
        }
        if ("passenger" !in p.audience) return
        _state.update { it.copy(proposal = p) }
    }

    // ---------- 픽업과 지도 ----------

    private suspend fun refreshJourney() {
        runCatching { api.journeys() }
            .onSuccess { list ->
                val j = list.firstOrNull { !it.ended }
                setJourney(j?.let { runCatching { api.journey(it.id) }.getOrDefault(it) })
            }
            .onFailure { log("픽업 정보를 못 불러왔어요: ${it.message}") }
    }

    private fun setJourney(j: Journey?) {
        val journey = j?.takeIf { !it.ended }
        val prevId = _state.value.journey?.id
        _state.update {
            if (journey == null) {
                it.copy(journey = null, car = null, etaLabel = null, proposal = null)
            } else {
                it.copy(journey = journey, car = if (prevId == journey.id) it.car else null)
            }
        }
        if (journey == null) {
            stopLocating()
        } else {
            if (journey.meetPlace != null) startLocating()
            if (_state.value.etaLabel == null && (journey.status == "planned" || journey.status == "active")) {
                scope.launch { runCatching { api.coordination(journey.id) }.getOrNull()?.etaLabel?.let { eta -> _state.update { it.copy(etaLabel = eta) } } }
            }
        }
        pollCar()
        updateFinder()
    }

    /** 계획이 정해진 픽업에서만 차 위치를 5초마다 물어요. 픽업이 끝나면 서버가 409로 위치 공유를 닫아요. */
    private fun pollCar() {
        val j = _state.value.journey
        val id = j?.takeIf { it.meetPlace != null }?.id
        if (id == carJobFor && carJob?.isActive == true) return
        carJob?.cancel()
        carJobFor = id
        if (id == null) return
        carJob = scope.launch {
            while (isActive) {
                try {
                    api.car(id)?.let { setCar(it, id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiError && e.status == 409) {
                        _state.update { it.copy(car = null) }
                        updateFinder()
                        return@launch
                    }
                }
                delay(5_000)
            }
        }
    }

    private fun setCar(car: CarPos, journeyId: String?) {
        if (journeyId != null && _state.value.journey?.id != journeyId) return
        _state.update { it.copy(car = car) }
        updateFinder()
    }

    // ---------- 내 위치·나침반 ----------

    private fun startLocating() {
        if (locating) return
        locating = true
        DeviceLocation.start(
            onFix = { p ->
                if (_state.value.me == null) log("내 위치: ${p.lat}, ${p.lng} ±${p.acc?.roundToInt()}m")
                _state.update { it.copy(me = p, locNote = null) }
                sendLoc()
                updateFinder()
            },
            onProblem = { problem ->
                log("위치 문제: $problem")
                val note = if (problem == LocationProblem.DENIED) "위치 권한이 없어 차 방향을 알려 줄 수 없어요" else "내 위치를 찾는 중이에요"
                _state.update { it.copy(locNote = note) }
                updateFinder()
            },
            onHeading = { h ->
                heading = h
                if (finderOn) updateFinder()
            },
        )
    }

    private fun stopLocating() {
        if (_state.value.call?.status == CallStatus.ACTIVE) return // 통화 중에는 위치 공유를 이어가요
        if (!locating) return
        locating = false
        DeviceLocation.stop()
        heading = null
        _state.update { it.copy(me = null) }
        updateFinder()
    }

    /** Android에서 위치 권한을 막 받았을 때 다시 시작합니다. */
    fun permissionsChanged() {
        if (!locating) return
        locating = false
        DeviceLocation.stop()
        startLocating()
    }

    /** 300m 안으로 오면 나침반, 350m 밖으로 멀어지면 다시 지도(경계에서 깜빡이지 않게). */
    private fun updateFinder() {
        val s = _state.value
        val meet = s.journey?.meetPlace
        val me = s.me
        val car = s.car
        val d = if (meet != null && me != null && car != null) distanceM(me.lat, me.lng, car.lat, car.lng) else null
        val wasOn = finderOn
        finderOn = d != null && d <= (if (finderOn) NEAR_M + 50 else NEAR_M)
        if (finderOn && !wasOn) log("차 찾기 나침반 켜짐 · ${d?.roundToInt()}m")
        if (!finderOn || d == null || me == null || car == null) {
            if (s.finder != null) _state.update { it.copy(finder = null) }
            return
        }
        val notes = ArrayList<String>()
        val age = nowMillis() - car.atMillis
        if (age > 60_000) notes += "차 위치가 ${age / 60_000}분 전 정보예요"
        val acc = me.acc ?: 0.0
        if (acc > 30) notes += "내 위치 오차가 ${acc.roundToInt()}m쯤이에요"
        val carLine = listOfNotNull(s.journey?.vehicle, meet?.name?.let { "$it 근처" }).joinToString(" · ").ifEmpty { null }
        val close = d <= max(HERE_M, min(acc, 40.0))
        val finder = if (close) {
            FinderUi(d, close = true, aligned = true, arrowAngle = arrowAngle, kicker = "차가 바로 옆에 있어요", hint = "주위를 둘러보세요", carLine = carLine, notes = notes)
        } else {
            val b = bearingDeg(me.lat, me.lng, car.lat, car.lng)
            val h = heading
            val rel = if (h == null) b else ((b - h + 540) % 360) - 180
            arrowAngle += ((((rel - arrowAngle) % 360) + 540) % 360 - 180).toFloat()
            if (h == null) notes.add(0, "나침반을 못 써서 화살표는 북쪽(위) 기준이에요")
            FinderUi(
                distanceM = d,
                close = false,
                aligned = h != null && abs(rel) <= 20,
                arrowAngle = arrowAngle,
                kicker = "차가 가까워요",
                hint = if (h == null) compassText(b) else sideText(rel),
                carLine = carLine,
                notes = notes,
            )
        }
        _state.update { it.copy(finder = finder) }
    }

    // ---------- 통화 ----------

    fun answer(consent: Boolean) = withCall("전화를 받지 못했어요") { onCallState(api.answer(it.id, consent)) }
    fun reject() = withCall("거절하지 못했어요") { onCallState(api.reject(it.id)) }

    fun hangup() = withCall("통화를 끝내지 못했어요") {
        if (it.status != CallStatus.ENDED) onCallState(api.endCall(it.id))
    }

    /** '운전자에게 전화'. 늦어지는 일 같은 건 따로 알리는 화면 없이 통화로 말해요(오디가 듣고 계획 변경을 물어요). */
    fun callDriver() {
        val id = driverId() ?: return showBanner(false, "운전자 정보가 없어요")
        act("전화를 걸지 못했어요") { onCallState(api.startCall(id, analysis = true)) }
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        callSession.setMuted(muted)
    }

    fun toggleSpeaker() {
        val on = !_state.value.speaker
        _state.update { it.copy(speaker = on) }
        CallAudioRoute.setSpeaker(on)
    }

    fun choose(p: Proposal, o: Option) = act("답을 보내지 못했어요") {
        api.decide(p.id, o.id)
        _state.update { if (it.proposal?.id == p.id) it.copy(proposal = null) else it }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    fun peerName(): String = _state.value.journey?.driverCallName ?: lastPeer?.second ?: "운전자"
    fun driverId(): String? = _state.value.journey?.driverUserId ?: lastPeer?.first

    private fun startCallAudio(call: CallInfo) {
        CallAudioRoute.begin(_state.value.speaker)
        scope.launch {
            val ice = iceServers ?: runCatching { api.rtcConfig() }.getOrElse {
                listOf(IceServerInfo(listOf("stun:stun.l.google.com:19302"), null, null))
            }.also { iceServers = it }
            if (_state.value.call?.id != call.id) return@launch
            callSession.start(ice, caller = call.outgoing)
        }
    }

    private fun closeCall() {
        locJob?.cancel()
        locSent = false
        callSession.close()
        CallAudioRoute.end()
    }

    // ---------- 데이터 채널: 내 위치를 차로, 차 위치·알림을 받기 ----------

    private fun onChannelOpen() {
        sendLoc()
        locJob?.cancel()
        locJob = scope.launch {
            while (isActive) {
                delay(2_000)
                sendLoc()
            }
        }
    }

    private fun sendLoc() {
        val p = _state.value.me ?: return
        val sent = callSession.send(buildJsonObject {
            put("t", "loc")
            put("lat", p.lat)
            put("lng", p.lng)
            p.acc?.let { put("acc", it) }
            put("at", nowMillis())
        })
        // 보내기 시작할 때와, 보내다가 막혔을 때만 남깁니다(채널이 열리기 전에는 원래 못 보내요).
        if (sent && !locSent) log("내 위치를 데이터 채널로 보내는 중")
        if (!sent && locSent) log("내 위치를 데이터 채널로 보내지 못했어요")
        locSent = sent
    }

    private var locSent = false

    private fun onPeerMessage(m: JsonObject) {
        when (m.str("t")) {
            "loc" -> {
                val lat = (m["lat"] as? JsonPrimitive)?.doubleOrNull ?: return
                val lng = (m["lng"] as? JsonPrimitive)?.doubleOrNull ?: return
                setCar(CarPos(lat, lng, nowMillis()), null)
            }
            "note" -> m.str("text")?.let { t ->
                _state.update { it.copy(say = t) }
                log("차 알림: $t")
            }
        }
    }

    // ---------- 공통 ----------

    private fun startTimer() {
        timerJob?.cancel()
        _state.update { it.copy(callSeconds = 0) }
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _state.update { it.copy(callSeconds = it.callSeconds + 1) }
            }
        }
    }

    private fun withCall(failure: String, block: suspend (CallInfo) -> Unit) {
        val call = _state.value.call ?: return
        act(failure) { block(call) }
    }

    private fun act(failure: String, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showBanner(false, "$failure: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun showBanner(ok: Boolean, text: String) {
        _state.update { it.copy(banner = Banner(ok, text)) }
        scope.launch {
            delay(5_000)
            _state.update { if (it.banner?.text == text) it.copy(banner = null) else it }
        }
    }

    private fun log(m: String) = println("Audeo: $m")

    private companion object {
        const val NEAR_M = 300.0
        const val HERE_M = 15.0
    }
}
