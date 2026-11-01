package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Quad
import com.bubigter.hanjangpdf.core.Annots
import com.bubigter.hanjangpdf.core.DocOps
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.PPoint
import com.bubigter.hanjangpdf.core.Widths
import com.bubigter.hanjangpdf.ui.BoxStyle
import com.bubigter.hanjangpdf.ui.Tool
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 8-4 주석 도구 5종·되돌리기·글자 고르기를 화면 모델([ViewerModel.tools])로 시험한다(쪽 좌표로 누르기·끌기를 넣음,
 * 에뮬 화면 탭은 쓰지 않음). 입력은 tests/android_compat/compat.py base로 만든 annots_base.pdf
 * (1쪽 400×600 글자 두 줄, 2쪽 /Rotate 90·CropBox 원점이 0이 아님).
 */
@RunWith(AndroidJUnit4::class)
class AnnotsTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val app get() = target.applicationContext as Application
    private val tmp = File(target.filesDir, "annots_test").apply { deleteRecursively(); mkdirs() }
    private val models = ArrayList<ViewerModel>()
    private val annots = Annots(target.assets.open(Widths.ASSET).use { Widths.load(it) })

    @After fun tearDown() {
        for (m in models) {
            val ds = onMain { listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { m.session?.failAt = null; ds.forEach { it.close() } }
        }
        tmp.deleteRecursively()
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

    /** 남이 넣은 형광펜(표식 없음) 하나가 든 견본을 만들어 모델로 연다. */
    private fun openBase(m: ViewerModel, name: String): File {
        val f = File(tmp, name)
        testCtx.assets.open("annots_base.pdf").use { Intake.copy(it, f) }
        val d = DocOps.open(f.path)
        val p = d.loadPage(0) as PDFPage
        val a = p.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
        a.addQuadPoint(Quad(50f, 350f, 150f, 350f, 50f, 365f, 150f, 365f))
        a.update()
        DocOps.atomicSave(d, f.path)
        d.destroy()
        val before = onMain { m.doc }
        onMain { m.openUri(Uri.fromFile(f)) }
        waitUntil("열기") { !m.busy && m.doc != null && m.doc !== before }
        return f
    }

    private fun newModel() = onMain { ViewerModel(app) }.also { models.add(it) }

    /** 지금 모델 문서에서 읽기(MuPDF 실행기). */
    private fun <T> onDoc(m: ViewerModel, f: (PDFDocument) -> T): T = runBlocking(m.mupdf) { f(m.doc!!.pdf) }

    /** 쪽의 주석 (종류, 내 주석, 글) 목록. */
    private fun list(d: PDFDocument, pno: Int): List<Triple<Int, Boolean, String>> =
        (d.loadPage(pno) as PDFPage).getAnnotations()?.map { Triple(it.type, annots.isOwn(it), annots.info(it, "Contents")) } ?: emptyList()

    private fun edit(m: ViewerModel, what: String, act: () -> Unit) {
        val g = onMain { m.gen }
        onMain(act)
        waitUntil(what) { !m.busy && m.gen != g }
    }

    private fun ownXref(d: PDFDocument, pno: Int, type: Int, text: String? = null): Int =
        (d.loadPage(pno) as PDFPage).getAnnotations()!!.first { it.type == type && annots.isOwn(it) && (text == null || annots.info(it, "Contents") == text) }
            .let { annots.xrefOf(it) }

    @Test fun a_eachToolThroughModel() {
        val m = newModel()
        openBase(m, "tools.pdf")
        val t = m.tools
        // 형광펜: 글자 위를 끌기
        onMain { t.tool = Tool.HIGHLIGHT; t.hlColor = "green" }
        edit(m, "형광펜") { t.drag(0, PPoint(45.0, 315.0), PPoint(200.0, 340.0)) }
        assertEquals(1, onDoc(m) { list(it, 0).count { a -> a.first == PDFAnnotation.TYPE_HIGHLIGHT && a.second } })
        // 메모: 누르면 창 → 붙이기, 붙인 메모를 누르면 고치기
        onMain { t.tool = Tool.NOTE; t.tap(0, PPoint(30.0, 560.0)) }
        waitUntil("메모 창") { t.note != null }
        assertNull(onMain { t.note!!.xref })
        edit(m, "메모") { t.commitNote("메모 하나") }
        assertNull(onMain { t.note })
        onMain { t.tap(0, PPoint(35.0, 565.0)) }
        waitUntil("메모 고치기 창") { t.note?.xref != null }
        assertEquals("메모 하나", onMain { t.note!!.text })
        edit(m, "메모 고침") { t.commitNote("메모 고침") }
        assertTrue(onDoc(m) { list(it, 0) }.any { it.first == PDFAnnotation.TYPE_TEXT && it.second && it.third == "메모 고침" })
        // 글자 상자: 누르면 기본 폭 칸 → 넣기
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("쓰는 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "안녕 글자")) }
        edit(m, "글자 넣기") { t.commitEditor() }
        assertNull(onMain { t.editor })
        val x1 = onDoc(m) { ownXref(it, 0, PDFAnnotation.TYPE_FREE_TEXT, "안녕 글자") }
        val st1 = onDoc(m) { annots.textStyle(it, 0, x1) }
        assertNotNull("끌어 만든 상자 꼴(14필드)", st1.box)
        // 넣은 상자를 눌러 열고 손잡이로 옮기기(글은 그대로, 자리만)
        onMain { t.tap(0, PPoint(70.0, 75.0)) }
        waitUntil("고치기 칸") { t.editor?.xref != null }
        assertEquals("안녕 글자", onMain { t.editor!!.text })
        onMain { t.moveEditor(40.0, 30.0) }
        edit(m, "옮기기") { t.commitEditor() }
        val st2 = onDoc(m) { annots.textStyle(it, 0, ownXref(it, 0, PDFAnnotation.TYPE_FREE_TEXT, "안녕 글자")) }
        assertEquals("오른쪽으로 40", st1.shown!!.x0 + 40, st2.shown!!.x0, 0.01)
        assertEquals("아래로 30", st1.shown!!.y0 + 30, st2.shown!!.y0, 0.01)
        assertEquals(1, onDoc(m) { list(it, 0).count { a -> a.first == PDFAnnotation.TYPE_FREE_TEXT } })
        // 돌린 쪽: 화면 방향으로 옮긴 만큼 화면에서 움직인다
        onMain { t.tap(1, PPoint(100.0, 300.0)) }
        waitUntil("돌린 쪽 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "돌린 쪽 글")) }
        edit(m, "돌린 쪽 넣기") { t.commitEditor() }
        val r1 = onDoc(m) { annots.textStyle(it, 1, ownXref(it, 1, PDFAnnotation.TYPE_FREE_TEXT, "돌린 쪽 글")).shown!! }
        val tapAt = PPoint((r1.x0 + r1.x1) / 2, (r1.y0 + r1.y1) / 2)
        onMain { t.tap(1, tapAt) }
        waitUntil("돌린 쪽 고치기 칸") { t.editor?.xref != null }
        onMain { t.moveEditor(25.0, 10.0) }
        edit(m, "돌린 쪽 옮기기") { t.commitEditor() }
        val r2 = onDoc(m) { annots.textStyle(it, 1, ownXref(it, 1, PDFAnnotation.TYPE_FREE_TEXT, "돌린 쪽 글")).shown!! }
        val v1 = onMain { m.doc!!.toView(1, r1) }; val v2 = onMain { m.doc!!.toView(1, r2) }
        assertEquals("화면 오른쪽으로 25", v1.x0 + 25, v2.x0, 0.01)
        assertEquals("화면 아래로 10", v1.y0 + 10, v2.y0, 0.01)
        // 상자: 끌어 그리기, 누르면 지금 색으로
        onMain { t.tool = Tool.BOX }
        edit(m, "상자") { t.drag(0, PPoint(220.0, 400.0), PPoint(350.0, 480.0)) }
        onMain { t.boxStyle = BoxStyle(null, "#00a000", 1.0, 0.5) }
        edit(m, "상자 색") { t.tap(0, PPoint(285.0, 440.0)) }
        onDoc(m) {
            val a = (it.loadPage(0) as PDFPage).getAnnotations()!!.first { a -> a.type == PDFAnnotation.TYPE_SQUARE }
            assertTrue("채우기", a.getObject().get("IC").isArray)
            assertEquals(0.5f, a.getOpacity(), 0.001f)
        }
        // 글자 고르기
        onMain { t.tool = Tool.SELECT }
        onMain { t.drag(0, PPoint(52.0, 325.0), PPoint(200.0, 325.0)) }
        waitUntil("고름") { t.selection != null }
        assertTrue("고른 글: ${onMain { t.selection!!.text }}", onMain { t.selection!!.text }.startsWith("Hello World"))
        // 지우개: 누르면 그것만, 끌면 범위의 내 주석만(남의 형광펜은 그대로)
        onMain { t.tool = Tool.ERASE }
        edit(m, "지우개 누름") { t.tap(0, PPoint(100.0, 330.0)) }
        assertEquals(0, onDoc(m) { list(it, 0).count { a -> a.first == PDFAnnotation.TYPE_HIGHLIGHT && a.second } })
        edit(m, "지우개 끌기") { t.drag(0, PPoint(20.0, 340.0), PPoint(390.0, 590.0)) }
        val left = onDoc(m) { list(it, 0) }
        assertTrue("남의 형광펜 그대로", left.any { it.first == PDFAnnotation.TYPE_HIGHLIGHT && !it.second })
        assertFalse("메모·상자 지움", left.any { (it.first == PDFAnnotation.TYPE_TEXT || it.first == PDFAnnotation.TYPE_SQUARE) && it.second })
        // 되돌리기: 지우개 끌기를 되돌리면 메모·상자가 돌아온다
        onMain { m.undo() }
        waitUntil("되돌림") { !m.busy }
        assertTrue(onDoc(m) { list(it, 0) }.any { it.first == PDFAnnotation.TYPE_SQUARE && it.second })
        onMain { m.redo() }
        waitUntil("다시") { !m.busy }
        assertFalse(onDoc(m) { list(it, 0) }.any { it.first == PDFAnnotation.TYPE_SQUARE && it.second })
    }

    /** 설계 7항: '기존 글자 상자 지우고 새 모양 만들기 실패' 뒤 문서·이력·저장 안 함 그대로, 쓰는 칸은 쓴 글 그대로 남음. */
    @Test fun b_textEditFailureRollsBack() {
        val m = newModel()
        openBase(m, "fail.pdf")
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "원래 글")) }
        edit(m, "넣기") { t.commitEditor() }
        val undoN = runBlocking(m.mupdf) { m.session!!.undoCount }
        val dirty = onMain { m.dirty }
        onMain { t.tap(0, PPoint(70.0, 75.0)) }
        waitUntil("고치기 칸") { t.editor?.xref != null }
        // 넘쳐서 거절되는 글(setText가 옛 상자를 지운 뒤 새 모양을 만들다 실패)
        val long = "아주 긴 글자 ".repeat(800)
        onMain { t.updateEditor(t.editor!!.copy(text = long, size = 40)) }
        edit(m, "실패") { t.commitEditor() }
        assertEquals("쓰는 칸은 쓴 글 그대로 남음", long, onMain { t.editor?.text })
        assertEquals("원래 상자 그대로", 1, onDoc(m) { list(it, 0).count { a -> a.first == PDFAnnotation.TYPE_FREE_TEXT && a.third == "원래 글" } })
        assertEquals("이력 그대로", undoN, runBlocking(m.mupdf) { m.session!!.undoCount })
        assertEquals("저장 안 함 그대로", dirty, onMain { m.dirty })
        onMain { t.cancelEditor() }
        // 일반 실패(편집 도중 오류): 지우개가 지웠다가 실패해도 그대로
        onMain { t.tool = Tool.ERASE }
        runBlocking(m.mupdf) { m.session!!.failAt = "op" }
        edit(m, "지우개 실패") { t.tap(0, PPoint(70.0, 75.0)) }
        runBlocking(m.mupdf) { m.session!!.failAt = null }
        assertEquals(1, onDoc(m) { list(it, 0).count { a -> a.first == PDFAnnotation.TYPE_FREE_TEXT && a.third == "원래 글" } })
        assertEquals(undoN, runBlocking(m.mupdf) { m.session!!.undoCount })
    }

    /** 주석 → 다른 이름으로 저장 → 다시 열기에서 보존, 되돌리기·다시 하기 뒤 저장도 보존. */
    @Test fun c_saveReopenUndoKeepsAnnots() {
        val m = newModel()
        openBase(m, "save.pdf")
        val t = m.tools
        onMain { t.tool = Tool.HIGHLIGHT }
        edit(m, "형광펜") { t.drag(1, PPoint(45.0, 215.0), PPoint(250.0, 240.0)) }
        onMain { t.tool = Tool.TEXT; t.tap(1, PPoint(100.0, 300.0)) }
        waitUntil("칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "저장할 글\n둘째 줄", align = "right", color = "#d00000")) }
        edit(m, "글자") { t.commitEditor() }
        onMain { t.tool = Tool.NOTE; t.tap(1, PPoint(80.0, 500.0)) }
        waitUntil("메모 창") { t.note != null }
        edit(m, "메모") { t.commitNote("저장할 메모") }
        val out1 = File(tmp, "saved1.pdf").apply { createNewFile() }
        var ok: Boolean? = null
        onMain { m.saveAs(Uri.fromFile(out1)) { ok = it } }
        waitUntil("저장") { !m.busy && ok != null }
        assertEquals(true, ok)
        assertFalse(onMain { m.dirty })
        val want = onDoc(m) { list(it, 1) }
        val re = DocOps.open(out1.path)
        assertEquals("다시 열어도 같음", want, list(re, 1))
        val x = (re.loadPage(1) as PDFPage).getAnnotations()!!.first { it.type == PDFAnnotation.TYPE_FREE_TEXT }.let { annots.xrefOf(it) }
        val st = annots.textStyle(re, 1, x)
        assertEquals(listOf("right", "#d00000"), listOf(st.align, st.color))
        re.destroy()
        // 되돌리기(메모 빼기) → 저장 안 함 → 다시 하기 → 저장한 판과 같음
        onMain { m.undo() }
        waitUntil("되돌림") { !m.busy && m.dirty }
        assertFalse(onDoc(m) { list(it, 1) }.any { it.first == PDFAnnotation.TYPE_TEXT })
        onMain { m.redo() }
        waitUntil("다시") { !m.busy && !m.dirty }
        assertEquals(want, onDoc(m) { list(it, 1) })
    }

    /**
     * 검토 판 1 지적 3: 쪽 아래 끝이라 위로 밀려 보이던 쓰는 칸은 보이던 자리에서부터 옮긴다(PC판 _move_editor의 ed._top).
     * 기대값은 PC판 코드(tests/android_compat/compat.py _pc_move + annots)로 같은 작업을 해 얻은 값.
     * 쪽 밖으로 끈 양은 쪽 끝에서 멈추고(clamp), 안 움직이고 닫으면 고치지 않는다(_same_rect).
     */
    @Test fun e_movePushedEditorLikePc() {
        val m = newModel()
        openBase(m, "pushed.pdf")
        val t = m.tools
        val text = "아래 끝 상자\n둘째 줄\n셋째 줄"
        fun shown() = onDoc(m) { annots.textStyle(it, 0, ownXref(it, 0, PDFAnnotation.TYPE_FREE_TEXT)).shown!! }
        onMain { t.tool = Tool.TEXT; t.drag(0, PPoint(50.0, 575.0), PPoint(250.0, 598.0)) }
        waitUntil("쓰는 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = text, size = 14)) }
        assertEquals("칸 테두리는 밀려 보이는 자리", 542.1, onMain { t.shownBox(t.editor!!) }.y0, 0.01)
        onMain { t.moveEditor(0.0, -10.0) }
        edit(m, "밀린 칸 옮겨 넣기") { t.commitEditor() }
        assertEquals("PC와 같이 보이던 자리에서 10 위", 532.1, shown().y0, 0.01)
        // 안 움직이고 닫음: 고치지 않음(이력·판 그대로)
        val undoN = runBlocking(m.mupdf) { m.session!!.undoCount }
        val g = onMain { m.gen }
        onMain { t.tap(0, PPoint(100.0, 570.0)) }
        waitUntil("고치기 칸") { t.editor?.xref != null }
        onMain { t.commitEditor() }
        // 마칠 때 모양을 MuPDF 실행기에서 다시 읽고 닫는다(검토 판 2 LOW)
        waitUntil("안 바뀐 칸 닫힘") { t.editor == null && !m.busy }
        assertEquals("판 그대로", g, onMain { m.gen })
        assertEquals("이력 그대로", undoN, runBlocking(m.mupdf) { m.session!!.undoCount })
        // 쪽 밖으로 끌기: 왼쪽·아래 끝에서 멈춤
        onMain { t.tap(0, PPoint(100.0, 570.0)) }
        waitUntil("고치기 칸 2") { t.editor?.xref != null }
        onMain { t.moveEditor(-100.0, 300.0) }
        val b = onMain { t.editor!!.box }
        assertEquals(0.0, b.x0, 0.01); assertEquals(600.0, b.y1, 0.01)
        edit(m, "끝까지 옮기기") { t.commitEditor() }
        val s3 = shown()
        assertEquals(0.0, s3.x0, 0.01); assertEquals(542.1, s3.y0, 0.01)
        // 글을 늘려 밀리게 된 칸을 옮김
        onMain { t.tap(0, PPoint(100.0, 570.0)) }
        waitUntil("고치기 칸 3") { t.editor?.xref != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "$text\n넷째 줄")) }
        onMain { t.moveEditor(20.0, -5.0) }
        edit(m, "늘린 칸 옮기기") { t.commitEditor() }
        val s4 = shown()
        assertEquals(20.0, s4.x0, 0.01); assertEquals(518.9, s4.y0, 0.01)
    }

    /** 화면이 실제로 그려지는지(도구 막대·쓰는 칸·손잡이·고른 글자 칠). 스크린샷을 files/ui_shots/에 남긴다(화면 탭 없음). */
    @Test fun d_uiSmoke() {
        val name = "ui_smoke.pdf"
        val cmd = "content call --uri content://com.bubigter.hanjangpdf.testdocs --method put --arg $name --extra write:b:true --extra asset:s:annots_base.pdf"
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        val uri = Uri.parse("content://com.bubigter.hanjangpdf.testdocs/$name")
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .setClassName(target, MainActivity::class.java.name)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val shots = File(target.filesDir, "ui_shots").apply { mkdirs() }
        // 앞 시험(복구)이 남긴 '보관한 원본' 창이 화면을 가리지 않게
        File(target.filesDir, "recovery_kept").deleteRecursively()
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(view).use { scn ->
            var mm: ViewerModel? = null
            scn.onActivity { mm = it.model }
            val m = mm!!
            waitUntil("열기") { !m.busy && m.doc != null }
            val t = m.tools
            onMain { t.visible = true; t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
            waitUntil("쓰는 칸") { t.editor != null }
            onMain { t.updateEditor(t.editor!!.copy(text = "화면 시험 글")) }
            instr.waitForIdleSync(); Thread.sleep(800)
            val b1 = instr.uiAutomation.takeScreenshot()
            assertNotNull(b1)
            File(shots, "editor.png").outputStream().use { b1.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            edit(m, "넣기") { t.commitEditor() }
            onMain { t.tool = Tool.SELECT; t.drag(0, PPoint(52.0, 325.0), PPoint(200.0, 360.0)) }
            waitUntil("고름") { t.selection != null }
            instr.waitForIdleSync(); Thread.sleep(800)
            val b2 = instr.uiAutomation.takeScreenshot()
            File(shots, "select.png").outputStream().use { b2.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(onMain { m.dirty })
        }
    }
}
