package com.bubigter.hanjangpdf.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.bubigter.hanjangpdf.R
import com.bubigter.hanjangpdf.core.Annots
import com.bubigter.hanjangpdf.core.PMatrix
import com.bubigter.hanjangpdf.core.PPoint
import com.bubigter.hanjangpdf.core.PRect
import com.bubigter.hanjangpdf.core.TextSelect
import com.bubigter.hanjangpdf.core.ViewDoc
import com.bubigter.hanjangpdf.core.Widths
import com.bubigter.hanjangpdf.core.pyMin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 쪽 위 도구(PC판 page_view의 모드와 같은 일). */
enum class Tool { VIEW, SELECT, HIGHLIGHT, NOTE, TEXT, BOX, ERASE }

/** 쓰는 칸이 떠 있는 글자 상자. [box]는 돌리기 전 쪽 좌표, [xref]가 있으면 그 상자를 고친다. */
data class TextEdit(val page: Int, val box: PRect, val xref: Int?, val text: String, val size: Int,
                    val align: String, val color: String, val orig: Annots.TextStyle? = null,
                    /** 칸을 새로 열 때마다 다른 번호(copy는 그대로: 입력 칸 상태를 이어 씀). */
                    val seq: Int = editorSeq.incrementAndGet())

private val editorSeq = java.util.concurrent.atomic.AtomicInteger()

/** 메모 창: [xref]가 있으면 고치기, 없으면 [point]에 붙이기. [orig]는 고치기 전 글. */
data class NoteEdit(val page: Int, val point: PPoint?, val xref: Int?, val text: String, val orig: String = "")

/** 고른 글자: 쪽, 낱말 사각형(돌리기 전 좌표), 글, 고를 때의 문서 판. */
data class Selection(val page: Int, val rects: List<PRect>, val text: String, val gen: Int)

/** 상자 모양(선·채우기 색 null = 없음). */
data class BoxStyle(val line: String?, val fill: String?, val width: Double, val opacity: Double)

/**
 * 주석 도구 5종·글자 고르기(설계 5항). 화면 좌표 변환은 화면이 하고, 여기서는 돌리기 전 쪽 좌표를 받는다.
 * 문서를 바꾸는 일은 모두 [ViewerModel.runEdit](8-3 EditSession: 실패 복구·되돌리기)로 한다.
 * 그 자리의 주석 찾기 같은 읽기는 MuPDF 실행기에서 한다.
 */
class AnnotTools(private val m: ViewerModel) {
    /** 도구 막대를 보이는지(화면을 돌려도 그대로). */
    var visible by mutableStateOf(false)
    var tool by mutableStateOf(Tool.VIEW)
    var hlColor by mutableStateOf("yellow")
    var textSize by mutableIntStateOf(12)
    var textAlign by mutableStateOf("left")
    var textColor by mutableStateOf("#000000")
    var boxStyle by mutableStateOf(BoxStyle("#d00000", null, 2.0, 1.0))
    var editor by mutableStateOf<TextEdit?>(null)
    var note by mutableStateOf<NoteEdit?>(null)
    var selection by mutableStateOf<Selection?>(null)

    /** 폭 표를 읽어 만든 주석 함수 묶음(MuPDF 실행기에서 처음 쓸 때 만든다). */
    private var annotsCache: Annots? = null
    internal val annots: Annots
        get() = annotsCache ?: Annots(m.getApplication<Application>().assets.open(Widths.ASSET).use { Widths.load(it) })
            .also { annotsCache = it }

    private fun str(id: Int, vararg a: Any) = m.getApplication<Application>().getString(id, *a)

    /**
     * 확인하지 않은 쓰는 칸·메모 글이 있는지(다른 문서를 열거나 앱을 닫기 전에 묻는다. 검토 8-4 판 2 HIGH).
     * 비어 있는 새 칸·메모와 연 뒤 바꾸지 않은 칸·메모는 아니다.
     */
    val unsaved: Boolean
        get() {
            val e = editor
            if (e != null && (if (e.xref == null) e.text.isNotBlank() else !unchanged(e.orig, e))) return true
            val n = note ?: return false
            return if (n.xref == null) n.text.isNotBlank() else Annots.pyStrip(n.text) != Annots.pyStrip(n.orig)
        }

    /** 다른 문서로 바꿀 때. 도구와 색은 그대로 둔다. */
    fun reset() {
        editor = null
        note = null
        selection = null
    }

    /** 읽기 하나를 MuPDF 실행기에서(문서가 닫혔으면 null). */
    private suspend fun <T> read(d: ViewDoc, block: (PDFDocument) -> T): T? =
        withContext(m.mupdf) { if (d.closed) null else block(d.pdf) }

    private fun launch(block: suspend () -> Unit) { m.viewModelScope.launch { block() } }

    /**
     * 누른 뒤 읽는 사이 문서가 바뀌었거나(다른 문서 열기), 다른 일이 시작됐거나, 이미 다른 칸·메모 창이 열렸으면 거짓
     * (검토 8-4 판 3 MEDIUM: 읽은 결과를 이전 문서의 상자 번호로 새 문서 위에 열거나, 쓰던 칸을 덮지 않게).
     */
    private fun stillFor(d: ViewDoc) = m.doc === d && !m.busy && editor == null && note == null

    /** 쪽을 그냥 눌렀다([pt]는 돌리기 전 쪽 좌표). */
    fun tap(page: Int, pt: PPoint) {
        val d = m.doc ?: return
        if (m.busy || editor != null) return
        when (tool) {
            Tool.VIEW -> launch {
                val hit = read(d) { annots.annotAt(it, page, pt, PDFAnnotation.TYPE_FREE_TEXT) } ?: return@launch
                if (!stillFor(d)) return@launch
                when (hit["type"]) {
                    PDFAnnotation.TYPE_FREE_TEXT -> openText(d, page, hit["xref"] as Int)
                    PDFAnnotation.TYPE_TEXT -> note = NoteEdit(page, null, hit["xref"] as Int, hit["text"] as String, hit["text"] as String)
                }
            }
            Tool.SELECT -> selection = null
            Tool.HIGHLIGHT -> m.say(str(R.string.hint_highlight))
            Tool.NOTE -> launch {
                val hit = read(d) { annots.annotAt(it, page, pt, PDFAnnotation.TYPE_TEXT) }
                if (!stillFor(d)) return@launch
                note = if (hit != null && hit["type"] == PDFAnnotation.TYPE_TEXT)
                    NoteEdit(page, null, hit["xref"] as Int, hit["text"] as String, hit["text"] as String)
                else NoteEdit(page, pt, null, "")
            }
            Tool.TEXT -> launch {
                val hit = read(d) { annots.annotAt(it, page, pt, PDFAnnotation.TYPE_FREE_TEXT) }
                if (!stillFor(d)) return@launch
                if (hit != null && hit["type"] == PDFAnnotation.TYPE_FREE_TEXT) openText(d, page, hit["xref"] as Int)
                else if (page < d.pageCount) editor = TextEdit(page, defaultBox(d, page, pt), null, "", textSize, textAlign, textColor)
            }
            Tool.BOX -> launch {
                val x = read(d) { annots.boxAt(it, page, pt) }
                if (!stillFor(d)) return@launch // 읽은 상자 번호는 그 문서의 것
                if (x == null) { m.say(str(R.string.hint_box)); return@launch }
                val st = boxStyle
                m.runAnnotEdit(str(R.string.done_box_style)) { annots.setBoxStyle(it, page, x, st.line, st.fill, st.width, st.opacity); x }
            }
            Tool.ERASE -> launch {
                val hit = read(d) { annots.annotAt(it, page, pt) }
                if (!stillFor(d)) return@launch
                if (hit == null) { m.say(str(R.string.hint_erase)); return@launch }
                m.runAnnotEdit(str(R.string.done_erase)) { annots.deleteAnnot(it, page, hit["xref"] as Int); 0 }
            }
        }
    }

    /** 쪽 위를 끌었다([a]→[b], 돌리기 전 쪽 좌표). */
    fun drag(page: Int, a: PPoint, b: PPoint) {
        val d = m.doc ?: return
        if (m.busy || editor != null) return
        val r = PRect(a.x, a.y, b.x, b.y).normalize()
        when (tool) {
            Tool.VIEW -> {}
            Tool.SELECT -> launch {
                val g = m.gen
                val ws = read(d) { TextSelect.between(TextSelect.pageWords(annots, annots.page(it, page)), a, b) } ?: return@launch
                if (m.doc !== d || m.gen != g) return@launch // 그사이 문서·판이 바뀜
                if (ws.isEmpty()) { selection = null; m.say(str(R.string.hint_select_none)); return@launch }
                selection = Selection(page, ws.map { it.rect }, TextSelect.text(ws), g)
                m.say(str(R.string.selected_words, ws.size))
            }
            Tool.HIGHLIGHT -> {
                val c = hlColor
                m.runAnnotEdit(str(R.string.done_highlight)) { annots.addHighlight(it, page, r, c) }
            }
            Tool.NOTE -> tap(page, a)
            Tool.TEXT -> editor = TextEdit(page, r, null, "", textSize, textAlign, textColor)
            Tool.BOX -> {
                val st = boxStyle
                m.runAnnotEdit(str(R.string.done_box)) { annots.addBox(it, page, r, st.line, st.fill, st.width, st.opacity) }
            }
            Tool.ERASE -> launch {
                val n = read(d) { annots.ownInRect(it, page, r).size } ?: return@launch
                if (!stillFor(d)) return@launch
                if (n == 0) { m.say(str(R.string.hint_erase_area)); return@launch }
                m.runAnnotEdit(null, { k -> str(R.string.done_erase_n, k) }) { annots.deleteInRect(it, page, r) }
            }
        }
    }

    /** PC판 text_at: 끌지 않고 누른 자리에 여는 기본 폭(200pt) 상자. */
    private fun defaultBox(d: ViewDoc, page: Int, pt: PPoint): PRect {
        val rot = d.rot(page)
        val vis = pt * rot
        val size = textSize
        val pw = d.sizes[page].w.toDouble()
        val x1 = pyMin(vis.x + DEFAULT_BOX_W, pw)
        val box = PRect(pyMin(vis.x, x1 - Annots.boxMinWidth(size)), vis.y, x1, vis.y + size * 1.6 + 8)
        return (box * PMatrix.invert(rot)).normalize()
    }

    /** 이 프로그램이 넣은 글자 상자를 쓰는 칸으로 연다(고치기 칸 자리는 text_style의 edit_box). */
    private suspend fun openText(d: ViewDoc, page: Int, xref: Int) {
        val st = try { read(d) { annots.textStyle(it, page, xref) } } catch (e: Exception) { m.say(e.message ?: ""); null } ?: return
        if (!stillFor(d)) return // 두 번째 읽기 사이에도 문서가 바뀔 수 있다
        editor = TextEdit(page, st.editBox, xref, st.text, st.size, st.align, st.color, st)
    }

    /**
     * 확인한 칸·메모를 넣는 중(문서 편집이 도는 중)이면 입력을 받지 않는다. 그때 받은 글은 성공 뒤 칸을 닫으며
     * 사라지기 때문이다(검토 8-4 판 3 HIGH, 판 2와 같은 종류). 화면도 이 동안 입력 칸·단추를 잠근다.
     */
    val inputLocked: Boolean get() = m.busy

    /** 쓰는 칸의 글·모양을 바꾼다(화면 입력). 받지 않으면(칸이 없음·다른 칸·[inputLocked]) 거짓. */
    fun updateEditor(e: TextEdit): Boolean {
        val cur = editor ?: return false
        if (cur.seq != e.seq || inputLocked) return false
        editor = e
        return true
    }

    /**
     * 손잡이로 끈 만큼(화면 방향 pt) 쓰는 상자를 옮긴다. 쪽 밖으로는 나가지 않는다(PC판 _move_editor).
     */
    fun moveEditor(dx: Double, dy: Double) {
        val e = editor ?: return
        if (inputLocked) return
        val d = m.doc ?: return
        if (e.page >= d.pageCount) return
        val rot = d.rot(e.page)
        val pw = d.sizes[e.page].w.toDouble(); val ph = d.sizes[e.page].h.toDouble()
        val moved = annots.moveEditorBox(e.box, rot, pw, ph, e.text, e.size, dx, dy) ?: return
        editor = e.copy(box = moved)
    }

    /**
     * 쓰는 칸 테두리·손잡이를 그릴 자리(돌리기 전 좌표). 쪽 아래 끝이라 넣을 때 위로 밀리면 밀린 자리
     * (PC판 text_box_editor._fit_height가 칸을 _top으로 올려 보여 주는 것과 같음), 아니면 [TextEdit.box].
     */
    fun shownBox(e: TextEdit): PRect {
        val d = m.doc ?: return e.box
        if (e.page >= d.pageCount) return e.box
        val rot = d.rot(e.page)
        val r = (e.box * rot).normalize()
        val top = annots.editorTop(rot, d.sizes[e.page].w.toDouble(), d.sizes[e.page].h.toDouble(), e.box, e.text, e.size)
        if (top == null || top >= r.y0) return e.box
        return (PRect(r.x0, top, r.x1, top + r.height) * PMatrix.invert(rot)).normalize()
    }

    fun cancelEditor() { editor = null }

    /** 다 쓴 글자 상자를 넣거나 고친다. 못 넣으면(넘침 등) 칸을 그대로 두어 쓴 글이 남는다. */
    fun commitEditor() {
        val e = editor ?: return
        if (m.busy) return
        if (e.xref == null) {
            if (e.text.isBlank()) { editor = null; return }
            // 성공하면 이 칸만 닫는다(넣는 동안 입력은 [inputLocked]로 잠겨 칸 글은 넣은 글 그대로)
            m.runAnnotEdit(str(R.string.done_text), onOk = { if (editor?.seq == e.seq) editor = null }) {
                annots.addTextBox(it, e.page, e.box, e.text, e.size, e.align, e.color)
            }
            return
        }
        val d = m.doc ?: return
        val xref = e.xref
        launch {
            // PC판 commit_text처럼 마칠 때 상자 모양을 다시 읽어 비교한다(연 때 읽은 [TextEdit.orig]가 아니라)
            val now = try { read(d) { annots.textStyle(it, e.page, xref) } } catch (ex: Exception) { null }
            val cur = editor ?: return@launch
            if (cur.seq != e.seq || m.busy || m.doc !== d) return@launch // 그사이 칸을 닫았거나 다른 일이 시작됐거나 문서가 바뀜
            if (unchanged(now, cur)) {
                editor = null // 바꾼 것이 없음(글도 자리도 그대로)
                return@launch
            }
            m.runAnnotEdit(str(R.string.done_text_edit), onOk = { if (editor?.seq == cur.seq) editor = null }) {
                annots.setText(it, cur.page, xref, cur.text, cur.size, cur.align, cur.color, null, cur.box)
            }
        }
    }

    fun cancelNote() { note = null }

    /** 메모 창의 글(화면 입력). 모델에 두어 화면을 돌려도 남고, 다른 문서를 열기 전에 물을 수 있다. */
    fun updateNote(text: String): Boolean {
        val n = note ?: return false
        if (inputLocked) return false // 붙이는 중에는 받지 않는다([inputLocked])
        note = n.copy(text = text)
        return true
    }

    fun commitNote(text: String) {
        val n = note ?: return
        if (m.busy) return
        if (text.isBlank()) { m.say(str(R.string.note_empty)); return }
        if (n.xref == null) {
            val p = n.point ?: return
            m.runAnnotEdit(str(R.string.done_note), onOk = { if (note === n) note = null }) { annots.addNote(it, n.page, p, text) }
        } else {
            if (Annots.pyStrip(text) == Annots.pyStrip(n.orig)) { note = null; return }
            m.runAnnotEdit(str(R.string.done_note_edit), onOk = { if (note === n) note = null }) { annots.setNoteText(it, n.page, n.xref, text); n.xref }
        }
    }

    companion object {
        /** 끌지 않고 눌렀을 때 여는 글자 상자 폭(pt, PC판 DEFAULT_BOX_W). */
        const val DEFAULT_BOX_W = 200.0
        val TEXT_COLORS = listOf("#000000", "#d00000", "#0050c8", "#00a000")
        val LINE_COLORS = listOf(null, "#d00000", "#0000ff", "#000000", "#00a000")
        val FILL_COLORS = listOf(null, "#ffff00", "#ffcccc", "#00a000", "#cccccc")
        val OPACITIES = listOf(1.0, 0.7, 0.5, 0.3)

        /** 두 사각형이 거의 같은가(PC판 _same_rect, 손잡이로 옮기지 않았으면 쓰는 칸 자리는 edit_box 그대로). */
        fun sameRect(a: PRect, b: PRect, tol: Double = 0.01) =
            Math.abs(a.x0 - b.x0) <= tol && Math.abs(a.y0 - b.y0) <= tol && Math.abs(a.x1 - b.x1) <= tol && Math.abs(a.y1 - b.y1) <= tol

        /** PC판 commit_text: 고치는 상자의 자리([sameRect])·글·모양이 [o](마칠 때 다시 읽은 모양)와 그대로면 고칠 것이 없다. */
        fun unchanged(o: Annots.TextStyle?, e: TextEdit): Boolean =
            o != null && sameRect(o.editBox, e.box) && e.text == o.text && e.size == o.size && e.align == o.align && e.color == o.color
    }
}
