package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import java.text.Normalizer

/** 사용자에게 그대로 보여 줄 수 있는 편집 오류(PC판 DocOpError). */
class DocOpError(message: String) : Exception(message)

/**
 * 형광펜·메모·글자 상자·상자: PC판 pdfviewer/core/annots.py를 그대로 옮긴 것(그 파일이 기준).
 *
 * 좌표는 모두 PC와 같은 '돌리기 전 쪽 좌표'(PyMuPDF 규약: CropBox 왼쪽 위가 0, 아래로 +y)이다.
 * fitz Java API는 쪽 회전을 넣은 좌표를 쓰므로, PyMuPDF가 하는 것처럼 쪽 /Rotate를 잠시 0으로 두고
 * 주석을 만들거나 읽는다([unrotated]). 그러면 Java 좌표가 PyMuPDF 좌표와 같아진다.
 *
 * 함수는 쪽 번호를 받아 그때마다 쪽을 다시 읽는다(PC판과 같음).
 */
class Annots(private val widths: Widths) {

    // ── 표식 상수(PC판과 같아야 서로 알아봄) ──
    companion object {
        val HL_COLORS = linkedMapOf(
            "yellow" to floatArrayOf(1.0f, 0.92f, 0.23f),
            "green" to floatArrayOf(0.55f, 0.9f, 0.45f),
            "pink" to floatArrayOf(1.0f, 0.6f, 0.8f),
        )
        val OWN_TYPES = setOf(PDFAnnotation.TYPE_HIGHLIGHT, PDFAnnotation.TYPE_TEXT,
            PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_SQUARE)
        const val AUTHOR = "PDF 뷰어"  // 이름이 한장PDF로 바뀌어도 그대로(예전 표식을 알아봄)
        const val MARK = "pdfviewer-highstar"
        const val MIN_DRAG = 3
        val TEXT_ALIGNS = listOf("left", "center", "right")
        const val TEXT_MIN = 6
        const val TEXT_MAX = 72
        const val STYLE_KEY = "HSTextStyle"
        const val MIN_BOX_W = 60
        const val BOX_LEAD = 26
        val SHAPE_WIDTHS = listOf(0.5, 1.0, 2.0, 3.0, 5.0)
        const val PRINT_FLAG = 4  // PDF_ANNOT_IS_PRINT
        const val TOO_LONG = "글자가 너무 많아 이 쪽에 다 들어가지 않습니다. 글자를 줄이거나 크기를 작게 해 주세요."
        /** 파이썬 re.match(r"^#[0-9a-fA-F]{6}$"): '$'는 끝의 줄바꿈 하나 앞에서도 맞는다. */
        fun colorOk(c: String): Boolean {
            val s = if (c.length == 8 && c[7] == '\n') c.substring(0, 7) else c
            return s.length == 7 && s[0] == '#' && s.substring(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        /** PyMuPDF _add_freetext_annot의 RC 머리(공백까지 같게). */
        const val RC_HEAD = "<?xml version=\"1.0\"?>\n" +
            "            <body xmlns=\"http://www.w3.org/1999/xtml\"\n" +
            "            xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\"\n" +
            "            xfa:contentType=\"text/html\" xfa:APIVersion=\"Acrobat:8.0.0\" xfa:spec=\"2.4\">\n" +
            "            "

        /** 파이썬 str.isspace()가 참인 글자. */
        fun pyIsSpace(cp: Int): Boolean = cp in 0x09..0x0d || cp in 0x1c..0x20 || cp == 0x85 || cp == 0xa0 ||
            cp == 0x1680 || cp in 0x2000..0x200a || cp == 0x2028 || cp == 0x2029 || cp == 0x202f ||
            cp == 0x205f || cp == 0x3000

        /** _squash: 띄어쓰기·줄바꿈을 뺀 글자만(파이썬 re \\s = str.isspace() 글자, NBSP 포함). */
        fun squash(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (!pyIsSpace(cp)) sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        /** 파이썬 str.splitlines(). */
        fun pySplitLines(s: String): List<String> {
            val out = ArrayList<String>()
            var start = 0; var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c in '\u001c'..'\u001e' ||
                    c == '\u0085' || c == ' ' || c == ' ') {
                    out.add(s.substring(start, i))
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    start = i + 1
                }
                i++
            }
            if (start < s.length) out.add(s.substring(start))
            return out
        }

        private val FIL = Regex("[fFil]")

        /** _read_key: 그려진 글자 비교용(줄마다 글자, 이음 글자 풀고 f·i·l 뺌). */
        fun readKey(s: String): List<String> {
            val out = ArrayList<String>()
            for (line in pySplitLines(s)) {
                val k = FIL.replace(squash(Normalizer.normalize(line, Normalizer.Form.NFKC)), "")
                if (squash(line).isNotEmpty()) out.add(k)
            }
            return out
        }

        /** _text_html: html.escape(quote=False) + 줄 앞·이어진 칸은 NBSP. */
        fun textHtml(rows: List<String>, size: Int, align: String, color: String): String {
            val style = "font-family:sans-serif;font-size:${size}pt;color:$color;text-align:$align;margin:0"
            val sb = StringBuilder()
            for (line in rows) {
                val esc = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val body = StringBuilder()
                for (i in esc.indices) {
                    val c = esc[i]
                    if (c == ' ' && (i == 0 || esc[i - 1] == ' ')) body.append('\u00a0') else body.append(c)
                }
                sb.append("<p style='").append(style).append("'>")
                    .append(if (body.isEmpty()) "\u00a0" else body).append("</p>")
            }
            return sb.toString()
        }

        fun boxNeedHeight(size: Int, nRows: Int): Double =
            30 + size * 1.25 + (maxOf(1, nRows) - 1) * size * 1.3

        fun boxMinWidth(size: Int): Double = maxOf(MIN_BOX_W, size * 3 + 8).toDouble()
    }

    // ── 쪽 정보 ──
    /** PyMuPDF page.rotation: 상속 값 포함, 90의 배수가 아니면 0. */
    fun rotationOf(page: PDFPage): Int {
        val r = page.getObject().getInheritable("Rotate")
        var rot = if (r.isNumber) r.asInteger() else 0
        while (rot < 0) rot += 360
        while (rot >= 360) rot -= 360
        return if (rot % 90 != 0) 0 else rot
    }

    /** 쪽 /Rotate를 잠시 0으로 두고 block을 돈다(PyMuPDF annot_preprocess와 같은 방법). */
    fun <T> unrotated(page: PDFPage, block: () -> T): T {
        if (rotationOf(page) == 0) return block()
        val obj = page.getObject()
        val own = obj.get("Rotate")
        obj.put("Rotate", 0)
        try {
            return block()
        } finally {
            if (own.isNull) obj.delete("Rotate") else obj.put("Rotate", own)
        }
    }

    inner class PageGeo(val page: PDFPage) {
        val rotation = rotationOf(page)
        private val ub: Rect = unrotated(page) { page.getBounds() }
        val w0 = ub.x1 - ub.x0
        val h0 = ub.y1 - ub.y0
        val rot = PMatrix.rotation(rotation, w0, h0)
        val der = PMatrix.invert(rot)
        /** page.rect(화면 방향, 원점 0). */
        val pw: Double = (if (rotation % 180 != 0) h0 else w0).toDouble()
        val ph: Double = (if (rotation % 180 != 0) w0 else h0).toDouble()
        /** _bounds: 돌리기 전 쪽 좌표의 보이는 영역. */
        val bounds: PRect get() = (PRect(0.0, 0.0, pw, ph) * der).normalize()
    }

    fun page(doc: PDFDocument, pno: Int): PDFPage {
        if (pno !in 0 until doc.countPages()) throw DocOpError("없는 쪽 번호입니다: ${pno + 1}")
        return doc.loadPage(pno) as PDFPage
    }

    // ── 주석 읽기 도우미 ──
    fun xrefOf(a: PDFAnnotation): Int {
        val o = a.getObject()
        return if (o.isIndirect) o.asIndirect() else -1
    }

    private fun str(o: PDFObject): String = when {
        o.isString -> o.asString()
        o.isName -> o.asName()
        o.isNull -> ""
        else -> o.toString()
    }

    fun info(a: PDFAnnotation, key: String): String = str(a.getObject().get(key))

    fun isOwn(a: PDFAnnotation): Boolean =
        a.type in OWN_TYPES && info(a, "T") == AUTHOR && info(a, "Subj") == MARK

    /** PyMuPDF annot.rect(돌리기 전 쪽 좌표). */
    fun annotRect(page: PDFPage, a: PDFAnnotation): PRect = unrotated(page) { a.getBounds() }.let {
        PRect(it.x0.toDouble(), it.y0.toDouble(), it.x1.toDouble(), it.y1.toDouble())
    }

    private fun quadRects(page: PDFPage, a: PDFAnnotation): List<PRect> = unrotated(page) {
        val n = a.getQuadPointCount()
        (0 until n).map { i ->
            val q = a.getQuadPoint(i)
            // pymupdf Quad(*v[i:i+4]).rect: 네 점의 bbox
            val xs = floatArrayOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x); val ys = floatArrayOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
            PRect(xs.min().toDouble(), ys.min().toDouble(), xs.max().toDouble(), ys.max().toDouble())
        }
    }

    private fun annotsOf(page: PDFPage): List<PDFAnnotation> = page.getAnnotations()?.toList() ?: emptyList()

    /** JM_add_annot_id: 쪽 안에서 겹치지 않는 /NM "fitz-A<i>". */
    private fun addAnnotId(doc: PDFDocument, page: PDFPage, a: PDFAnnotation, stem: String) {
        val names = annotsOf(page).map { info(it, "NM") }.toSet()
        var i = 0
        while ("fitz-$stem$i" in names) i++
        a.getObject().put("NM", doc.newString("fitz-$stem$i"))
    }

    private fun toRect(r: PRect) = Rect(r.x0.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y1.toFloat())
    private fun quadOf(r: PRect): Quad {  // Rect.quad: tl, tr, bl, br
        val x0 = r.x0.toFloat(); val y0 = r.y0.toFloat(); val x1 = r.x1.toFloat(); val y1 = r.y1.toFloat()
        return Quad(x0, y0, x1, y0, x0, y1, x1, y1)
    }

    private fun colorArray(doc: PDFDocument, c: FloatArray?): PDFObject {
        val arr = doc.newArray()
        c?.forEach { arr.push(it) }
        return arr
    }

    // ── 형광펜·메모 ──
    fun addHighlight(doc: PDFDocument, pno: Int, rect: PRect, color: String = "yellow"): Int {
        val page = page(doc, pno)
        val g = PageGeo(page)
        val r = rect.normalize() and g.bounds
        if (r.isEmpty || (r.width < MIN_DRAG && r.height < MIN_DRAG)) throw DocOpError("형광펜을 칠할 곳을 끌어서 골라 주세요.")
        val col = HL_COLORS[color] ?: throw DocOpError("모르는 형광펜 색입니다: $color")
        val words = words(page)
        val hit = words.filter { it.intersects(r) }
        val quads = if (hit.isNotEmpty()) hit.map { quadOf(it) } else listOf(quadOf(r))
        val a = unrotated(page) {
            val a = page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
            quads.forEach { a.addQuadPoint(it) }
            a.update()
            addAnnotId(doc, page, a, "A")
            a
        }
        a.getObject().put("C", colorArray(doc, col))  // set_colors(stroke=...)
        a.setAuthor(AUTHOR)
        a.getObject().put("Subj", doc.newString(MARK))
        a.update()
        return xrefOf(a)
    }

    /** page.get_text("words")의 단어 사각형(돌리기 전 좌표). 칸(코드 ≤ 32, 160)에서 나눈다. */
    fun words(page: PDFPage): List<PRect> = unrotated(page) {
        val st = page.toStructuredText("preserve-ligatures,preserve-whitespace")
        val out = ArrayList<PRect>()
        for (b in st.getBlocks() ?: emptyArray()) for (ln in b.lines ?: emptyArray()) {
            var cur: PRect? = null
            for (ch in ln.chars ?: emptyArray()) {
                if (ch.c <= 32 || ch.c == 160) { cur?.let { out.add(it) }; cur = null; continue }
                val q = ch.quad
                val cr = PRect(minOf(q.ul_x, q.ll_x).toDouble(), minOf(q.ul_y, q.ur_y).toDouble(),
                    maxOf(q.ur_x, q.lr_x).toDouble(), maxOf(q.ll_y, q.lr_y).toDouble())
                cur = cur?.let { PRect(minOf(it.x0, cr.x0), minOf(it.y0, cr.y0), maxOf(it.x1, cr.x1), maxOf(it.y1, cr.y1)) } ?: cr
            }
            cur?.let { out.add(it) }
        }
        out
    }

    fun addNote(doc: PDFDocument, pno: Int, point: PPoint, text: String): Int {
        val t = pyStrip(text)
        if (t.isEmpty()) throw DocOpError("메모 내용이 비어 있습니다.")
        val page = page(doc, pno)
        if (!PageGeo(page).bounds.contains(point)) throw DocOpError("쪽 안을 눌러 메모를 붙여 주세요.")
        val a = unrotated(page) {
            val a = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
            val r0 = a.getRect()
            val px = point.x.toFloat(); val py = point.y.toFloat()
            a.setRect(Rect(px, py, px + r0.x1 - r0.x0, py + r0.y1 - r0.y0))
            a.setContents(t)
            a.setIcon("Note")
            a.update()
            addAnnotId(doc, page, a, "A")
            a
        }
        a.setContents(t)
        a.setAuthor(AUTHOR)
        a.getObject().put("Subj", doc.newString(MARK))
        a.update()
        return xrefOf(a)
    }

    private fun pyStrip(s: String): String {
        var a = 0; var b = s.length
        while (a < b && pyIsSpace(s.codePointAt(a))) a += Character.charCount(s.codePointAt(a))
        while (b > a && pyIsSpace(s.codePointBefore(b))) b -= Character.charCount(s.codePointBefore(b))
        return s.substring(a, b)
    }

    // ── 누르기·지우개 판정 ──
    private fun ownAnnot(page: PDFPage, xref: Int): PDFAnnotation =
        annotsOf(page).firstOrNull { xrefOf(it) == xref && isOwn(it) }
            ?: throw DocOpError("이 프로그램으로 넣은 형광펜·메모만 지우거나 고칠 수 있습니다.")

    fun deleteAnnot(doc: PDFDocument, pno: Int, xref: Int) {
        val page = page(doc, pno)
        page.deleteAnnotation(ownAnnot(page, xref))
    }

    private fun styleParts(a: PDFAnnotation): List<String> {
        val o = a.getObject().get(STYLE_KEY)
        val raw = if (o.isString) o.asString().trim('(', ')') else str(o)
        return raw.split("|")
    }

    /** _shown_rect: 글자 상자에서 글이 보이는 영역. */
    fun shownRect(page: PDFPage, a: PDFAnnotation): PRect {
        val p = styleParts(a)
        if (p.size == 14 && p[5] == "box") {
            try {
                return PRect(p[10].toDouble(), p[11].toDouble(), p[12].toDouble(), p[13].toDouble())
            } catch (e: NumberFormatException) {
            }
        }
        return annotRect(page, a)
    }

    private fun hollow(a: PDFAnnotation): Boolean {
        if (a.type != PDFAnnotation.TYPE_SQUARE) return false
        val ic = a.getObject().get("IC")
        return !(ic.isArray && ic.size() > 0)
    }

    private fun band(page: PDFPage, a: PDFAnnotation): Pair<PRect, PRect> {
        val pad = a.getBorderWidth().toDouble() / 2 + 3
        val r = annotRect(page, a)
        return PRect(r.x0 - pad, r.y0 - pad, r.x1 + pad, r.y1 + pad) to PRect(r.x0 + pad, r.y0 + pad, r.x1 - pad, r.y1 - pad)
    }

    fun hits(page: PDFPage, a: PDFAnnotation, pt: PPoint): Boolean {
        if (a.type == PDFAnnotation.TYPE_FREE_TEXT) return shownRect(page, a).contains(pt)
        if (hollow(a)) {
            val (outer, inner) = band(page, a)
            return outer.contains(pt) && !inner.contains(pt)
        }
        if (a.type != PDFAnnotation.TYPE_HIGHLIGHT) return annotRect(page, a).contains(pt)
        val qs = quadRects(page, a)
        if (qs.isEmpty()) return annotRect(page, a).contains(pt)
        return qs.any { it.contains(pt) }
    }

    /** _meets: 닿음(폭이나 높이가 0인 끌기도 포함). */
    private fun meets(a: PRect, b: PRect) = a.x0 <= b.x1 && b.x0 <= a.x1 && a.y0 <= b.y1 && b.y0 <= a.y1

    fun touches(page: PDFPage, a: PDFAnnotation, rect: PRect): Boolean {
        if (a.type == PDFAnnotation.TYPE_FREE_TEXT) return meets(shownRect(page, a), rect)
        if (hollow(a)) {
            val (outer, inner) = band(page, a)
            return meets(outer, rect) && !inner.containsRect(rect)
        }
        if (a.type == PDFAnnotation.TYPE_HIGHLIGHT) {
            val qs = quadRects(page, a)
            if (qs.isNotEmpty()) return qs.any { meets(it, rect) }
        }
        return meets(annotRect(page, a), rect)
    }

    fun annotAt(doc: PDFDocument, pno: Int, point: PPoint, prefer: Int? = null): Map<String, Any>? {
        val page = page(doc, pno)
        var found: Map<String, Any>? = null; var pick: Map<String, Any>? = null
        for (a in annotsOf(page)) {
            if (a.type in OWN_TYPES && isOwn(a) && hits(page, a, point)) {
                found = mapOf("xref" to xrefOf(a), "type" to a.type, "text" to info(a, "Contents"))
                if (a.type == prefer) pick = found
            }
        }
        return pick ?: found
    }

    /** box_at: 그 자리를 품은 이 프로그램 상자 도형(맨 위 것). */
    fun boxAt(doc: PDFDocument, pno: Int, point: PPoint): Int? {
        val page = page(doc, pno)
        var found: Int? = null
        for (a in annotsOf(page)) if (a.type == PDFAnnotation.TYPE_SQUARE && isOwn(a) && annotRect(page, a).contains(point)) found = xrefOf(a)
        return found
    }

    fun ownInRect(doc: PDFDocument, pno: Int, rect: PRect): List<Int> {
        val page = page(doc, pno)
        val r = rect.normalize()
        return annotsOf(page).filter { it.type in OWN_TYPES && isOwn(it) && touches(page, it, r) }.map { xrefOf(it) }
    }

    fun deleteInRect(doc: PDFDocument, pno: Int, rect: PRect): Int {
        val page = page(doc, pno)
        val r = rect.normalize()
        if (r.width < MIN_DRAG && r.height < MIN_DRAG) throw DocOpError("지울 곳을 끌어서 골라 주세요.")
        val xrefs = ownInRect(doc, pno, r)
        if (xrefs.isEmpty()) throw DocOpError("끈 곳에 지울 형광펜·메모·글자·상자가 없습니다.")
        for (x in xrefs) page.deleteAnnotation(ownAnnot(page, x))
        return xrefs.size
    }

    // ── 글자 상자 ──
    fun checkText(text: String, size: Int, align: String, color: String): String {
        val t = text.replace("\r\n", "\n").replace("\r", "\n")
        if (pyStrip(t).isEmpty()) throw DocOpError("넣을 글자가 비어 있습니다.")
        if (size !in TEXT_MIN..TEXT_MAX) throw DocOpError("글자 크기는 $TEXT_MIN~$TEXT_MAX 사이로 골라 주세요.")
        if (align !in TEXT_ALIGNS) throw DocOpError("모르는 정렬입니다: $align")
        if (!colorOk(color)) throw DocOpError("모르는 색입니다: $color")
        return t
    }

    fun textWidth(line: String, size: Int): Double = widths.textWidth(line, size)

    /** _wrap: 한 줄을 폭 inner 안에 들어가게 나눈다(띄어쓰기에서 먼저, 없으면 글자 단위). */
    fun wrap(line: String, size: Int, inner: Double): List<String> {
        val rows = ArrayList<String>()
        var cur = ""
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += Character.charCount(cp)
            if (cur.isNotEmpty() && textWidth(cur + ch, size) * 1.15 > inner) {
                val cut = cur.lastIndexOf(' ')
                if (cut > 0) {
                    rows.add(cur.substring(0, cut))
                    cur = cur.substring(cut + 1) + ch
                } else {
                    rows.add(cur)
                    cur = ch
                }
            } else {
                cur += ch
            }
        }
        rows.add(cur)
        return rows
    }

    fun rowsOk(rows: List<String>?, text: String, size: Int, inner: Double): Boolean =
        rows != null && rows.isNotEmpty() && squash(rows.joinToString("")) == squash(text) &&
            rows.all { textWidth(it, size) <= inner }

    data class Layout(val rows: List<String>, val width: Double, val height: Double)

    fun layout(text: String, size: Int, maxWidth: Double, fixed: Boolean = false, rows: List<String>? = null): Layout {
        val pad = size + 8
        val inner = pyMax(maxWidth - pad, (size * 2).toDouble())
        val rs = if (rows != null && rowsOk(rows, text, size, inner)) rows
        else text.split("\n").flatMap { wrap(it, size, inner) }
        val width = if (fixed) maxWidth
        else pyMin(pyMax(rs.maxOf { textWidth(it, size) * 1.15 } + pad, MIN_BOX_W.toDouble()), maxWidth)
        return Layout(rs, width, boxNeedHeight(size, rs.size))
    }

    /** _box_rect: 누른 곳·정렬로 상자 자리. (줄들, 돌리기 전 사각형). */
    fun boxRect(g: PageGeo, anchor: PPoint, text: String, size: Int, align: String): Pair<List<String>, PRect> {
        val vis = anchor * g.rot
        val (rows, w, h) = layout(text, size, g.pw)
        if (h > g.ph) throw DocOpError(TOO_LONG)
        var x0 = vis.x - when (align) { "left" -> 0.0; "center" -> w / 2; else -> w }
        x0 = pyMin(pyMax(x0, 0.0), g.pw - w)
        val y0 = pyMin(pyMax(vis.y, 0.0), g.ph - h)
        return rows to (PRect(x0, y0, x0 + w, y0 + h) * g.der).normalize()
    }

    /** _fixed_rect: 끌어 정한 상자. (줄들, 주석 사각형, 글이 보이는 영역). */
    fun fixedRect(g: PageGeo, box: PRect, text: String, size: Int, rows: List<String>? = null): Triple<List<String>, PRect, PRect> {
        val vis = (box * g.rot).normalize()
        var w = pyMin(pyMax(vis.width, boxMinWidth(size)), g.pw)
        val lay = layout(text, size, w, fixed = true, rows = rows)
        w = lay.width
        val h = pyMax(vis.height + BOX_LEAD, lay.height)
        if (h > g.ph) throw DocOpError(TOO_LONG)
        val x0 = pyMin(pyMax(vis.x0, 0.0), g.pw - w)
        val y0 = pyMin(pyMax(vis.y0 - BOX_LEAD, -BOX_LEAD.toDouble()), g.ph - h)
        val rect = PRect(x0, y0, x0 + w, y0 + h)
        val shown = PRect(x0, pyMin(y0 + BOX_LEAD, rect.y1), rect.x1, rect.y1)
        return Triple(lay.rows, (rect * g.der).normalize(), (shown * g.der).normalize())
    }

    /** 마지막으로 넣은 글자 상자의 줄들(시험·대조용). */
    var lastRows: List<String> = emptyList()
        private set

    fun addText(doc: PDFDocument, pno: Int, anchor: PPoint, text: String, size: Int = 12,
                align: String = "left", color: String = "#000000"): Int {
        val t = checkText(text, size, align, color)
        val page = page(doc, pno)
        val g = PageGeo(page)
        if (!g.bounds.contains(anchor)) throw DocOpError("쪽 안을 눌러 글자를 넣어 주세요.")
        val (rows, rect) = boxRect(g, anchor, t, size, align)
        return putText(doc, page, g, rect, rows, t, size, align, color, "${pyFmt2(anchor.x)}|${pyFmt2(anchor.y)}")
    }

    fun addTextBox(doc: PDFDocument, pno: Int, box: PRect, text: String, size: Int = 12, align: String = "left",
                   color: String = "#000000", rows: List<String>? = null): Int {
        val t = checkText(text, size, align, color)
        val page = page(doc, pno)
        val g = PageGeo(page)
        val b = box.normalize()
        if (b.isEmpty || !b.intersects(g.bounds)) throw DocOpError("쪽 안을 끌어 글자 상자를 그려 주세요.")
        val (rs, rect, shown) = fixedRect(g, b, t, size, rows)
        return putText(doc, page, g, rect, rs, t, size, align, color,
            "${pyFmt2(rect.x0)}|${pyFmt2(rect.y0)}|box|${pyFmt2(b.x0)}|${pyFmt2(b.y0)}|${pyFmt2(b.x1)}|${pyFmt2(b.y1)}" +
                "|${pyFmt2(shown.x0)}|${pyFmt2(shown.y0)}|${pyFmt2(shown.x1)}|${pyFmt2(shown.y1)}")
    }

    /** PyMuPDF add_freetext_annot(richtext=True, border_width=0, rotate=...)와 같은 순서로 만든다. */
    private fun createFreeText(doc: PDFDocument, page: PDFPage, rect: PRect, html: String, rotate: Int): PDFAnnotation =
        unrotated(page) {
            val a = page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
            val o = a.getObject()
            o.put("RC", doc.newString(RC_HEAD + html))
            a.setRect(toRect(rect))
            if (rotate != 0) o.put("Rotate", rotate)
            a.setQuadding(0)
            a.setBorderWidth(0f)
            a.setOpacity(1f)
            a.update()
            addAnnotId(doc, page, a, "A")
            a
        }

    /** 주석 모양에서 읽은 글자(PyMuPDF annot.get_textpage().extractText()). */
    fun annotText(a: PDFAnnotation): String = a.toDisplayList().toStructuredText("").asText()

    private fun putText(doc: PDFDocument, page: PDFPage, g: PageGeo, rect: PRect, rows: List<String>, text: String,
                        size: Int, align: String, color: String, where: String): Int {
        val a = createFreeText(doc, page, rect, textHtml(rows, size, align, color), g.rotation)
        val o = a.getObject()
        // 모양을 만든 '뒤' 표식을 PDF 값으로 직접 쓴다. 이 뒤로 update()를 부르지 않는다(기본 모양으로 다시 그려짐).
        o.put("T", doc.newString(AUTHOR))
        o.put("Subj", doc.newString(MARK))
        o.put("Contents", doc.newString(text))
        o.put("F", PRINT_FLAG)
        o.put(STYLE_KEY, doc.newString("$size|$align|$color|$where"))
        lastRows = rows
        // 실제로 그려진 모양에 글자가 빠짐없이 들어갔는지(넘쳐서 잘렸으면 거절)
        if (readKey(annotText(a)) != reference(rows, size, align, color, rect, g.rotation)) {
            page.deleteAnnotation(a)
            throw DocOpError(TOO_LONG)
        }
        return xrefOf(a)
    }

    /** _reference: 같은 폭에 높이만 아주 넉넉한 상자에 그렸을 때 읽히는 글자. */
    fun reference(rows: List<String>, size: Int, align: String, color: String, rect: PRect, rotation: Int): List<String> {
        val w = if (rotation % 180 != 0) rect.height else rect.width
        val tmp = PDFDocument()
        try {
            val pobj = tmp.addPage(Rect(0f, 0f, (w + 20).toFloat(), 6000f), 0, tmp.newDictionary(), "")
            tmp.insertPage(-1, pobj)
            val p = tmp.loadPage(0) as PDFPage
            val ref = createFreeText(tmp, p, PRect(10.0, 10.0, 10 + w, 5990.0), textHtml(rows, size, align, color), 0)
            return readKey(annotText(ref))
        } finally {
            tmp.destroy()
        }
    }

    data class TextStyle(val size: Int, val align: String, val color: String, val anchor: PPoint,
                         val box: PRect?, val shown: PRect?, val text: String, val rect: PRect, val editBox: PRect)

    fun textStyle(doc: PDFDocument, pno: Int, xref: Int): TextStyle {
        val page = page(doc, pno)
        val a = ownAnnot(page, xref)
        if (a.type != PDFAnnotation.TYPE_FREE_TEXT) throw DocOpError("글자 상자가 아닙니다.")
        val g = PageGeo(page)
        val ar = annotRect(page, a)
        var size = 12; var align = "left"; var color = "#000000"
        var anchor: PPoint; var box: PRect? = null; var shown: PRect? = null
        try {
            val p = styleParts(a)
            if (p.size < 5) throw NumberFormatException("짧음")
            size = p[0].toInt(); align = p[1]; color = p[2]
            anchor = PPoint(p[3].toDouble(), p[4].toDouble())
            if (p.size == 14 && p[5] == "box") {
                box = PRect(p[6].toDouble(), p[7].toDouble(), p[8].toDouble(), p[9].toDouble())
                shown = PRect(p[10].toDouble(), p[11].toDouble(), p[12].toDouble(), p[13].toDouble())
            }
        } catch (e: NumberFormatException) {  // 표식이 깨졌으면 상자 왼쪽 위·기본값으로
            size = 12; align = "left"; color = "#000000"; box = null; shown = null
            anchor = (ar * g.rot).normalize().tl * g.der
        }
        val vr = (ar * g.rot).normalize()
        var editBox = (PRect(vr.x0, pyMin(vr.y0 + BOX_LEAD, vr.y1), vr.x1, vr.y1) * g.der).normalize()
        var rect = ar
        if (shown != null) {
            rect = shown
            val vs = (shown * g.rot).normalize(); val vb = (box!! * g.rot).normalize()
            editBox = (PRect(vs.x0, vs.y0, vs.x1, vs.y0 + vb.height) * g.der).normalize()
        }
        return TextStyle(size, align, color, anchor, box, shown, info(a, "Contents"), rect, editBox)
    }

    fun setText(doc: PDFDocument, pno: Int, xref: Int, text: String, size: Int, align: String, color: String,
                rows: List<String>? = null, box: PRect? = null): Int {
        val t = checkText(text, size, align, color)
        val st = textStyle(doc, pno, xref)
        val page = page(doc, pno)
        page.deleteAnnotation(ownAnnot(page, xref))
        if (box != null) return addTextBox(doc, pno, box, t, size, align, color, rows)
        if (st.box != null) return addTextBox(doc, pno, st.box, t, size, align, color, rows)
        return addText(doc, pno, st.anchor, t, size, align, color)
    }

    // ── 상자 도형 ──
    private fun rgb(hex: String?): FloatArray? {
        if (hex == null) return null
        if (!colorOk(hex)) throw DocOpError("모르는 색입니다: $hex")
        // 파이썬 tuple(int/255): set_colors가 '%g'로 적고 MuPDF가 float로 읽는다(= float로 바꾼 값)
        return FloatArray(3) { (hex.substring(1 + it * 2, 3 + it * 2).toInt(16) / 255.0).toFloat() }
    }

    private fun styleBox(doc: PDFDocument, a: PDFAnnotation, line: String?, fill: String?, width: Double, opacity: Double) {
        if (line == null && fill == null) throw DocOpError("선 색과 채우기 색이 둘 다 '없음'이면 상자가 보이지 않습니다.")
        if (width !in SHAPE_WIDTHS) throw DocOpError("모르는 선 굵기입니다: $width")
        if (!(opacity >= 0.1 && opacity <= 1)) throw DocOpError("투명도는 10~100% 사이로 골라 주세요.")
        val o = a.getObject()
        o.put("C", colorArray(doc, rgb(line)))
        // PC판은 채우기 없음이면 IC를 남기지 않는다(PyMuPDF update가 빈 IC를 지움)
        if (fill == null) o.delete("IC") else o.put("IC", colorArray(doc, rgb(fill)))
        // PyMuPDF set_border(JM_annot_set_border)처럼 BS를 직접 쓴다(MuPDF의 테두리 함수는 Rect를 따로 고친다)
        o.delete("BS"); o.delete("BE"); o.delete("Border")
        val bs = doc.newDictionary()
        bs.put("W", (if (line != null) width else 0.0).toFloat())
        bs.put("S", doc.newName("S"))
        o.put("BS", bs)
        a.setOpacity(opacity.toFloat())
        a.setFlags(PRINT_FLAG)
        a.update()
    }

    fun addBox(doc: PDFDocument, pno: Int, rect: PRect, line: String? = "#d00000", fill: String? = null,
               width: Double = 2.0, opacity: Double = 1.0): Int {
        val page = page(doc, pno)
        val r = rect.normalize() and PageGeo(page).bounds
        if (r.isEmpty || r.width < MIN_DRAG || r.height < MIN_DRAG) throw DocOpError("상자를 그릴 곳을 끌어서 정해 주세요.")
        val a = unrotated(page) {
            val a = page.createAnnotation(PDFAnnotation.TYPE_SQUARE)
            a.setRect(toRect(r))
            a.update()
            addAnnotId(doc, page, a, "A")
            a
        }
        a.setAuthor(AUTHOR)
        a.getObject().put("Subj", doc.newString(MARK))
        styleBox(doc, a, line, fill, width, opacity)
        return xrefOf(a)
    }

    fun setBoxStyle(doc: PDFDocument, pno: Int, xref: Int, line: String?, fill: String?, width: Double, opacity: Double) {
        val page = page(doc, pno)
        val a = ownAnnot(page, xref)
        if (a.type != PDFAnnotation.TYPE_SQUARE) throw DocOpError("상자가 아닙니다.")
        styleBox(doc, a, line, fill, width, opacity)
    }
}
