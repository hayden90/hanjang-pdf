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
import com.bubigter.hanjangpdf.ui.Tool
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 8-4 판 4 남은 지적(8-5 묶음 0번) 재현. 화면 탭 없이 모델·core 함수로만 넣는다.
 * 고치기 전 코드로도 컴파일되게 예전부터 있던 함수·값만 쓴다('처리 중'은 MuPDF 실행기에 막는 일을 넣어 만든다).
 */
@RunWith(AndroidJUnit4::class)
class Fix85Test {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val app get() = target.applicationContext as Application
    private val tmp = File(target.filesDir, "fix85_test").apply { deleteRecursively(); mkdirs() }
    private val models = ArrayList<ViewerModel>()
    private val gates = ArrayList<CountDownLatch>()

    @After fun tearDown() {
        gates.forEach { it.countDown() }
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

    private fun block(m: ViewerModel, running: Boolean = true): CountDownLatch {
        val go = CountDownLatch(1).also { gates.add(it) }
        val inside = CountDownLatch(1)
        Thread { runBlocking(m.mupdf) { inside.countDown(); go.await() } }.start()
        if (running) inside.await() else Thread.sleep(300)
        return go
    }

    private fun addText(m: ViewerModel, text: String) {
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = text)) }
        val g = onMain { m.gen }
        onMain { t.commitEditor() }
        waitUntil("넣기") { !m.busy && m.gen != g && t.editor == null }
    }

    private fun ownTexts(m: ViewerModel, type: Int): List<String> = runBlocking(m.mupdf) {
        val p = m.doc!!.pdf.loadPage(0) as PDFPage
        (p.getAnnotations() ?: emptyArray()).filter { it.type == type && m.tools.annots.isOwn(it) }
            .map { m.tools.annots.info(it, "Contents") }
    }

    private class FileTarget(val file: File, val failWriteAfter: Int = -1) : SaveTarget {
        override fun openRead(): InputStream = file.inputStream()
        override fun openWrite(): OutputStream {
            val o = FileOutputStream(file)
            if (failWriteAfter < 0) return o
            // [failWriteAfter] 바이트까지 쓴 뒤 실패하는 대상(SAF 제공자가 도중에 끊긴 경우)
            return object : OutputStream() {
                var n = 0
                override fun write(b: Int) { if (n++ >= failWriteAfter) throw IOException("시험용 쓰기 실패"); o.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { for (i in 0 until len) write(b[off + i].toInt()) }
                override fun close() = o.close()
            }
        }
        override val id: String = Uri.fromFile(file).toString()
    }

    /** [rec]에 '끝나지 않은 덮어쓰기' 기록 하나(덮기 전 판 [old], 새 판 해시 [newSha]). */
    private fun pending(rec: File, uri: String, old: ByteArray, newSha: String): File {
        val d = File(rec, "s1_test").apply { mkdirs() }
        val b = File(d, Recovery.BEFORE).apply { writeBytes(old) }
        File(d, Recovery.JSON).writeText(JSONObject().put("uri", uri).put("title", "t.pdf")
            .put("before", Saver.sha256(b)).put("new", newSha).put("size", b.length()).put("time", 1L).toString())
        return d
    }

    /**
     * MEDIUM(opus 판 4 시나리오 E): 복구본 '다른 이름으로 저장' 대상이 그 기록의 원본(같은 URI)이고 원본이 새 판이면,
     * 고치기 전에는 Overwrite.run의 정리(settle)가 before.pdf를 지운 뒤 그 파일을 쓰려다 실패했다(복구본 소실).
     */
    @Test fun a_saveCopyToSameSourceKeepsBefore() {
        val rec = File(tmp, "rec")
        val cur = File(tmp, "cur.pdf").apply { writeText("NEW_UNIQUE") }
        val t = FileTarget(cur)
        pending(rec, t.id, "OLD".toByteArray(), Saver.sha256(cur))
        val p = Recovery.list(rec).single()
        val r = runCatching { Recovery.saveCopy(p, t, rec) }
        assertTrue("복구본 before.pdf가 남음(결과 $r)", p.before.exists())
        assertTrue("기록이 남음", Recovery.isOpen(p))
        assertEquals("원본 그대로", "NEW_UNIQUE", cur.readText())
    }

    /** MEDIUM, 화면 모델: 같은 원본을 고르면 물음을 조용히 닫지 않고 남긴 채 알린다. */
    @Test fun b_modelRecoverSaveAsSameSourceTellsUser() {
        File(app.filesDir, "recovery").deleteRecursively()
        val cur = File(tmp, "cur2.pdf").apply { writeText("NEW_UNIQUE") }
        val newSha = Saver.sha256(cur)
        cur.writeText("OTHER") // 물을 때는 덮기 전 판도 새 판도 아님
        val uri = Uri.fromFile(cur)
        pending(File(app.filesDir, "recovery"), uri.toString(), "OLD".toByteArray(), newSha)
        val m = newModel()
        waitUntil("복구 물음") { m.recovery != null }
        cur.writeText("NEW_UNIQUE") // 물음이 떠 있는 사이 원본이 새 판이 됨(시나리오 E)
        onMain { m.recoverSaveAs(uri) }
        Thread.sleep(300)
        waitUntil("끝") { !m.busy }
        assertNotNull("물음이 남음", onMain { m.recovery })
        assertNotNull("알림", onMain { m.message })
        assertTrue("복구본이 남음", File(app.filesDir, "recovery/s1_test/" + Recovery.BEFORE).exists())
    }

    /** LOW(opus 판 4): 빈 대상에 쓰다 실패하면 쓰다 만 내용이 남았다. 고친 뒤에는 원래대로 비운다. */
    @Test fun c_copyFailureLeavesEmptyTarget() {
        val src = File(tmp, "src.pdf").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val dst = File(tmp, "dst.pdf").apply { writeBytes(ByteArray(0)) }
        val r = runCatching { Saver.saveTo(src, FileTarget(dst, failWriteAfter = 70_000), File(tmp, "rec3"), "dst.pdf") }
        assertTrue("실패로 알림(결과 $r)", r.isFailure || r.getOrNull() !is com.bubigter.hanjangpdf.core.Overwrite.Result.Ok)
        assertEquals("쓰다 만 내용이 남지 않음", 0L, dst.length())
    }

    /** LOW(Fable 판 4): 찾기만 도는 중(문서 편집 아님)에는 쓰는 칸 입력을 받는다(inputLocked는 편집·저장 중만). */
    @Test fun d_inputNotLockedWhileSearching() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        val gate = block(m)
        onMain { m.query = "PDF"; m.runSearch() }
        waitUntil("찾는 중") { m.busy }
        val ok = onMain { t.updateEditor(t.editor!!.copy(text = "찾는 중 쓴 글")) }
        gate.countDown()
        waitUntil("찾기 끝") { !m.busy }
        assertTrue("찾는 중에도 입력을 받음", ok)
        assertEquals("찾는 중 쓴 글", onMain { t.editor?.text })
    }

    /** LOW(Fable 판 4): 고치는 칸을 확인했는데 다시 읽는 사이 다른 일이 시작돼 넣지 못하면 조용히 넘기지 않고 알린다. */
    @Test fun e_existingCommitSkippedTellsUser() {
        val m = newModel()
        open(m, base("a.pdf"))
        addText(m, "처음 글")
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(70.0, 75.0)) }
        waitUntil("고치기 칸") { t.editor?.xref != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "고친 글")); m.consumeMessage() }
        val g1 = block(m)
        onMain { t.commitEditor() } // 모양 다시 읽기가 g1 뒤에 줄 선다
        val g2 = block(m, running = false) // 찾기가 다시 읽기 뒤에서 막히게
        onMain { m.query = "PDF"; m.runSearch() }
        g1.countDown()
        Thread.sleep(800)
        val msg = onMain { m.message }
        g2.countDown()
        waitUntil("끝") { !m.busy }
        assertNotNull("넣지 못했다고 알림", msg)
        assertEquals("칸과 쓴 글은 남음", "고친 글", onMain { t.editor?.text })
        assertTrue("문서는 그대로", "고친 글" !in ownTexts(m, PDFAnnotation.TYPE_FREE_TEXT))
    }

    /** LOW(Fable 판 4): 확인한 칸을 넣는 중에는 '취소'도 받지 않는다(넣다 실패하면 쓴 글이 남아야 하므로). */
    @Test fun f_cancelIgnoredWhileCommitting() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "실패할 글")) }
        runBlocking(m.mupdf) { m.session!!.failAt = "op" }
        val gate = block(m)
        onMain { t.commitEditor() }
        waitUntil("넣는 중") { m.busy }
        onMain { t.cancelEditor() }
        gate.countDown()
        Thread.sleep(300)
        waitUntil("끝") { !m.busy }
        runBlocking(m.mupdf) { m.session!!.failAt = null }
        assertEquals("넣기 실패 뒤 쓴 글이 남음(넣는 중 취소는 받지 않음)", "실패할 글", onMain { t.editor?.text })
    }

    /**
     * LOW(opus·Fable 판 4): 입력 칸 → 모델 반영이 한 틀 늦어, 같은 틀에서 친 마지막 글자가 확인 때 빠질 수 있었다.
     * 확인 단추는 입력 칸의 지금 글을 넘기고, 모델은 그 글로 넣는다(고치기 전 판에는 넘길 인자가 없어 코드 읽기로만 확인).
     */
    @Test fun g_commitUsesFieldText() {
        val m = newModel()
        open(m, base("a.pdf"))
        val t = m.tools
        onMain { t.tool = Tool.TEXT; t.tap(0, PPoint(60.0, 60.0)) }
        waitUntil("새 칸") { t.editor != null }
        onMain { t.updateEditor(t.editor!!.copy(text = "AB")) } // 모델은 아직 한 글자 전
        val g = onMain { m.gen }
        onMain { t.commitEditor("ABC") }
        waitUntil("넣기") { !m.busy && m.gen != g && t.editor == null }
        assertEquals(listOf("ABC"), ownTexts(m, PDFAnnotation.TYPE_FREE_TEXT))
    }
}
