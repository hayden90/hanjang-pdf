package com.bubigter.hanjangpdf.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bubigter.hanjangpdf.core.ViewDoc
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val TILE = 512

/** 세로 스크롤 쪽 보기: 두 손가락 확대·축소, 두 번 눌러 확대·폭 맞춤, 찾은 자리 칠하기. */
@Composable
fun PdfView(model: ViewerModel, doc: ViewDoc, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Color(0xFFE6E6E6))) {
        val density = LocalDensity.current
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val gap = with(density) { 8.dp.toPx() }
        val layout = remember(doc, viewW) { PageLayout(doc.sizes, viewW, gap) }
        var zoom by remember(doc) { mutableFloatStateOf(1f) }
        var ox by remember(doc) { mutableFloatStateOf(0f) }
        var oy by remember(doc) { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val flingJob = remember(doc) { arrayOfNulls<Job>(1) } // 진행 중인 관성 스크롤

        fun zoomAt(newZoom: Float, c: Offset) {
            val z = newZoom.coerceIn(PageLayout.MIN_ZOOM, PageLayout.MAX_ZOOM)
            // 손가락 가운데(c) 아래의 내용이 그대로 있도록 스크롤을 옮긴다.
            ox = layout.clampX((c.x + ox) * z / zoom - c.x, z)
            oy = layout.clampY((c.y + oy) * z / zoom - c.y, z, viewH)
            zoom = z
        }

        // 렌더: 움직임이 멈추고 잠시 뒤 보이는 쪽(앞뒤 한 쪽 포함)의 기본 그림과, 확대 중이면 보이는 타일만 그린다.
        LaunchedEffect(layout, viewH) {
            snapshotFlow { Triple(zoom, ox, oy) }.collectLatest { (z, x, y) ->
                delay(80)
                renderPass(model, doc, layout, z, x, y, viewH)
            }
        }

        // 찾기 결과로 옮겨 가기: 찾은 자리를 화면 가운데로.
        LaunchedEffect(model.jumpTo, layout) {
            val h = model.jumpTo ?: return@LaunchedEffect
            if (h.page >= layout.count) return@LaunchedEffect
            val cx = layout.lefts[h.page] + (h.rect.x0 + h.rect.x1) / 2f * layout.base
            val cy = layout.tops[h.page] + (h.rect.y0 + h.rect.y1) / 2f * layout.base
            flingJob[0]?.cancel()
            ox = layout.clampX(cx * zoom - viewW / 2f, zoom)
            oy = layout.clampY(cy * zoom - viewH / 2f, zoom, viewH)
            model.jumpTo = null
        }

        // 쪽 목록에서 고른 쪽·쪽 작업 뒤 보던 쪽으로 옮긴다.
        LaunchedEffect(model.jumpPage, layout) {
            val p = model.jumpPage ?: return@LaunchedEffect
            if (p in 0 until layout.count) oy = layout.clampY(layout.tops[p] * zoom, zoom, viewH)
            model.jumpPage = null
        }

        val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val cur = model.cursor
        Canvas(
            Modifier.fillMaxSize().testTag("pdfview")
                .pointerInput(layout) {
                    detectTapGestures(onDoubleTap = { c ->
                        flingJob[0]?.cancel()
                        zoomAt(if (zoom < 1.9f) 2.5f else 1f, c)
                    })
                }
                .pointerInput(layout) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        flingJob[0]?.cancel()
                        val vt = VelocityTracker()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                            val zc = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid(useCurrent = true)
                            if (zc != 1f || pan != Offset.Zero) {
                                if (zc != 1f && c != Offset.Unspecified) zoomAt(zoom * zc, c)
                                ox = layout.clampX(ox - pan.x, zoom)
                                oy = layout.clampY(oy - pan.y, zoom, viewH)
                                moved = true
                            }
                            val p = ev.changes.first()
                            vt.addPosition(p.uptimeMillis, p.position)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (moved) {
                            val v = vt.calculateVelocity()
                            flingJob[0] = scope.launch {
                                var lx = 0f; var ly = 0f
                                val sx = AnimationState(0f, -v.x); val sy = AnimationState(0f, -v.y)
                                launch { sx.animateDecay(exponentialDecay()) { ox = layout.clampX(ox + (value - lx), zoom); lx = value } }
                                sy.animateDecay(exponentialDecay()) { oy = layout.clampY(oy + (value - ly), zoom, viewH); ly = value }
                            }
                        }
                    }
                }
        ) {
            val vis = layout.pagesIn(0f, size.height, zoom, oy)
            val tiles = model.tiles
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                for (p in vis) {
                    val l = layout.lefts[p] * zoom - ox
                    val t = layout.tops[p] * zoom - oy
                    val w = layout.widths[p] * zoom
                    val h = layout.heights[p] * zoom
                    drawRect(Color.White, Offset(l, t), Size(w, h))
                    tiles[TileKey(p, 0f, 0, 0)]?.let { nc.drawBitmap(it, null, RectF(l, t, l + w, t + h), paint) }
                    // 확대 타일(배율이 다른 옛 타일도 새 것이 올 때까지 늘려 그린다. 낮은 배율부터).
                    tiles.keys.filter { it.page == p && it.zq != 0f }.sortedBy { it.zq }.forEach { k ->
                        val bmp = tiles[k] ?: return@forEach
                        val s = zoom / k.zq
                        val x = l + k.tx * TILE * s
                        val y = t + k.ty * TILE * s
                        nc.drawBitmap(bmp, null, RectF(x, y, x + bmp.width * s, y + bmp.height * s), paint)
                    }
                    // 찾은 자리
                    val k = layout.base * zoom
                    cur.hits.forEachIndexed { i, hit ->
                        if (hit.page != p) return@forEachIndexed
                        val col = if (i == cur.index) Color(0x99FF6D00) else Color(0x66FFD600)
                        drawRect(col, Offset(l + hit.rect.x0 * k, t + hit.rect.y0 * k),
                            Size(hit.rect.w * k, hit.rect.h * k))
                    }
                }
            }
        }

        // 지금 쪽 / 전체 쪽 수
        val pageNo = layout.pageAt(viewH / 2f, zoom, oy) + 1
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            .background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("$pageNo / ${layout.count}", color = Color.White, modifier = Modifier.testTag("pageLabel"))
        }
    }
}

/**
 * 화면 하나(배율 [z], 스크롤 [x],[y])에 필요한 그림을 그린다. 화면이 움직이면 도중에 취소되므로
 * 필요 없는 그림은 그리기 **전에** 버린다(취소돼도 쌓이지 않게). 보이는 쪽의 다른 배율 타일만
 * 새 타일이 올 때까지 늘려 그리도록 남기고, 그 합계는 [ViewerModel.ZOOM_TILE_BYTES]로 묶인다.
 * [render]는 시험에서 바꿔 끼우는 자리다.
 */
internal suspend fun renderPass(
    model: ViewerModel, doc: ViewDoc, layout: PageLayout, z: Float, x: Float, y: Float, viewH: Float,
    render: suspend (TileKey) -> Unit = { k -> model.render(doc, k) { renderKey(it, layout, k) } },
) {
    val vis = layout.pagesIn(0f, viewH, z, y)
    if (vis.isEmpty()) return
    val near = max(0, vis.first - 1)..min(layout.count - 1, vis.last + 1)
    val want = LinkedHashSet<TileKey>()
    for (p in vis) want.add(TileKey(p, 0f, 0, 0))
    for (p in near) want.add(TileKey(p, 0f, 0, 0))
    // 확대 중이거나 기본 그림이 픽셀 한도로 낮게 그려진 쪽은 보이는 부분만 타일로.
    val zqs = HashMap<Int, Float>()
    for (p in vis) {
        val zq = layout.tileZoom(p, z)
        zqs[p] = zq
        if (zq > 0f) for ((tx, ty) in layout.visibleTiles(p, z, x, y, viewH, zq, TILE))
            want.add(TileKey(p, zq, tx, ty))
    }
    model.wanted = want
    // 그리기 전에: 필요 없는 기본 그림, 안 보이는 쪽의 확대 타일, 지금 배율인데 화면 밖인 타일을 버린다.
    model.dropTiles { it !in want && (it.zq == 0f || it.page !in vis || it.zq == zqs[it.page]) }
    for (k in want) {
        if (model.tiles.containsKey(k)) continue
        render(k)
    }
    // 새 타일을 다 그렸으면 늘려 그리던 다른 배율 타일도 버린다.
    model.dropTiles { it.zq != 0f && it !in want }
}

/** 렌더 하나(MuPDF 실행기에서). 쪽이 문서 밖이면 null. 기본 그림은 픽셀 한도 안(core [ViewDoc.renderPage]). */
internal fun renderKey(doc: ViewDoc, layout: PageLayout, k: TileKey): Bitmap? {
    if (doc.closed || k.page >= doc.pageCount) return null // 다른 문서로 바뀐 뒤 줄 서 있던 렌더
    if (k.zq == 0f) return doc.renderPage(k.page, layout.base)
    val pw = ceil(layout.widths[k.page] * k.zq).toInt()
    val ph = ceil(layout.heights[k.page] * k.zq).toInt()
    val x = k.tx * TILE; val y = k.ty * TILE
    val w = min(TILE, pw - x); val h = min(TILE, ph - y)
    if (w <= 0 || h <= 0) return null
    return doc.renderTile(k.page, layout.base * k.zq, x, y, w, h)
}
