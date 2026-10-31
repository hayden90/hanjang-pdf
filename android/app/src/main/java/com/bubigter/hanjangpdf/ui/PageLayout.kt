package com.bubigter.hanjangpdf.ui

import com.bubigter.hanjangpdf.core.VRect
import com.bubigter.hanjangpdf.core.cappedScale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 쪽을 세로로 늘어놓은 배치(화면 없음, 숫자만). 배율 1 = 가장 넓은 쪽을 화면 폭에 맞춤.
 * '내용 좌표'는 배율 1의 픽셀이고, 화면 좌표 = 내용 좌표 × zoom − 스크롤(ox, oy).
 */
class PageLayout(sizes: List<VRect>, val viewW: Float, val gap: Float) {
    /** pt → 배율 1 픽셀. */
    val base: Float = viewW / max(1f, sizes.maxOf { it.w })
    val lefts: FloatArray
    val tops: FloatArray
    val widths: FloatArray
    val heights: FloatArray
    val totalH: Float
    /** 기본 그림이 픽셀 한도 때문에 배율 1보다 낮게 그려지는 쪽(아주 긴 쪽·넓은 쪽). 이런 쪽은 배율 1에서도 보이는 부분을 타일로 그린다. */
    val capped: BooleanArray

    init {
        val n = sizes.size
        lefts = FloatArray(n); tops = FloatArray(n); widths = FloatArray(n); heights = FloatArray(n)
        capped = BooleanArray(n) { cappedScale(sizes[it].w, sizes[it].h, base) < base }
        var y = gap
        for (i in 0 until n) {
            widths[i] = sizes[i].w * base
            heights[i] = sizes[i].h * base
            lefts[i] = (viewW - widths[i]) / 2f
            tops[i] = y
            y += heights[i] + gap
        }
        totalH = y
    }

    val count get() = tops.size

    /** 화면 구간 [y0, y1)(화면 픽셀)에 걸친 쪽 번호 범위. 없으면 빈 범위. */
    fun pagesIn(y0: Float, y1: Float, zoom: Float, oy: Float): IntRange {
        var first = -1; var last = -1
        for (i in 0 until count) {
            val t = tops[i] * zoom - oy
            val b = (tops[i] + heights[i]) * zoom - oy
            if (b > y0 && t < y1) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    /** 화면 가운데 줄에 가장 가까운 쪽(쪽 번호 표시). */
    fun pageAt(screenY: Float, zoom: Float, oy: Float): Int {
        val y = (screenY + oy) / zoom
        var best = 0
        for (i in 0 until count) if (tops[i] - gap / 2f <= y) best = i
        return best
    }

    /** 스크롤 한계. 내용이 화면보다 좁으면 가운데에 둔다. */
    fun clampX(ox: Float, zoom: Float): Float {
        val cw = viewW * zoom
        return if (cw <= viewW) (cw - viewW) / 2f else min(max(ox, 0f), cw - viewW)
    }

    fun clampY(oy: Float, zoom: Float, viewH: Float): Float = min(max(oy, 0f), max(0f, totalH * zoom - viewH))

    /**
     * 확대 타일 목록: 쪽 [page]을 배율 [zq](배율 1 대비)로 그린 그림을 [tile]px 조각으로 나눌 때,
     * 화면 [viewH] 안에 보이는 조각의 (tx, ty) 목록.
     */
    fun visibleTiles(page: Int, zoom: Float, ox: Float, oy: Float, viewH: Float, zq: Float, tile: Int): List<Pair<Int, Int>> {
        val sx = lefts[page] * zoom - ox
        val sy = tops[page] * zoom - oy
        val k = zq / zoom // 화면 픽셀 → 그 배율의 쪽 픽셀
        val pw = widths[page] * zq
        val ph = heights[page] * zq
        val x0 = max(0f, (0f - sx) * k); val x1 = min(pw, (viewW - sx) * k)
        val y0 = max(0f, (0f - sy) * k); val y1 = min(ph, (viewH - sy) * k)
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (ty in floor(y0 / tile).toInt()..(ceil(y1 / tile).toInt() - 1))
            for (tx in floor(x0 / tile).toInt()..(ceil(x1 / tile).toInt() - 1)) out.add(tx to ty)
        return out
    }

    /** 쪽 [page]의 타일 배율. 0이면 타일 없이 기본 그림만(확대하지 않았고 기본 그림이 한도 안). */
    fun tileZoom(page: Int, zoom: Float): Float = if (zoom > TILE_FROM || capped[page]) quantize(zoom) else 0f

    /** 확대 타일을 그릴 배율 단계(0.5 단위, 최대 [MAX_ZOOM]). 이 값 이하면 타일 없이 기본 그림만. */
    fun quantize(zoom: Float): Float = min(MAX_ZOOM, ceil(zoom * 2f) / 2f)

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 8f
        const val TILE_FROM = 1.25f
    }
}
