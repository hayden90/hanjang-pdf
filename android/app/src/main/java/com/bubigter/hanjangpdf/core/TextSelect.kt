package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.PDFPage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * 8-4 글자 끌어 고르기·복사: PC판 pdfviewer/core/text_select.py의 '줄 따라 흐르게 고르기'를 옮긴 것.
 * 표 칸 고르기(page_cells)는 안드로이드 첫 판 비목표라 옮기지 않았다.
 * 좌표는 돌리기 전 쪽 좌표(주석과 같음). 읽는 순서는 쪽에서 가장 많은 글줄 방향을 오른쪽으로 놓고
 * 덩어리 위→아래·왼→오른, 덩어리 안 줄 위→아래, 줄 안 낱말은 쓰인 차례.
 */
object TextSelect {
    /** 낱말 하나(PyMuPDF get_text("words")의 한 줄: 사각형·글자·덩어리·줄·낱말 번호). */
    data class Word(val rect: PRect, val text: String, val block: Int, val line: Int, val word: Int)

    /** 읽는 순서대로 낱말. 스캔 쪽처럼 글자 정보가 없으면 빈 목록. */
    fun pageWords(annots: Annots, page: PDFPage): List<Word> = annots.unrotated(page) {
        val st = page.toStructuredText("preserve-ligatures,preserve-whitespace")
        val words = ArrayList<Word>()
        val dirs = LinkedHashMap<Pair<Double, Double>, Int>()
        try {
            st.getBlocks()?.forEachIndexed { bi, b ->
                b.lines?.forEachIndexed { li, ln ->
                    ln.dir?.let { d ->
                        val k = Math.rint(d.x * 100.0) / 100.0 to Math.rint(d.y * 100.0) / 100.0
                        dirs[k] = (dirs[k] ?: 0) + 1
                    }
                    var cur: PRect? = null
                    val sb = StringBuilder()
                    var wi = 0
                    fun flush() {
                        val r = cur ?: return
                        if (sb.isNotBlank()) words.add(Word(r, sb.toString(), bi, li, wi++))
                        cur = null; sb.setLength(0)
                    }
                    for (ch in ln.chars ?: emptyArray()) {
                        if (ch.c <= 32 || ch.c == 160) { flush(); continue }
                        val q = ch.quad
                        val cr = PRect(minOf(q.ul_x, q.ll_x).toDouble(), minOf(q.ul_y, q.ur_y).toDouble(),
                            maxOf(q.ur_x, q.lr_x).toDouble(), maxOf(q.ll_y, q.lr_y).toDouble())
                        cur = cur?.let { PRect(minOf(it.x0, cr.x0), minOf(it.y0, cr.y0), maxOf(it.x1, cr.x1), maxOf(it.y1, cr.y1)) } ?: cr
                        sb.appendCodePoint(ch.c)
                    }
                    flush()
                }
            }
        } finally {
            st.destroy()
        }
        order(words, frame(dirs))
    }

    /** 가장 많은 글줄 방향이 오른쪽(+x)이 되게 돌리는 행렬(같은 수면 먼저 나온 방향, 파이썬 Counter.most_common). */
    private fun frame(dirs: Map<Pair<Double, Double>, Int>): DoubleArray {
        val best = dirs.entries.maxByOrNull { it.value }?.key ?: return doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        val th = -atan2(best.second, best.first)
        return doubleArrayOf(cos(th), sin(th), -sin(th), cos(th))
    }

    private fun turn(r: PRect, m: DoubleArray): PRect {
        val xs = DoubleArray(4); val ys = DoubleArray(4)
        listOf(r.x0 to r.y0, r.x1 to r.y0, r.x0 to r.y1, r.x1 to r.y1).forEachIndexed { i, (x, y) ->
            xs[i] = x * m[0] + y * m[2]; ys[i] = x * m[1] + y * m[3]
        }
        return PRect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun union(a: PRect?, b: PRect) = if (a == null) b else PRect(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1))

    private fun order(words: List<Word>, m: DoubleArray): List<Word> {
        val blocks = HashMap<Int, PRect>()
        val lines = HashMap<Pair<Int, Int>, PRect>()
        for (w in words) {
            val r = turn(w.rect, m)
            blocks[w.block] = union(blocks[w.block], r)
            lines[w.block to w.line] = union(lines[w.block to w.line], r)
        }
        fun r(v: Double) = Math.rint(v).toLong()
        return words.sortedWith(compareBy<Word>(
            { r(blocks[it.block]!!.y0) }, { r(blocks[it.block]!!.x0) }, { it.block },
            { r(lines[it.block to it.line]!!.y0) }, { r(lines[it.block to it.line]!!.x0) }, { it.line }, { it.word }))
    }

    private fun dist(w: Word, p: PPoint): Double {
        val dx = maxOf(w.rect.x0 - p.x, 0.0, p.x - w.rect.x1)
        val dy = maxOf(w.rect.y0 - p.y, 0.0, p.y - w.rect.y1)
        return dx * dx + dy * dy
    }

    private fun nearest(words: List<Word>, p: PPoint): Int {
        var best = 0
        for (i in 1 until words.size) if (dist(words[i], p) < dist(words[best], p)) best = i
        return best
    }

    /** 끈 시작점·끝점 사이 낱말(읽는 순서). 시작 낱말부터 끝 낱말까지 줄을 따라 흐른다. */
    fun between(words: List<Word>, p0: PPoint, p1: PPoint): List<Word> {
        if (words.isEmpty()) return emptyList()
        var a = nearest(words, p0); var b = nearest(words, p1)
        if (a > b) { val t = a; a = b; b = t }
        return words.subList(a, b + 1).toList()
    }

    /** 같은 줄 낱말은 띄어 쓰고, 줄이 바뀌면 줄을 바꾼다. */
    fun text(words: List<Word>): String {
        val out = ArrayList<MutableList<String>>()
        var key: Pair<Int, Int>? = null
        for (w in words) {
            val k = w.block to w.line
            if (k != key) { out.add(ArrayList()); key = k }
            out.last().add(w.text)
        }
        return out.joinToString("\n") { it.joinToString(" ") }
    }
}
