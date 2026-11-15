package com.audeo.phone

import com.audeo.phone.net.GeoPos
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.NSUserDefaults
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual object Prefs {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun get(key: String): String? = defaults.stringForKey(key)

    actual fun set(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
    }
}

actual fun createHttpClient(): HttpClient = HttpClient(Darwin)

actual val platformName: String = "iOS"

/**
 * CLLocationManager 하나로 위치(최고 정확도)와 방향(진북, 없으면 자북)을 받습니다.
 * CLLocationManager는 만든 스레드의 런루프로 콜백을 보내서, 메인 스레드에서 만들고 다룹니다
 * (다른 스레드에서 만들면 위치가 와도 콜백이 불리지 않아요).
 */
@OptIn(ExperimentalForeignApi::class)
actual object DeviceLocation {
    private var managerOrNull: CLLocationManager? = null
    private val manager: CLLocationManager get() = managerOrNull ?: CLLocationManager().also { managerOrNull = it }
    private var onFix: ((GeoPos) -> Unit)? = null
    private var onProblem: ((LocationProblem) -> Unit)? = null
    private var onHeading: ((Double) -> Unit)? = null
    private var running = false

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            val (lat, lng) = loc.coordinate.useContents { latitude to longitude }
            onFix?.invoke(GeoPos(lat, lng, loc.horizontalAccuracy.takeIf { it >= 0 }))
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            // '아직 모름'(kCLErrorLocationUnknown)은 잠깐 생기는 오류라 계속 기다립니다(애플 문서 권장).
            if (didFailWithError.code == kCLErrorLocationUnknown) return
            onProblem?.invoke(if (didFailWithError.code == kCLErrorDenied) LocationProblem.DENIED else LocationProblem.UNAVAILABLE)
        }

        override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
            val h = if (didUpdateHeading.trueHeading >= 0) didUpdateHeading.trueHeading else didUpdateHeading.magneticHeading
            if (h >= 0) onHeading?.invoke(h)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (!running) return
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> onProblem?.invoke(LocationProblem.DENIED)
                kCLAuthorizationStatusNotDetermined -> Unit
                else -> manager.startUpdatingLocation()
            }
        }
    }

    actual fun start(onFix: (GeoPos) -> Unit, onProblem: (LocationProblem) -> Unit, onHeading: (Double) -> Unit) = onMain {
        this.onFix = onFix
        this.onProblem = onProblem
        this.onHeading = onHeading
        running = true
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> onProblem(LocationProblem.DENIED)
            else -> Unit
        }
        manager.startUpdatingLocation()
        if (CLLocationManager.headingAvailable()) {
            manager.headingFilter = 1.0
            manager.startUpdatingHeading()
        }
    }

    actual fun stop() = onMain {
        running = false
        managerOrNull?.stopUpdatingLocation()
        managerOrNull?.stopUpdatingHeading()
        onFix = null
        onProblem = null
        onHeading = null
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

/** WebRTC가 음성 세션(PlayAndRecord·VoiceChat)을 잡은 뒤에 스피커로 돌립니다. 연결될 때 한 번 더 부릅니다. */
@OptIn(ExperimentalForeignApi::class)
actual object CallAudioRoute {
    actual fun begin(speaker: Boolean) = setSpeaker(speaker)

    actual fun setSpeaker(on: Boolean) {
        runCatching {
            AVAudioSession.sharedInstance().overrideOutputAudioPort(if (on) AVAudioSessionPortOverrideSpeaker else AVAudioSessionPortOverrideNone, null)
        }
    }

    actual fun end() = Unit
}
