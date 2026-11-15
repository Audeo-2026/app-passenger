package com.audeo.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audeo.phone.createHttpClient
import com.audeo.phone.net.CarPos
import com.audeo.phone.net.GeoPos
import com.audeo.phone.net.Place
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 만날 곳·차·내 위치를 한 화면에 맞춰 보여 주는 지도(탑승자 웹의 MapLibre 지도 대신).
 * 키 없이 쓰는 OpenStreetMap 기본 타일을 받아 Canvas에 그리고, 그 위에 표시를 그립니다.
 * (예전 CARTO 타일은 키 없이 요청하면 'API KEY REQUIRED' 그림만 줘서 바꿨어요.)
 * 낮에는 색을 조금 빼서 표시가 잘 보이게 하고, 밤에는 같은 타일을 색 필터로 뒤집어 어둡게 그려요.
 * 보이는 점의 개수나 만날 곳이 바뀔 때만 화면을 다시 맞춰서, 위치가 조금씩 움직일 때 지도가 흔들리지 않아요.
 */
@Composable
fun TileMap(meet: Place, car: CarPos?, me: GeoPos?, modifier: Modifier = Modifier) {
    val c = Audeo.colors
    val night = c.isNight
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.background(c.mapLand)) {
        val density = LocalDensity.current.density
        val w = constraints.maxWidth.toDouble()
        val h = constraints.maxHeight.toDouble()
        val points = listOfNotNull(meet.lat to meet.lng, car?.let { it.lat to it.lng }, me?.let { it.lat to it.lng })
        val fitKey = "${points.size}:${meet.lat}:${meet.lng}:$w:$h"
        val view = remember(fitKey) { MapView.fit(points, w, h, density.toDouble()) }
        val tiles = remember(view, w, h) { view.visibleTiles(w, h) }
        LaunchedEffect(tiles) { tiles.forEach { t -> launch { TileCache.load(t) } } }
        val tileFilter = if (night) NIGHT_TILES else DAY_TILES

        Canvas(Modifier.fillMaxSize()) {
            tiles.forEach { t ->
                val img = TileCache.get(t) ?: return@forEach
                val x = t.x * view.tilePx - view.originX
                val y = t.y * view.tilePx - view.originY
                drawImage(
                    img,
                    dstOffset = IntOffset(floor(x).toInt(), floor(y).toInt()),
                    dstSize = IntSize(view.tilePx.roundToInt() + 1, view.tilePx.roundToInt() + 1),
                    colorFilter = tileFilter,
                )
            }
            me?.let { drawMe(view.screen(it.lat, it.lng), it, view) }
            val pinStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            car?.let { drawPin(measurer, view.screen(it.lat, it.lng), "차", pinStyle, Color(0xFF0E1013), border = night) }
            drawPin(measurer, view.screen(meet.lat, meet.lng), meet.name, pinStyle, c.brand, border = false)
        }
        Text(
            "© OpenStreetMap",
            style = AudeoType.label.copy(fontSize = 10.sp),
            color = c.inkMuted,
            modifier = Modifier.align(Alignment.BottomEnd).background(c.surface.copy(alpha = 0.75f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** 낮: 색을 조금 뺀 OpenStreetMap. */
private val DAY_TILES = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.45f) })

/**
 * 밤: 색 반전 + 색상 180° 회전(글자·도로 색은 그대로 알아보게) + 채도 0.25 + 밝기 0.82를 한 행렬로 합친 것.
 * 밝은 바탕(#F2EFE9)이 앱 지도 바탕(mapLand #0F1114)에 가까운 색이 돼요.
 */
private val NIGHT_TILES = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.014f, -0.733f, -0.074f, 0f, 209.1f,
            -0.219f, -0.528f, -0.074f, 0f, 209.1f,
            -0.219f, -0.733f, 0.131f, 0f, 209.1f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private fun DrawScope.drawMe(p: Offset, me: GeoPos, view: MapView) {
    val blue = Color(0xFF1A73E8)
    val accPx = me.acc?.let { (it / view.metersPerPx(me.lat)).toFloat() } ?: 0f
    if (accPx > 12.dp.toPx()) drawCircle(blue.copy(alpha = 0.14f), accPx, p)
    drawCircle(Color.White, 9.dp.toPx(), p)
    drawCircle(blue, 6.dp.toPx(), p)
}

/** 이름표: 아래 꼭짓점이 그 위치를 가리켜요(탑승자 웹의 .pin). */
private fun DrawScope.drawPin(measurer: TextMeasurer, p: Offset, text: String, style: TextStyle, bg: Color, border: Boolean) {
    val layout = measurer.measure(text, style, maxLines = 1)
    val padX = 10.dp.toPx()
    val padY = 4.dp.toPx()
    val tip = 6.dp.toPx()
    val size = Size(layout.size.width + padX * 2, layout.size.height + padY * 2)
    val tl = Offset(p.x - size.width / 2, p.y - tip - size.height)
    val radius = CornerRadius(size.height / 2)
    val pointer = Path().apply {
        moveTo(p.x - tip, tl.y + size.height - 1)
        lineTo(p.x, p.y)
        lineTo(p.x + tip, tl.y + size.height - 1)
        close()
    }
    drawRoundRect(Color.Black.copy(alpha = 0.18f), tl + Offset(0f, 2.dp.toPx()), size, radius)
    drawPath(pointer, bg)
    drawRoundRect(bg, tl, size, radius)
    if (border) drawRoundRect(Color.White.copy(alpha = 0.7f), tl, size, radius, style = Stroke(1.5.dp.toPx()))
    drawText(layout, topLeft = Offset(tl.x + padX, tl.y + padY))
}

data class TileId(val z: Int, val x: Int, val y: Int)

/** 웹 메르카토르 화면. zoom은 소수이고, 타일은 그 아래 정수 단계(tileZoom)를 늘려서 그립니다. */
private class MapView(val zoom: Double, val originX: Double, val originY: Double, val tileZoom: Int, val tilePx: Double, val basePx: Double) {

    fun screen(lat: Double, lng: Double) = Offset((worldX(lng, zoom, basePx) - originX).toFloat(), (worldY(lat, zoom, basePx) - originY).toFloat())

    fun metersPerPx(lat: Double) = 40_075_016.686 * cos(lat * PI / 180) / (basePx * 2.0.pow(zoom))

    fun visibleTiles(w: Double, h: Double): List<TileId> {
        val n = 1 shl tileZoom
        val x0 = floor(originX / tilePx).toInt()
        val x1 = floor((originX + w) / tilePx).toInt()
        val y0 = floor(originY / tilePx).toInt().coerceAtLeast(0)
        val y1 = floor((originY + h) / tilePx).toInt().coerceAtMost(n - 1)
        return buildList {
            for (y in y0..y1) for (x in x0..x1) add(TileId(tileZoom, x, y))
        }
    }

    companion object {
        /** 탑승자 웹 fitBounds와 같은 여백(위 64, 아래 32, 양옆 40)과 최대 확대(16). 점이 하나면 15. */
        fun fit(points: List<Pair<Double, Double>>, w: Double, h: Double, density: Double): MapView {
            val base = 256 * density
            val padL = 40 * density
            val padT = 64 * density
            val availW = max(1.0, w - 80 * density)
            val availH = max(1.0, h - 96 * density)
            val xs = points.map { worldX(it.second, 0.0, base) }
            val ys = points.map { worldY(it.first, 0.0, base) }
            val zoom = if (points.size <= 1) {
                15.0
            } else {
                val zx = log2(availW / max(xs.max() - xs.min(), 1e-9))
                val zy = log2(availH / max(ys.max() - ys.min(), 1e-9))
                min(16.0, min(zx, zy)).coerceAtLeast(3.0)
            }
            val k = 2.0.pow(zoom)
            val cx = (xs.min() + xs.max()) / 2 * k
            val cy = (ys.min() + ys.max()) / 2 * k
            val tileZoom = floor(zoom).toInt().coerceIn(0, 19)
            return MapView(zoom, cx - (padL + availW / 2), cy - (padT + availH / 2), tileZoom, base * 2.0.pow(zoom - tileZoom), base)
        }

        fun worldX(lng: Double, zoom: Double, base: Double) = (lng + 180) / 360 * base * 2.0.pow(zoom)

        fun worldY(lat: Double, zoom: Double, base: Double): Double {
            val s = sin(lat * PI / 180).coerceIn(-0.9999, 0.9999)
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * base * 2.0.pow(zoom)
        }
    }
}

/** 받은 타일을 기억해 둡니다(최근 160장). 상태 맵이라 타일이 도착하면 지도가 다시 그려져요. */
private object TileCache {
    private val client = createHttpClient().config {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        expectSuccess = false
    }
    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val order = ArrayDeque<String>()
    private val loading = HashSet<String>()

    private fun key(t: TileId) = "${t.z}/${t.x}/${t.y}"

    fun get(t: TileId): ImageBitmap? = images[key(t)]

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(t: TileId) {
        val k = key(t)
        if (images.containsKey(k) || !loading.add(k)) return
        try {
            val n = 1 shl t.z
            val x = ((t.x % n) + n) % n
            // OSM 타일 정책: 앱을 알아볼 수 있는 User-Agent를 보내야 해요(없거나 흔한 값이면 막혀요).
            val res = client.get("https://tile.openstreetmap.org/${t.z}/$x/${t.y}.png") {
                header("User-Agent", "AudeoPassenger/0.1 (+https://github.com/Audeo-2026/app-passenger)")
            }
            if (!res.status.isSuccess()) return
            val bytes: ByteArray = res.body()
            val img = withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
            images[k] = img
            order.addLast(k)
            while (order.size > 160) images.remove(order.removeFirst())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 타일 하나쯤 못 받아도 지도 바탕색으로 두고, 다음에 다시 그릴 때 또 받아요.
        } finally {
            loading.remove(k)
        }
    }
}
