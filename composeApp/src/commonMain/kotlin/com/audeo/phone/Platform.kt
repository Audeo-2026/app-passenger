package com.audeo.phone

import com.audeo.phone.net.GeoPos
import io.ktor.client.HttpClient

/** 앱 저장소(기기 토큰 등). Android는 SharedPreferences, iOS는 NSUserDefaults. */
expect object Prefs {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/** 플랫폼 HTTP 엔진(Android OkHttp, iOS Darwin)으로 만든 클라이언트. */
expect fun createHttpClient(): HttpClient

expect val platformName: String

enum class LocationProblem { DENIED, UNAVAILABLE }

/**
 * 이 휴대폰의 정밀 위치(GPS)와 나침반 방향. 탑승자 웹의 watchPosition(enableHighAccuracy) + deviceorientation과 같은 역할입니다.
 * 방향(heading)은 진북 기준 시계 방향 각도(0~360)이고, 나침반이 없는 기기에서는 오지 않아요.
 * 콜백은 메인 스레드에서 불립니다.
 */
expect object DeviceLocation {
    fun start(onFix: (GeoPos) -> Unit, onProblem: (LocationProblem) -> Unit, onHeading: (Double) -> Unit)
    fun stop()
}

/** 통화 음성 경로. 통화 중에는 지도와 나침반을 보면서 말하니 스피커로 시작합니다. */
expect object CallAudioRoute {
    fun begin(speaker: Boolean)
    fun setSpeaker(on: Boolean)
    fun end()
}
