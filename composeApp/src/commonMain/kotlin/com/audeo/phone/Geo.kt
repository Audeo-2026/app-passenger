package com.audeo.phone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val RAD = PI / 180

/** 두 지점 사이 거리(m). 탑승자 웹 distance()와 같은 하버사인 공식. */
fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val dLat = (bLat - aLat) * RAD
    val dLng = (bLng - aLng) * RAD
    val h = sin(dLat / 2).let { it * it } + cos(aLat * RAD) * cos(bLat * RAD) * sin(dLng / 2).let { it * it }
    return 2 * 6_371_000 * asin(sqrt(h))
}

/** a에서 b를 향한 방위각(진북 기준 시계 방향, 0~360). */
fun bearingDeg(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val y = sin((bLng - aLng) * RAD) * cos(bLat * RAD)
    val x = cos(aLat * RAD) * sin(bLat * RAD) - sin(aLat * RAD) * cos(bLat * RAD) * cos((bLng - aLng) * RAD)
    return (atan2(y, x) / RAD + 360) % 360
}

/** 내가 보는 방향 기준 차의 위치(-180~180, 오른쪽이 +). */
fun sideText(rel: Double): String {
    val a = abs(rel)
    val side = if (rel > 0) "오른쪽" else "왼쪽"
    return when {
        a <= 20 -> "앞쪽이에요"
        a <= 60 -> "$side 앞"
        a <= 120 -> side
        a <= 160 -> "$side 뒤"
        else -> "뒤쪽이에요"
    }
}

private val COMPASS = listOf("북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽")

fun compassText(bearing: Double): String = COMPASS[(bearing / 45).roundToInt() % 8]

/** 거리 숫자와 단위. 1km 안은 5m 단위로, 그 밖은 0.1km 단위로. */
fun distanceParts(m: Double): Pair<String, String> =
    if (m < 1000) {
        max(5, (m / 5).roundToInt() * 5).toString() to "m"
    } else {
        val tenths = (m / 100).roundToInt()
        "${tenths / 10}.${tenths % 10}" to "km"
    }

/** 받침에 따라 조사를 붙입니다(민지가·준호가, 민정과·민수와). 한글이 아니면 받침 없는 쪽으로 씁니다. */
fun String.josa(withFinal: String, withoutFinal: String): String {
    val ch = trimEnd().lastOrNull() ?: return this
    val hasFinal = ch in '가'..'힣' && (ch - '가') % 28 != 0
    return this + if (hasFinal) withFinal else withoutFinal
}

@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
fun parseIsoMillis(s: String): Long? = runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull()
