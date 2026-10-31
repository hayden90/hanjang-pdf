package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker

/*
 * PC판 찾기(pdfviewer.core.doc_ops.search → PyMuPDF 2.0 page.search_for)를 같은 규칙으로 옮긴 것.
 *
 * PC판이 하는 일(PyMuPDF 2.0 src/extra.i, MuPDF 1.28.5로 확인):
 *  1. page.get_textpage: 쪽 /Rotate를 잠시 0으로 두고, 글자 옵션 flags = DEHYPHENATE|PRESERVE_WHITESPACE|
 *     PRESERVE_LIGATURES|MEDIABOX_CLIP(= FZ_STEXT_CLIP, 83)로 글자를 뽑는다(page_get_textpage).
 *     CropBox 걸러내기는 이 단계에서 일어난다: FZ_STEXT_CLIP은 '완전히 잘려 나가는 글자'만 버리고
 *     일부라도 보이는 글자는 남긴다. 그래서 첫 글자가 완전히 밖인 낱말은 그 글자가 빠져 안 걸리고,
 *     일부만 걸친 낱말은 쪽 밖 좌표(음수 등)까지 든 사각형으로 걸린다.
 *  2. JM_search_stext_page: 글자를 이어 붙인 글(줄 끝 '\n', 블록 끝 '\n')에서 대소문자·공백을 접어 찾고
 *     (find_string·match_string·canon), 찾은 글자의 사각형을 줄 방향으로 이어 붙인다(on_highlight_char).
 *  3. TextPage.search: 같은 줄(y1 같음)에서 겹치는 사각형을 합친다.
 *  4. doc_ops.search: 사각형 * page.rotation_matrix(화면 방향).
 *
 * 안드로이드는 같은 MuPDF 1.28.5지만 fitz Java의 StructuredText.search가 JNI 결함으로 앱을 죽이고
 * (1.28.5 platform/java/jni/structuredtext.c가 ArrayList.toArray를 인자 없이 부름),
 * Page.search는 글자 옵션이 DEHYPHENATE뿐이라(fz_match_page_cb) CropBox 밖 글자를 버리지 않는다.
 * 그래서 Page.toStructuredText에 1과 같은 옵션을 주어 글자를 뽑고, 2~4를 여기서 그대로 한다.
 * fitz Java는 쪽을 돌린 좌표로 글자를 주므로, PC처럼 '돌리기 전 좌표'로 되돌려 2·3을 하고 4로 돌린다
 * (90도 배수 회전은 사각형을 사각형으로 정확히 옮긴다).
 *
 * 줄 플래그 FZ_STEXT_LINE_FLAGS_JOINED(dehyphenate로 하이픈 줄과 다음 줄이 이어짐)는 walk가 넘겨주지 않는다.
 * 그래서 같은 글자 쪽의 StructuredText.asJSON()(1.28.5 jni structuredtext.c → fz_print_stext_page_as_json)이
 * 줄마다 적는 "flags"(줄 bbox 객체 안)를 읽는다. JSON의 text 블록·줄 순서는 walk와 같고(구조 블록 없음), 줄 글이
 * 하나라도 다르면 이어짐 없음으로 본다. PC 쪽 대응은 make_viewer_cases._line_flags가 실제 line->flags와 대조함.
 * PC는 이어진 줄의 마지막 글자(하이픈)를 찾을 글에서 빼고 줄바꿈도 안 넣지만, 사각형을 칠하는 순회는 그 글자에서도
 * 칸을 하나 넘긴다. 그래서 그 뒤 결과는 칠하는 글자가 한 칸씩 앞으로 밀린다(PC 그대로 따름, 견본 viewer_hyphen).
 *
 * 옮기지 못한 것(Java에서 보이지 않음, PC와 다를 수 있는 곳):
 *  - JM_char_quad의 글꼴 ascender/descender 보정(ascender-descender < 1인 글꼴만): fitz Java Font에 값이 없어
 *    MuPDF 글자 사각형을 그대로 쓴다(보통 글꼴은 PC도 그대로 씀).
 */

/** PC판 search_for 기본 글자 옵션과 같은 MuPDF 옵션 문자열(flags 83). */
const val PC_SEARCH_TEXT_OPTIONS = "preserve-ligatures,preserve-whitespace,dehyphenate,clip"

/** 뽑은 글자 하나(돌리기 전 좌표). quad 순서는 ul, ur, ll, lr의 x·y. */
class SChar(val c: Int, val q: FloatArray, val size: Float)

/** 뽑은 줄 하나. dir은 줄 방향(cos, sin). joined는 FZ_STEXT_LINE_FLAGS_JOINED. */
class SLine(val wmode: Int, val dx: Float, val dy: Float, val chars: List<SChar>, val joined: Boolean = false)

object TextSearch {
    /**
     * PC판 doc_ops.search의 한 쪽 몫. [page]의 화면 방향 사각형 목록(PC와 같은 순서·개수).
     * [b]는 이 쪽의 MuPDF 쪽 경계(Page.bounds).
     */
    fun searchPage(page: PDFPage, b: Rect, query: String): List<PRect> {
        val rot = rotationOf(page)
        val w = b.x1 - b.x0
        val h = b.y1 - b.y0
        // 돌리기 전 쪽 크기(90·270이면 폭·높이가 바뀜)
        val (w0, h0) = if (rot == 90 || rot == 270) h to w else w to h
        val toScreen = PMatrix.rotation(rot, w0, h0)
        val toPage = PMatrix.invert(toScreen)
        val blocks = collect(page, b, toPage)
        return search(blocks, query, PRect(0.0, 0.0, w0.toDouble(), h0.toDouble())).map { it * toScreen }
    }

    /** PyMuPDF page.rotation(상속 값 포함, 90의 배수가 아니면 0). [Annots.rotationOf]와 같은 셈. */
    private fun rotationOf(page: PDFPage): Int {
        val r = page.getObject().getInheritable("Rotate")
        var rot = if (r.isNumber) r.asInteger() else 0
        while (rot < 0) rot += 360
        while (rot >= 360) rot -= 360
        return if (rot % 90 != 0) 0 else rot
    }

    /** PC 옵션으로 글자를 뽑아 돌리기 전 좌표(쪽 경계 원점 0)로 옮긴 블록·줄·글자. */
    private fun collect(page: PDFPage, b: Rect, toPage: PMatrix): List<List<SLine>> {
        val st = page.toStructuredText(PC_SEARCH_TEXT_OPTIONS)
        val blocks = ArrayList<List<SLine>>()
        try {
            st.walk(object : StructuredTextWalker {
                var lines: ArrayList<SLine>? = null
                var chars: ArrayList<SChar>? = null
                var wmode = 0
                var dx = 1f
                var dy = 0f
                override fun beginTextBlock(bbox: Rect?, flags: Int) { lines = ArrayList() }
                override fun endTextBlock() { lines?.let { blocks.add(it) }; lines = null }
                override fun beginLine(bbox: Rect?, wmode: Int, dir: Point) {
                    chars = ArrayList()
                    this.wmode = wmode
                    // 방향은 회전 부분만 되돌린다(이동 없음)
                    dx = dir.x * toPage.a + dir.y * toPage.c
                    dy = dir.x * toPage.b + dir.y * toPage.d
                }
                override fun endLine() { chars?.let { lines?.add(SLine(wmode, dx, dy, it)) }; chars = null }
                override fun onChar(c: Int, origin: Point?, font: com.artifex.mupdf.fitz.Font?, size: Float,
                                    quad: Quad, argb: Int, flags: Int, bidi: Int) {
                    chars?.add(SChar(c, toPageQuad(quad, b, toPage), size))
                }
                override fun onImageBlock(bbox: Rect?, transform: com.artifex.mupdf.fitz.Matrix?,
                                          image: com.artifex.mupdf.fitz.Image?) {}
                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
            })
            return withJoined(blocks, jsonLines(st.asJSON()))
        } finally {
            st.destroy()
        }
    }

    /** asJSON의 text 블록 줄마다 (글, 줄 플래그). 읽지 못하면 null. */
    internal fun jsonLines(json: String): List<Pair<String, Int>>? = try {
        val out = ArrayList<Pair<String, Int>>()
        val bs = org.json.JSONObject(json).getJSONArray("blocks")
        for (i in 0 until bs.length()) {
            val b = bs.getJSONObject(i)
            if (b.optString("type") != "text") continue
            val ls = b.getJSONArray("lines")
            for (j in 0 until ls.length()) {
                val l = ls.getJSONObject(j)
                // 1.28.5는 줄의 flags를 bbox 객체 안에 적는다
                out.add(l.getString("text") to (l.optJSONObject("bbox")?.optInt("flags", 0) ?: 0))
            }
        }
        out
    } catch (e: org.json.JSONException) {
        null
    }

    /** walk 줄에 JSON 줄 플래그의 JOINED(1)를 붙인다. 줄 수나 글이 하나라도 다르면 그대로(이어짐 없음). */
    internal fun withJoined(blocks: List<List<SLine>>, lines: List<Pair<String, Int>>?): List<List<SLine>> {
        if (lines == null || lines.size != blocks.sumOf { it.size }) return blocks
        var k = 0
        for (block in blocks) for (line in block) {
            val text = buildString { line.chars.forEach { appendCodePoint(it.c) } }
            if (text != lines[k++].first) return blocks
        }
        k = 0
        return blocks.map { block ->
            block.map { l -> SLine(l.wmode, l.dx, l.dy, l.chars, (lines[k++].second and LINE_JOINED) != 0) }
        }
    }

    private const val LINE_JOINED = 1 // FZ_STEXT_LINE_FLAGS_JOINED

    private fun toPageQuad(q: Quad, b: Rect, m: PMatrix): FloatArray {
        val src = floatArrayOf(q.ul_x, q.ul_y, q.ur_x, q.ur_y, q.ll_x, q.ll_y, q.lr_x, q.lr_y)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = src[2 * i] - b.x0
            val y = src[2 * i + 1] - b.y0
            out[2 * i] = x * m.a + y * m.c + m.e
            out[2 * i + 1] = x * m.b + y * m.d + m.f
        }
        return out
    }

    /**
     * PyMuPDF JM_search_stext_page + TextPage.search(quads=False)와 같은 셈.
     * [mediabox]는 글자 쪽(stext page)의 mediabox(= 돌리기 전 쪽 경계). 돌려주는 것은 돌리기 전 좌표 사각형.
     */
    fun search(blocks: List<List<SLine>>, query: String, mediabox: PRect): List<PRect> {
        val needle = query.codePoints().toArray()
        if (needle.isEmpty()) return emptyList()
        val hay = haystack(blocks, mediabox)
        val quads = ArrayList<FloatArray>()
        var hi = 0
        var found = findString(hay, 0, needle) ?: return emptyList()
        var inside = false
        loop@ for (block in blocks) {
            for (line in block) {
                for (ch in line.chars) {
                    if (!overlaps(mediabox, charBbox(line, ch))) continue
                    while (true) { // try_new_match
                        if (!inside && hi >= found.first) inside = true
                        if (inside) {
                            if (hi < found.second) {
                                highlight(quads, line, ch)
                            } else {
                                inside = false
                                found = findString(hay, hi, needle) ?: break@loop
                                continue
                            }
                        }
                        break
                    }
                    hi += 1
                }
                if (hay.getOrNull(hi) == NL) hi += 1
            }
            hi += 1 // 블록 끝 '\n'
        }
        return joinRects(quads.map { quadRect(it) })
    }

    private const val NL = '\n'.code
    private const val SP = ' '.code

    /**
     * JM_new_buffer_from_stext_page. 글자 하나 = 칸 하나. 이어진 줄은 마지막 글자(쪽 안에 있을 때만)를 빼고
     * 줄바꿈도 넣지 않는다(마지막 글자가 쪽 밖이면 PC처럼 줄바꿈을 넣는다).
     */
    private fun haystack(blocks: List<List<SLine>>, mediabox: PRect): IntArray {
        val out = ArrayList<Int>()
        for (block in blocks) {
            for (line in block) {
                var breakLine = true
                for ((i, ch) in line.chars.withIndex()) {
                    if (!overlaps(mediabox, charBbox(line, ch))) continue
                    if (i == line.chars.size - 1 && line.joined) breakLine = false
                    else out.add(ch.c)
                }
                if (breakLine) out.add(NL)
            }
            out.add(NL)
        }
        return out.toIntArray()
    }

    /** extra.i canon(): 줄바꿈·탭·특수 공백 → ' ', A-Z만 소문자(유니코드 접기 없음). */
    private fun canon(c: Int): Int = when {
        c == 0xA0 || c == 0x2028 || c == 0x2029 -> SP
        c == '\r'.code || c == '\n'.code || c == '\t'.code -> SP
        c in 'A'.code..'Z'.code -> c - 'A'.code + 'a'.code
        else -> c
    }

    /**
     * extra.i find_string: [from]부터 한 칸씩 옮기며 match_string이 맞는 첫 자리.
     * (시작, 끝) 칸 번호. 끝은 match_string의 e(마지막으로 맞춘 글자 바로 뒤).
     */
    private fun findString(hay: IntArray, from: Int, needle: IntArray): Pair<Int, Int>? {
        for (s in from until hay.size) {
            val e = matchString(hay, s, needle)
            if (e != null) return s to e
        }
        return null
    }

    /**
     * extra.i match_string. 글 끝은 0(C 문자열 끝)으로 본다. C는 둘 다 0이어도 돌지만 그때는 글 끝을 넘어 읽는다.
     * 글은 늘 '\n'으로 끝나고 찾는 말은 앞뒤 공백을 떼므로 그 경우는 생기지 않아, 여기서는 찾는 말 끝에서 멈춘다.
     */
    private fun matchString(hay: IntArray, start: Int, needle: IntArray): Int? {
        var h = start
        var n = 0
        fun hAt(i: Int) = if (i < hay.size) canon(hay[i]) else 0
        fun nAt(i: Int) = if (i < needle.size) canon(needle[i]) else 0
        var e = h
        var hc = hAt(h); h += 1
        var nc = nAt(n); n += 1
        while (hc == nc && nc != 0) {
            e = h
            if (hc == SP) {
                do { hc = hAt(h); h += 1 } while (hc == SP)
            } else {
                hc = hAt(h); h += 1
            }
            if (nc == SP) {
                do { nc = nAt(n); n += 1 } while (nc == SP)
            } else {
                nc = nAt(n); n += 1
            }
        }
        return if (nc == 0) e else null
    }

    /** JM_char_bbox: 글자 사각형의 bbox, 세로쓰기 줄이면 높이를 글자 크기 이상으로. */
    private fun charBbox(line: SLine, ch: SChar): PRect {
        val r = quadRectF(ch.q)
        if (line.wmode == 0) return r
        return if (r.y1 < r.y0 + ch.size) PRect(r.x0, (r.y1.toFloat() - ch.size).toDouble(), r.x1, r.y1) else r
    }

    /** JM_rects_overlap(무한 사각형 검사는 쪽 경계라 필요 없음). */
    private fun overlaps(a: PRect, b: PRect): Boolean =
        !(a.x0 >= b.x1 || a.y0 >= b.y1 || a.x1 <= b.x0 || a.y1 <= b.y0)

    /** fz_rect_from_quad(float min/max). */
    private fun quadRectF(q: FloatArray): PRect {
        val xs = floatArrayOf(q[0], q[2], q[4], q[6])
        val ys = floatArrayOf(q[1], q[3], q[5], q[7])
        return PRect(xs.min().toDouble(), ys.min().toDouble(), xs.max().toDouble(), ys.max().toDouble())
    }

    /** pymupdf Quad.rect(네 점의 min/max). */
    private fun quadRect(q: FloatArray): PRect = quadRectF(q)

    private fun hdist(dx: Float, dy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        Math.abs((bx - ax) * dx + (by - ay) * dy)

    private fun vdist(dx: Float, dy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        Math.abs((bx - ax) * dy + (by - ay) * dx)

    /** extra.i on_highlight_char(hfuzz 0.2, vfuzz 0.1): 앞 사각형 끝에 붙으면 늘이고, 아니면 새로 둔다. */
    private fun highlight(quads: ArrayList<FloatArray>, line: SLine, ch: SChar) {
        val vfuzz = ch.size * 0.1f
        val hfuzz = ch.size * 0.2f
        val q = ch.q
        if (quads.isNotEmpty()) {
            val end = quads[quads.size - 1]
            val dx = line.dx
            val dy = line.dy
            // end.lr(6,7)↔ch.ll(4,5), end.ur(2,3)↔ch.ul(0,1)
            if (hdist(dx, dy, end[6], end[7], q[4], q[5]) < hfuzz &&
                vdist(dx, dy, end[6], end[7], q[4], q[5]) < vfuzz &&
                hdist(dx, dy, end[2], end[3], q[0], q[1]) < hfuzz &&
                vdist(dx, dy, end[2], end[3], q[0], q[1]) < vfuzz
            ) {
                val merged = end.copyOf()
                merged[2] = q[2]; merged[3] = q[3] // ur
                merged[6] = q[6]; merged[7] = q[7] // lr
                quads[quads.size - 1] = merged
                return
            }
        }
        quads.add(q.copyOf())
    }

    /** TextPage.search(quads=False): 같은 줄(y1 같음)에서 겹치는 사각형을 합친다. */
    private fun joinRects(rects: List<PRect>): List<PRect> {
        val v = ArrayList(rects)
        var i = 0
        while (i < v.size - 1) {
            val v1 = v[i]
            val v2 = v[i + 1]
            if (v1.y1 != v2.y1 || (v1 and v2).isEmpty) {
                i += 1
                continue
            }
            v[i] = union(v1, v2)
            v.removeAt(i + 1)
        }
        return v
    }

    /** pymupdf Rect | Rect(include_rect: 빈 사각형 처리 + 합집합). */
    private fun union(a: PRect, b: PRect): PRect = when {
        b.isEmpty -> a
        a.isEmpty -> b
        else -> PRect(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1))
    }
}
