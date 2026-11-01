package com.bubigter.hanjangpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.PPoint
import com.bubigter.hanjangpdf.core.Recovery
import com.bubigter.hanjangpdf.core.Saver
import com.bubigter.hanjangpdf.ui.NoteEdit
import com.bubigter.hanjangpdf.ui.Tool
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 8-4 독립 검토 판 2 지적 재현: 확인하지 않은 글자 상자 쓰는 칸·메모 글이 문서 교체·편집·저장 때 묻지 않고 사라지거나
 * 빠진 판이 저장되는 경로(HIGH 1), 마칠 때 상자 모양 다시 읽기·보관본 저장 중복 막기(LOW 2).
 * 화면 탭 없이 화면 모델 함수로만 넣는다. 고치기 전 코드로도 컴파일되게 예전부터 있던 함수·값만 쓴다.
 */
@RunWith(AndroidJUnit4::class)
class Fix84r2Test {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val app get() = target.applicationContext as Application
    private val tmp = File(target.filesDir, "fix84r2_test").apply { deleteRecursively(); mkdirs() }
    private val models = ArrayList<ViewerModel>()

    @After fun tearDown() {
        for (m in models) {
            val ds = onMain { listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        tmp.deleteRecursively()
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovery_kept").deleteRecursively()
    }

    private fun <T> onMain(f: () -> T): T {
        var r: Any? = null
        instr.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun waitUntil(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(20)
        }
        fail("기다림 초과: $what")
    }

    private fun newModel() = onMain { ViewerModel(app) }.also { models.add(it) }

    /** annots_base.pdf 사본(1쪽 400×600). */
    private fun base(name: String): Uri {
        val f = File(tmp, name)
        testCtx.assets.open("annots_base.pdf").use { Intake.copy(it, f) }
        return Uri.fromFile(f)
    }

    private fun open(m: ViewerModel, uri: Uri) {
        val before = onMain { m.doc }
        onMain { m.openUri(uri) }
        waitUntil("열기 $uri") { !m.busy && m.pendingOpen == null && m.doc != null && m.doc !== before }
    }

    /** 1쪽 (60,60)에 글자 상자를 넣는다(쓰는 칸 → 확인). */
    private fun addText(m: ViewerModel, text: String) {
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = text)) }
        val g = onMain { m.gen }
        onMain { t.commitEditor() }
        waitUntil("넣기") { !m.busy && m.gen != g && t.editor == null }
    }

    /** 넣은 상자를 눌러 고치기 칸으로 연다. */
    private fun openExisting(m: ViewerModel) {
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(70.0, 75.0)) }
        waitUntil("고치기 칸") { t.editor?.xref != null }
    }

    /** 문서의 내 글자 상자 글. */
    private fun ownText(m: ViewerModel): String = runBlocking(m.mupdf) {
        val p = m.doc!!.pdf.loadPage(0) as PDFPage
        val a = p.getAnnotations()!!.first { it.type == PDFAnnotation.TYPE_FREE_TEXT && m.tools.annots.isOwn(it) }
        m.tools.annots.info(a, "Contents")
    }

    /** HIGH 1: 쓰는 칸에 글을 쓰고 확인하기 전에 메뉴로 다른 문서를 열면 묻는다. 취소하면 칸 그대로, 버리면 연다. */
    @Test fun a_menuOpenAsksForTypedEditor() {
        val m = newModel()
        val a = base("a.pdf"); val b = base("b.pdf")
        open(m, a)
        val docA = onMain { m.doc }
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "쓰던 글")) }
        onMain { m.openUri(b) }
        waitUntil("열기 물음 또는 열림") { !m.busy && (m.pendingOpen != null || m.doc !== docA) }
        assertNotNull("확인 안 한 쓰는 칸이 있으면 묻는다", onMain { m.pendingOpen })
        assertSame("문서는 그대로", docA, onMain { m.doc })
        onMain { m.cancelOpen() }
        assertEquals("취소하면 쓰던 글 그대로", "쓰던 글", onMain { t.editor?.text })
        onMain { m.openUri(b) }
        waitUntil("다시 물음") { m.pendingOpen != null }
        onMain { m.confirmOpen() }
        waitUntil("버리고 열기") { !m.busy && m.doc != null && m.doc !== docA }
        assertNull("버리고 열면 칸이 닫힘", onMain { t.editor })
    }

    /** HIGH 1: 메모 창에 글을 쓰는 중(새 메모·고치는 메모)에 다른 문서를 열면 묻는다. */
    @Test fun b_openAsksForTypedNote() {
        val m = newModel()
        val a = base("a.pdf"); val b = base("b.pdf")
        open(m, a)
        val docA = onMain { m.doc }
        val t = m.tools
        // 메모 창이 쓰는 글처럼 모델의 note.text에 넣는다(화면 입력 칸이 하는 일)
        onMain { t.note = NoteEdit(0, PPoint(30.0, 560.0), null, ""); t.note = t.note!!.copy(text = "메모 쓰는 중") }
        onMain { m.openUri(b) }
        waitUntil("물음 또는 열림") { !m.busy && (m.pendingOpen != null || m.doc !== docA) }
        assertNotNull("새 메모 글이 있으면 묻는다", onMain { m.pendingOpen })
        assertEquals("메모 글 그대로", "메모 쓰는 중", onMain { t.note?.text })
        onMain { m.cancelOpen() }
        // 있던 메모를 고치는 중
        onMain { t.note = NoteEdit(0, null, 5, "원래 메모", "원래 메모"); t.note = t.note!!.copy(text = "고친 메모") }
        onMain { m.openUri(b) }
        waitUntil("물음 또는 열림 2") { !m.busy && (m.pendingOpen != null || m.doc !== docA) }
        assertNotNull("고친 메모 글이 있으면 묻는다", onMain { m.pendingOpen })
        onMain { m.cancelOpen(); t.cancelNote() }
    }

    /** HIGH 1: 다른 앱의 '열기'(onCreate·onNewIntent가 부름)와 도는 중에 미뤄 둔 열기도 묻는다. */
    @Test fun c_intentAndQueuedOpenAsk() {
        val m = newModel()
        val a = base("a.pdf"); val b = base("b.pdf"); val c = base("c.pdf")
        open(m, a)
        val docA = onMain { m.doc }
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "쓰던 글")) }
        onMain { m.openIntent(Intent(Intent.ACTION_VIEW, b)) }
        waitUntil("Intent 물음 또는 열림") { !m.busy && (m.pendingOpen != null || m.doc !== docA) }
        assertEquals("다른 앱 열기도 묻는다", b, onMain { m.pendingOpen?.first })
        onMain { m.cancelOpen() }
        // 찾기가 도는 중에 들어온 열기는 미뤘다가 끝나면 연다: 그때도 묻는다
        onMain { m.query = "a"; m.runSearch(); m.openUri(c) }
        waitUntil("미룬 열기 처리") { !m.busy && m.queuedOpen == null && (m.pendingOpen != null || m.doc !== docA) }
        assertEquals("미룬 열기도 묻는다", c, onMain { m.pendingOpen?.first })
        assertEquals("쓰던 글 그대로", "쓰던 글", onMain { t.editor?.text })
        onMain { m.cancelOpen() }
    }

    /** 쓴 것이 없으면 묻지 않는다(빈 새 칸, 열고 안 고친 칸). */
    @Test fun d_untouchedInputOpensDirectly() {
        val m = newModel()
        val a = base("a.pdf"); val b = base("b.pdf")
        open(m, a)
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("빈 칸") { t.editor != null }
        open(m, b)
        assertNull("빈 칸은 그냥 닫힘", onMain { t.editor })
        // 저장한 파일 b에 상자를 넣고 저장(편집 없는 상태로) → 안 고친 칸을 열고 다른 문서 열기
        addText(m, "있던 글")
        val saved = File(tmp, "b_saved.pdf").apply { writeBytes(ByteArray(0)) }
        var ok = false
        onMain { m.saveAs(Uri.fromFile(saved)) { ok = it } }
        waitUntil("저장") { !m.busy }
        assertTrue("저장 됨", ok)
        openExisting(m)
        open(m, a)
        assertNull("안 고친 칸은 그냥 닫힘", onMain { t.editor })
    }

    /**
     * HIGH 1과 같은 꼴: 쓰는 칸·메모 창이 떠 있는 동안 문서 되돌리기·쪽 작업·저장·공유를 하면 칸이 가리키는 상자가 바뀌거나
     * 확인 안 한 글이 빠진 판이 저장된다. 고친 뒤에는 하지 않고 알린다(칸 글 그대로).
     */
    @Test fun e_editsAndSavesWaitForInput() {
        val m = newModel()
        open(m, base("a.pdf"))
        addText(m, "처음 글")
        openExisting(m)
        val t = m.tools
        onMain { t.updateEditor(t.editor!!.copy(text = "고치는 중")) }
        val bad = ArrayList<String>()
        fun same(what: String, act: () -> Unit) {
            val g = onMain { m.gen }
            val n = runBlocking(m.mupdf) { m.session!!.undoCount }
            onMain(act)
            Thread.sleep(300)
            waitUntil("$what 끝") { !m.busy }
            if (onMain { m.gen } != g || runBlocking(m.mupdf) { m.session!!.undoCount } != n) bad.add("$what: 문서가 바뀜")
            if (onMain { t.editor?.text } != "고치는 중") bad.add("$what: 쓰는 칸 글이 사라짐")
        }
        same("되돌리기") { m.undo() }
        same("쪽 돌리기") { m.rotatePages(listOf(0), 90) }
        same("쪽 지우기") { m.deletePages(listOf(0)) }
        val dest = File(tmp, "dest.pdf").apply { writeBytes(ByteArray(0)) }
        same("다른 이름으로 저장") { m.saveAs(Uri.fromFile(dest)) }
        if (dest.length() != 0L) bad.add("다른 이름으로 저장: 확인 안 한 글이 빠진 판을 씀")
        same("공유") { m.share() }
        if (onMain { m.lastShared } != null) bad.add("공유: 확인 안 한 글이 빠진 판을 공유함")
        val out = File(tmp, "export.pdf").apply { writeBytes(ByteArray(0)) }
        same("쪽 내보내기") { m.exportPages(listOf(0), Uri.fromFile(out)) }
        if (out.length() != 0L) bad.add("쪽 내보내기: 확인 안 한 글이 빠진 판을 씀")
        // 메모 창이 떠 있을 때도
        onMain { t.cancelEditor(); t.note = NoteEdit(0, PPoint(30.0, 560.0), null, "메모") }
        val g = onMain { m.gen }
        onMain { m.undo() }
        Thread.sleep(300)
        waitUntil("메모 중 되돌리기 끝") { !m.busy }
        if (onMain { m.gen } != g) bad.add("메모 창 중 되돌리기: 문서가 바뀜")
        onMain { t.cancelNote() }
        assertTrue("막혀야 할 경로: $bad", bad.isEmpty())
        assertEquals("상자 글 그대로", "처음 글", ownText(m))
    }

    /** LOW 2: 고치는 칸을 마칠 때 상자 모양을 다시 읽어 비교한다(PC판 commit_text). 연 뒤 문서 쪽 글이 바뀌었으면 칸 글로 고친다. */
    @Test fun f_commitRereadsStyle() {
        val m = newModel()
        open(m, base("a.pdf"))
        addText(m, "처음 글")
        openExisting(m)
        val t = m.tools
        val e = onMain { t.editor!! }
        // 칸이 열린 동안 문서 쪽 글이 바뀜(연 때 읽은 모양과 달라짐). 같은 상자(xref 그대로)의 Contents만 바꾼다.
        val g = onMain { m.gen }
        onMain {
            m.runAnnotEdit(null) { d ->
                val p = d.loadPage(e.page) as PDFPage
                p.getAnnotations()!!.first { t.annots.xrefOf(it) == e.xref }.setContents("바뀐 글")
                e.xref!!
            }
        }
        waitUntil("문서 쪽 고침") { !m.busy && m.gen != g }
        assertEquals("바뀐 글", ownText(m))
        onMain { t.commitEditor() }
        waitUntil("칸 닫힘") { !m.busy && t.editor == null }
        assertEquals("칸 글(처음 글)로 고침", "처음 글", ownText(m))
    }

    /** LOW 2: 다른 일이 도는 중이면 보관본 '다른 이름으로 저장'을 하지 않는다. */
    @Test fun g_keptSaveAsWaitsWhenBusy() {
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovery_kept").deleteRecursively()
        val kd = File(app.filesDir, "recovery_kept/k1_test").apply { mkdirs() }
        val kf = File(kd, Recovery.CURRENT)
        testCtx.assets.open("annots_base.pdf").use { Intake.copy(it, kf) }
        File(kd, Recovery.INFO).writeText(JSONObject().put("uri", "content://x/kept.pdf").put("title", "kept.pdf")
            .put("size", kf.length()).put("sha", Saver.sha256(kf)).put("time", 1L).toString())
        val m = newModel()
        waitUntil("보관본 물음") { m.kept != null }
        open(m, base("a.pdf"))
        val dest = File(tmp, "kept_dest.pdf").apply { writeBytes(ByteArray(0)) }
        onMain { m.query = "a"; m.runSearch(); m.keptSaveAs(Uri.fromFile(dest)) }
        Thread.sleep(300)
        waitUntil("끝") { !m.busy }
        assertEquals("도는 중에는 쓰지 않음", 0L, dest.length())
    }
}
