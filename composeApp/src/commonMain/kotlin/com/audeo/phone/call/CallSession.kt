package com.audeo.phone.call

import com.audeo.phone.net.IceServerInfo
import com.shepeliev.webrtckmp.DataChannel
import com.shepeliev.webrtckmp.DataChannelState
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onDataChannel
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Audeo 통화 음성(WebRTC, 음성만). 휴대폰과 차가 직접 연결되고 서버는 신호만 전달합니다.
 *
 * 신호 규칙(차량 앱·탑승자 웹과 같음): 통화가 연결되면 건 쪽이 offer를 보내고 받은 쪽이 answer를 돌려줍니다.
 * payload: offer/answer = {type, sdp}, ice = {candidate, sdpMid, sdpMLineIndex}.
 * 받은 신호는 순서대로 하나씩 처리합니다(마이크 준비 전에 offer가 와도 기다렸다가 처리).
 *
 * 같은 연결에 데이터 채널("audeo")을 엽니다. 건 쪽이 offer 전에 만들고, 받은 쪽은 onDataChannel로 받아요.
 * 휴대폰은 여기로 정밀 위치({"t":"loc"})를 보내고, 차는 차 위치({"t":"loc"})와 알림({"t":"note"})을 보냅니다.
 * 이 메시지는 서버를 거치지 않아요.
 */
class CallSession(
    private val scope: CoroutineScope,
    private val sendSignal: suspend (kind: String, payload: JsonObject) -> Unit,
    private val onState: (String) -> Unit,
    private val onChannelOpen: () -> Unit,
    private val onChannelClosed: () -> Unit,
    private val onPeerMessage: (JsonObject) -> Unit,
    private val log: (String) -> Unit,
) {
    private var pc: PeerConnection? = null
    private var stream: MediaStream? = null
    private var channel: DataChannel? = null
    private var remoteSet = false
    private val pendingIce = ArrayList<IceCandidate>()
    private val inbox = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    private val jobs = ArrayList<Job>()
    var active = false
        private set

    fun start(iceServers: List<IceServerInfo>, caller: Boolean) {
        if (active) return
        active = true
        jobs += scope.launch {
            val s = runCatching { MediaDevices.getUserMedia(audio = true, video = false) }.getOrElse {
                log("마이크를 쓸 수 없어요: ${it.message}")
                onState("failed")
                return@launch
            }
            stream = s
            val config = RtcConfiguration(
                iceServers = iceServers.map { IceServer(urls = it.urls, username = it.username.orEmpty(), password = it.credential.orEmpty()) },
            )
            val peer = PeerConnection(config)
            pc = peer
            s.tracks.forEach { peer.addTrack(it, s) }
            jobs += scope.launch {
                peer.onIceCandidate.collect { c ->
                    sendSignal("ice", buildJsonObject {
                        put("candidate", c.candidate)
                        put("sdpMid", c.sdpMid)
                        put("sdpMLineIndex", c.sdpMLineIndex)
                    })
                }
            }
            jobs += scope.launch { peer.onConnectionStateChange.collect { onState(it.name.lowercase()) } }
            jobs += scope.launch { peer.onTrack.collect { log("상대 목소리 연결") } }
            jobs += scope.launch { peer.onDataChannel.collect { useChannel(it) } }
            if (caller) {
                peer.createDataChannel("audeo")?.let(::useChannel)
                val offer = peer.createOffer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = false))
                peer.setLocalDescription(offer)
                sendSignal("offer", buildJsonObject { put("type", "offer"); put("sdp", offer.sdp) })
            }
            // 준비가 끝난 뒤부터 받은 신호를 차례로 처리합니다.
            for ((kind, payload) in inbox) handle(peer, kind, payload)
        }
    }

    fun onRemoteSignal(kind: String, payload: JsonObject) {
        inbox.trySend(kind to payload)
    }

    fun setMuted(muted: Boolean) {
        stream?.tracks?.forEach { it.enabled = !muted }
    }

    /** 데이터 채널로 상대 기기에 보냅니다. 채널이 열려 있지 않으면 false. */
    fun send(message: JsonObject): Boolean {
        val ch = channel ?: return false
        if (ch.readyState != DataChannelState.Open) return false
        return runCatching { ch.send(message.toString().encodeToByteArray()) }.getOrDefault(false)
    }

    fun close() {
        if (!active) return
        active = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { channel?.close() }
        channel = null
        runCatching { pc?.close() }
        pc = null
        stream?.tracks?.forEach { runCatching { it.stop() } }
        runCatching { stream?.release() }
        stream = null
        remoteSet = false
        pendingIce.clear()
        while (inbox.tryReceive().isSuccess) Unit
    }

    private fun useChannel(ch: DataChannel) {
        channel = ch
        jobs += scope.launch {
            ch.onOpen.collect {
                log("데이터 채널 열림")
                onChannelOpen()
            }
        }
        jobs += scope.launch {
            ch.onClose.collect {
                if (channel === ch) channel = null
                onChannelClosed()
            }
        }
        jobs += scope.launch {
            ch.onMessage.collect { bytes ->
                runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()?.let(onPeerMessage)
            }
        }
        // 받은 쪽은 채널이 이미 열린 채로 올 수 있어요(열림 알림을 놓치지 않게).
        if (ch.readyState == DataChannelState.Open) onChannelOpen()
    }

    private suspend fun handle(peer: PeerConnection, kind: String, payload: JsonObject) {
        runCatching {
            when (kind) {
                "offer" -> {
                    peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, payload.text("sdp")))
                    remoteSet = true
                    flushIce(peer)
                    val answer = peer.createAnswer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = false))
                    peer.setLocalDescription(answer)
                    sendSignal("answer", buildJsonObject { put("type", "answer"); put("sdp", answer.sdp) })
                }
                "answer" -> {
                    peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, payload.text("sdp")))
                    remoteSet = true
                    flushIce(peer)
                }
                "ice" -> {
                    val c = payload.text("candidate")
                    if (c.isBlank()) return
                    val candidate = IceCandidate(
                        sdpMid = payload.text("sdpMid").ifEmpty { "0" },
                        sdpMLineIndex = payload["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                        candidate = c,
                    )
                    if (remoteSet) peer.addIceCandidate(candidate) else pendingIce.add(candidate)
                }
                "hangup" -> onState("hangup")
            }
        }.onFailure { log("통화 신호 처리 실패($kind): ${it.message}") }
    }

    private suspend fun flushIce(peer: PeerConnection) {
        pendingIce.forEach { peer.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
}
