package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.PPoint
import com.bubigter.hanjangpdf.core.Recovery
import com.bubigter.hanjangpdf.core.SaveTarget
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * 8-4 독립 검토 판 3 지적 재현: 되돌리기 직전 지금 원본을 못 읽으면 보관 없이 덮음(HIGH 1), 확인 뒤 처리 중 바꾼 입력이
 * 사라짐(HIGH 2), 비동기 읽기 뒤 문서·칸이 바뀌었는지 보지 않고 상태를 넣음(MEDIUM 3).
 * 화면 탭 없이 모델 함수로만 넣는다. 고치기 전 코드로도 컴파일되게 예전부터 있던 함수·값만 쓴다.
 * '처리 중'은 MuPDF 실행기에 막는 일을 넣어 만든다([block]).
 */
@RunWith(AndroidJUnit4::class)
class Fix84r3Test {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val app get() = target.applicationContext as Application
    private val tmp = File(target.filesDir, "fix84r3_test").apply { deleteRecursively(); mkdirs() }
    private val models = ArrayList<ViewerModel>()
    private val gates = ArrayList<CountDownLatch>()

    @After fun tearDown() {
        gates.forEach { it.countDown() }
        for (m in models) {
            val ds = onMain { listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        tmp.walkTopDown().forEach { it.setReadable(true, false) }
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

    /**
     * MuPDF 실행기에 막는 일 하나를 넣는다(돌려준 것을 countDown하면 풀림). [running]이면 그 일이 실제로 돌기 시작할 때까지
     * 기다리고, 아니면 줄에 들어갈 만큼만 잠깐 기다린다(앞에 다른 일이 있을 때).
     */
    private fun block(m: ViewerModel, running: Boolean = true): CountDownLatch {
        val go = CountDownLatch(1).also { gates.add(it) }
        val inside = CountDownLatch(1)
        Thread { runBlocking(m.mupdf) { inside.countDown(); go.await() } }.start()
        if (running) inside.await() else Thread.sleep(300)
        return go
    }

    /** 1쪽 (60,60)에 글자 상자를 넣는다. */
    private fun addText(m: ViewerModel, text: String) {
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = text)) }
        val g = onMain { m.gen }
        onMain { t.commitEditor() }
        waitUntil("넣기") { !m.busy && m.gen != g && t.editor == null }
    }

    /** 문서의 내 주석([type]) 글들. */
    private fun ownTexts(m: ViewerModel, type: Int): List<String> = runBlocking(m.mupdf) {
        val p = m.doc!!.pdf.loadPage(0) as PDFPage
        (p.getAnnotations() ?: emptyArray()).filter { it.type == type && m.tools.annots.isOwn(it) }
            .map { m.tools.annots.info(it, "Contents") }
    }

    private class FileTarget(val file: File, val failFirstReads: Int = 0) : SaveTarget {
        var reads = 0
        override fun openRead(): InputStream {
            if (reads++ < failFirstReads) throw IOException("시험용 읽기 실패")
            return file.inputStream()
        }
        override fun openWrite(): OutputStream = FileOutputStream(file)
        override val id: String = Uri.fromFile(file).toString()
    }

    /** [rec]에 '끝나지 않은 덮어쓰기' 기록 하나(덮기 전 판 [old], 새 판 해시는 아무 값)를 만든다. */
    private fun pending(rec: File, uri: String, old: ByteArray): File {
        val d = File(rec, "s1_test").apply { mkdirs() }
        val b = File(d, Recovery.BEFORE).apply { writeBytes(old) }
        File(d, Recovery.JSON).writeText(JSONObject().put("uri", uri).put("title", "t.pdf")
            .put("before", Saver.sha256(b)).put("new", "0".repeat(64)).put("size", b.length()).put("time", 1L).toString())
        return d
    }

    /**
     * HIGH 1(Codex 재현 그대로): 되돌리기 직전 지금 원본의 첫 읽기만 실패하면, 고치기 전에는 보관 없이 원본을 옛 판으로 덮고
     * 기록까지 지웠다. 고친 뒤에는 원본에 쓰기 전에 멈추고 기록을 남긴다.
     */
    @Test fun a_restoreStopsWhenCurrentUnreadable() {
        val rec = File(tmp, "rec"); val keep = File(tmp, "keep")
        val cur = File(tmp, "cur.pdf").apply { writeText("NEW_UNIQUE") }
        val t = FileTarget(cur, failFirstReads = 1)
        pending(rec, t.id, "OLD".toByteArray())
        val p = Recovery.list(rec).single()
        val r = runCatching { Recovery.restore(p, t, keep) }
        val keptN = keep.listFiles()?.size ?: 0
        assertEquals("지금 원본 그대로(보관 없이 덮지 않음, 결과 $r, 보관 $keptN)", "NEW_UNIQUE", cur.readText())
        assertTrue("기록은 남음", Recovery.isOpen(p))
        assertTrue("되돌리기는 성공이 아님", r.getOrNull() != true)
    }

    /** HIGH 1, 화면 모델: 원본을 아예 읽을 수 없으면 '원본 복구'를 눌러도 원본에 쓰지 않고 기록을 남긴 채 알린다. */
    @Test fun b_modelRestoreKeepsUnreadableOriginal() {
        File(app.filesDir, "recovery").deleteRecursively()
        val cur = File(tmp, "cur2.pdf").apply { writeText("NEW_UNIQUE") }
        pending(File(app.filesDir, "recovery"), Uri.fromFile(cur).toString(), "OLD".toByteArray())
        cur.setReadable(false, false)
        val m = newModel()
        waitUntil("복구 물음") { m.recovery != null }
        onMain { m.recoverRestore() }
        Thread.sleep(300)
        waitUntil("복구 끝") { !m.busy }
        cur.setReadable(true, false)
        assertEquals("원본 그대로", "NEW_UNIQUE", cur.readText())
        assertTrue("기록은 남음", File(app.filesDir, "recovery/s1_test/" + Recovery.JSON).exists())
        assertNotNull("알림", onMain { m.message })
    }

    /**
     * HIGH 1에서 같이 훑은 꼴: saving.json이 아직 바꿔 끼워지지 않고 tmp만 있을 때, tmp를 '읽지 못하면'(쓰다 만 것이 아니라)
     * 고치기 전에는 쓰다 만 것으로 보고 before.pdf째 지웠다. 고친 뒤에는 건너뛰고 남긴다.
     */
    @Test fun c_listKeepsUnreadableJsonTmp() {
        val rec = File(tmp, "rec3")
        val d = pending(rec, "content://x/t.pdf", "OLD".toByteArray())
        val j = File(d, Recovery.JSON)
        val tmpJ = File(d, Recovery.JSON + ".tmp")
        assertTrue(j.renameTo(tmpJ))
        tmpJ.setReadable(false, false)
        Recovery.list(rec)
        assertTrue("읽지 못한 기록은 지우지 않음", File(d, Recovery.BEFORE).exists())
        tmpJ.setReadable(true, false)
        assertEquals("읽을 수 있게 되면 다시 보임", 1, Recovery.list(rec).size)
    }

    /** 처리 중에 바꾼 입력이 남았는지: 받았으면([accepted]) 칸이나 문서에 남아야 한다. */
    private fun assertKept(what: String, accepted: Boolean, inEditor: Boolean, inDoc: Boolean) {
        assertTrue("$what: 처리 중 받은 입력이 사라짐(칸 $inEditor, 문서 $inDoc)", !accepted || inEditor || inDoc)
    }

    /** HIGH 2: 새 글자 상자를 확인한 뒤 넣는 중에 더 친 글·모양·자리가 사라지지 않는다. */
    @Test fun d_newTextInputWhileCommitting() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "A")) }
        val gate = block(m)
        onMain { t.commitEditor() }
        waitUntil("처리 중") { m.busy }
        val box0 = onMain { t.editor!!.box }
        onMain { t.updateEditor(t.editor!!.copy(text = "AB", size = 20)); t.moveEditor(30.0, 30.0) }
        val e1 = onMain { t.editor }
        val acceptedText = e1?.text == "AB"
        val acceptedMove = e1 != null && e1.box != box0
        gate.countDown()
        waitUntil("처리 끝") { !m.busy }
        val e2 = onMain { t.editor }
        val docs = ownTexts(m, PDFAnnotation.TYPE_FREE_TEXT)
        assertKept("글", acceptedText, e2?.text == "AB", "AB" in docs)
        assertKept("자리", acceptedMove, e2 != null && e2.box == e1!!.box, false)
        assertEquals("확인한 글은 들어감", listOf("A"), docs.filter { it == "A" })
    }

    /** HIGH 2: 있던 글자 상자를 고치는 칸도(마칠 때 다시 읽은 뒤 고치는 중) 더 친 글이 사라지지 않는다. */
    @Test fun e_existingTextInputWhileCommitting() {
        val m = newModel()
        open(m, base("a.pdf"))
        addText(m, "처음 글")
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(70.0, 75.0)) }
        waitUntil("고치기 칸") { t.editor?.xref != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "A")) }
        val g1 = block(m)
        onMain { t.commitEditor() } // 모양 다시 읽기가 g1 뒤에 줄 선다
        val g2 = block(m, running = false) // 고치기는 g2 뒤에 줄 서게
        g1.countDown()
        waitUntil("고치는 중") { m.busy }
        onMain { t.updateEditor(t.editor!!.copy(text = "AB")) }
        val accepted = onMain { t.editor?.text } == "AB"
        g2.countDown()
        waitUntil("처리 끝") { !m.busy }
        assertKept("고치는 칸 글", accepted, onMain { t.editor?.text } == "AB", "AB" in ownTexts(m, PDFAnnotation.TYPE_FREE_TEXT))
    }

    /** HIGH 2: 메모를 확인한 뒤 붙이는 중에 더 친 글이 사라지지 않는다. */
    @Test fun f_noteInputWhileCommitting() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.note = NoteEdit(0, PPoint(30.0, 560.0), null, ""); t.updateNote("메모A") }
        val gate = block(m)
        onMain { t.commitNote(t.note!!.text) }
        waitUntil("처리 중") { m.busy }
        onMain { t.updateNote("메모AB") }
        val accepted = onMain { t.note?.text } == "메모AB"
        gate.countDown()
        waitUntil("처리 끝") { !m.busy }
        assertKept("메모 글", accepted, onMain { t.note?.text } == "메모AB", "메모AB" in ownTexts(m, PDFAnnotation.TYPE_TEXT))
    }

    /** 확정 실패: 메모를 붙이다 실패하면 메모 창과 쓴 글이 남는다(쓰는 칸 실패는 AnnotsTest b_). */
    @Test fun g_noteCommitFailureKeepsText() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.note = NoteEdit(0, PPoint(30.0, 560.0), null, ""); t.updateNote("남을 메모") }
        runBlocking(m.mupdf) { m.session!!.failAt = "op" }
        onMain { t.commitNote(t.note!!.text) }
        Thread.sleep(300)
        waitUntil("실패 끝") { !m.busy }
        runBlocking(m.mupdf) { m.session!!.failAt = null }
        assertEquals("메모 글 그대로", "남을 메모", onMain { t.note?.text })
        assertTrue("문서에는 없음", ownTexts(m, PDFAnnotation.TYPE_TEXT).isEmpty())
    }

    /**
     * MEDIUM 3: 있던 글자 상자를 누른 뒤(읽는 중) 다른 문서가 열리면, 고치기 전에는 칸이 이전 문서의 상자 번호를 가리킨 채
     * 새 문서 위에 열렸다(확인하면 새 문서의 같은 번호 상자를 덮어씀). 고친 뒤에는 열지 않는다.
     */
    @Test fun h_tapThenDocSwappedDoesNotOpenStaleEditor() {
        val m = newModel()
        open(m, base("a.pdf"))
        addText(m, "A의 상자")
        val docA = onMain { m.doc }
        val b = base("b.pdf")
        val t = m.tools
        onMain { t.tool = Tool.VIEW }
        val gate = block(m)
        onMain { t.tap(0, PPoint(70.0, 75.0)) } // 주석 찾기가 막힌 뒤에 줄 선다
        onMain { m.openUri(b, force = true) } // 저장 안 한 편집은 버리고 연다(물음 없이)
        Thread.sleep(500) // 새 문서 열기(Intake.open)도 줄에 들어가게
        gate.countDown()
        waitUntil("B 열림") { !m.busy && m.doc != null && m.doc !== docA }
        Thread.sleep(300)
        assertNull("이전 문서의 상자로 칸을 열지 않음", onMain { t.editor })
    }

    /** MEDIUM 3과 같은 꼴: 두 번 누른 읽기가 겹치면, 먼저 열린 칸에 쓴 글을 뒤 읽기가 새 칸으로 덮었다. */
    @Test fun i_secondTapDoesNotReplaceTypedEditor() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.tool = Tool.TEXT }
        val g1 = block(m)
        onMain { t.tap(0, PPoint(60.0, 60.0)) }
        val g2 = block(m, running = false)
        onMain { t.tap(0, PPoint(60.0, 300.0)) }
        g1.countDown()
        waitUntil("첫 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "쓴 글")) }
        g2.countDown()
        Thread.sleep(500)
        waitUntil("끝") { !m.busy }
        assertEquals("쓴 글 그대로", "쓴 글", onMain { t.editor?.text })
    }

    /** MEDIUM 3과 같은 꼴(ViewerModel): 끼워 넣을 PDF의 암호를 묻는 중에 다른 문서를 열면 그 물음은 닫힌다(새 문서에 넣지 않게). */
    @Test fun j_insertPasswordClosedOnDocSwap() {
        val m = newModel()
        open(m, base("a.pdf"))
        val locked = File(tmp, "locked.pdf")
        testCtx.assets.open("pages_locked.pdf").use { Intake.copy(it, locked) }
        onMain { m.insertFrom(Uri.fromFile(locked), 0) }
        waitUntil("암호 물음") { m.insertPw != null }
        open(m, base("b.pdf"))
        assertNull("다른 문서를 열면 끼워 넣기 암호 물음은 닫힘", onMain { m.insertPw })
    }

    /** LOW 4: 다른 프로그램이 앞뒤 공백을 넣은 메모를 열고 안 고쳤으면 '확인 안 한 글'로 묻지 않는다. */
    @Test fun k_untouchedNoteWithSpacesIsNotUnsaved() {
        val m = newModel()
        val t = m.tools
        onMain { t.note = NoteEdit(0, null, 5, " 다른 프로그램 메모\n", " 다른 프로그램 메모\n") }
        assertEquals("안 고친 메모는 묻지 않음", false, onMain { t.unsaved })
        onMain { t.cancelNote() }
    }
}
