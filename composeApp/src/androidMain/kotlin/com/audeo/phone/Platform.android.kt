package com.audeo.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.audeo.phone.net.GeoPos
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.math.abs

actual object Prefs {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("audeo_phone", Context.MODE_PRIVATE)
    }

    actual fun get(key: String): String? = prefs?.getString(key, null)

    actual fun set(key: String, value: String?) {
        prefs?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.apply()
    }
}

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp)

actual val platformName: String = "Android"

/** MainActivity가 앱 Context를 한 번 넣어 줍니다. */
object AndroidPlatform {
    internal var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        Prefs.init(context)
    }
}

/**
 * 위치는 LocationManager(GPS + 네트워크), 방향은 회전 벡터 센서로 구합니다(Google Play 서비스 없이).
 * 회전 벡터는 자북 기준이라 지금 위치의 편각을 더해 진북으로 맞춥니다(서울은 약 -9°).
 */
actual object DeviceLocation {
    private var manager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var sensors: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var best: Location? = null
    private var declination = 0f
    private var lastHeading: Double? = null

    @SuppressLint("MissingPermission")
    actual fun start(onFix: (GeoPos) -> Unit, onProblem: (LocationProblem) -> Unit, onHeading: (Double) -> Unit) {
        val ctx = AndroidPlatform.app ?: return onProblem(LocationProblem.UNAVAILABLE)
        stop()
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            onProblem(LocationProblem.DENIED)
        } else {
            val m = ctx.getSystemService(LocationManager::class.java)
            val listener = LocationListener { loc ->
                if (!isBetter(loc)) return@LocationListener
                best = loc
                declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time).declination
                onFix(GeoPos(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null))
            }
            val providers = buildList {
                if (fine) add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
            providers.forEach { p ->
                m.requestLocationUpdates(p, 2_000L, 0f, listener, Looper.getMainLooper())
                m.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) }
            }
            if (providers.isEmpty()) onProblem(LocationProblem.UNAVAILABLE)
            manager = m
            locationListener = listener
        }
        startHeading(ctx, onHeading)
    }

    actual fun stop() {
        locationListener?.let { l -> manager?.removeUpdates(l) }
        locationListener = null
        manager = null
        sensorListener?.let { l -> sensors?.unregisterListener(l) }
        sensorListener = null
        sensors = null
        best = null
        lastHeading = null
    }

    /** 새 위치가 지금 것보다 나은지: 10초 넘게 지났거나, 오차가 비슷하거나 더 작으면. */
    private fun isBetter(loc: Location): Boolean {
        val cur = best ?: return true
        if (loc.time - cur.time > 10_000) return true
        if (!loc.hasAccuracy()) return false
        return !cur.hasAccuracy() || loc.accuracy <= cur.accuracy * 1.5f
    }

    private fun startHeading(ctx: Context, onHeading: (Double) -> Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val r = FloatArray(9)
        val o = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                SensorManager.getOrientation(r, o)
                val screen = when (display?.rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val h = (Math.toDegrees(o[0].toDouble()) + declination + screen + 720) % 360
                val prev = lastHeading
                // 1° 넘게 바뀔 때만 알려서 화면을 덜 다시 그립니다.
                if (prev == null || abs(((h - prev + 540) % 360) - 180) >= 1.0) {
                    lastHeading = h
                    onHeading(h)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        sensors = sm
        sensorListener = listener
    }
}

actual object CallAudioRoute {
    private val audio: AudioManager? get() = AndroidPlatform.app?.getSystemService(AudioManager::class.java)

    actual fun begin(speaker: Boolean) {
        audio?.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    actual fun setSpeaker(on: Boolean) {
        val am = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    actual fun end() {
        val am = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }
}
