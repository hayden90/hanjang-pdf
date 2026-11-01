package com.bubigter.hanjangpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import com.bubigter.hanjangpdf.core.Annots
import com.bubigter.hanjangpdf.core.DocOpError
import com.bubigter.hanjangpdf.core.EditSession
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.NoSpaceError
import com.bubigter.hanjangpdf.core.OpenResult
import com.bubigter.hanjangpdf.core.Overwrite
import com.bubigter.hanjangpdf.core.PRect
import com.bubigter.hanjangpdf.core.PageOps
import com.bubigter.hanjangpdf.core.Recovery
import com.bubigter.hanjangpdf.core.SaveTarget
import com.bubigter.hanjangpdf.core.Saver
import com.bubigter.hanjangpdf.core.ShareStore
import com.bubigter.hanjangpdf.core.SimulatedDeath
import com.bubigter.hanjangpdf.core.ViewDoc
import com.bubigter.hanjangpdf.core.Widths
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 8-3 쪽 작업·되돌리기·저장·공유 계측 시험.
 * a_는 결과 PDF를 files/pages/에 남기고 맥의 tests/android_compat/pages.py compare가 PC판 결과와 대조한다.
 * 나머지는 기기 안에서 스스로 판정한다.
 */
@RunWith(AndroidJUnit4::class)
class PagesTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val args = InstrumentationRegistry.getArguments()
    private val tmp = File(target.filesDir, "pages_test").apply { deleteRecursively(); mkdirs() }
    private val out = File(
        if (args.getString("dir") == "internal") target.filesDir else target.getExternalFilesDir(null), "pages").apply { mkdirs() }
    private val sc = JSONObject(testCtx.assets.open("pages_scenario.json").bufferedReader().readText())
    private val opened = ArrayList<ViewDoc>()
    private val models = ArrayList<ViewerModel>()

    @After fun tearDown() {
        opened.forEach { it.close() }
        for (m in models) {
            val ds = onMain { listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        Overwrite.hook = null
        tmp.deleteRecursively()
    }

    private fun asset(name: String, to: File = File(tmp, name)): File {
        testCtx.assets.open(name).use { Intake.copy(it, to) }
        return to
    }

    private fun openOk(f: File, pw: String? = null): ViewDoc {
        var r = Intake.open(f.path)
        if (r is OpenResult.NeedsPassword) r = r.doc.unlock(pw ?: "") ?: OpenResult.Broken("암호 틀림")
        assertTrue("열림 $r", r is OpenResult.Ok)
        return (r as OpenResult.Ok).doc.also { opened.add(it) }
    }

    private fun session(v: ViewDoc, name: String, free: (File) -> Long = { it.usableSpace }) =
        EditSession(v, File(tmp, "work_$name"), File(tmp, "undo_$name"), 1L, free)

    /** 쪽마다 맨 앞 글(P1 등)과 회전값. */
    private fun labels(d: PDFDocument): List<String> = (0 until d.countPages()).map { i ->
        val p = d.loadPage(i)
        try {
            val t = p.toStructuredText().asText().trim().lineSequence().firstOrNull() ?: ""
            "$t@${PageOps.rotation(d, i)}"
        } finally { p.destroy() }
    }

    // ---- a: 시나리오(PC 대조는 맥에서) ----

    @Test fun a_scenarios() {
        val list = sc.getJSONArray("scenarios")
        for (k in 0 until list.length()) {
            val s = list.getJSONObject(k)
            val name = s.getString("name")
            val v = openOk(asset("pages_case.pdf", File(tmp, "case_$name.pdf")))
            val es = session(v, name)
            val ops = s.getJSONArray("ops")
            val errors = JSONArray()
            val outPdf = File(out, "an_$name.pdf").apply { delete() }
            var exported = false
            for (i in 0 until ops.length()) {
                val op = ops.getJSONObject(i)
                val before = labels(v.pdf)
                val ver = es.version
                try {
                    when (op.getString("op")) {
                        "rotate" -> es.edit { PageOps.rotate(it, ints(op, "pages"), op.getInt("deg")) }
                        "delete" -> es.edit { PageOps.delete(it, ints(op, "pages")) }
                        "move" -> es.edit { PageOps.move(it, ints(op, "pages"), op.getInt("to")) }
                        "insert" -> {
                            val f = asset(op.getString("file"), File(tmp, "ins_${name}_$i.pdf"))
                            es.edit { d ->
                                val src = PageOps.openSource(f.path, op.optString("password").ifEmpty { null })
                                try { PageOps.insertPdf(d, src, op.getInt("at")) } finally { src.destroy() }
                            }
                        }
                        "export" -> {
                            val n = PageOps.exportPages(v.pdf, ints(op, "pages"), outPdf.path)
                            assertEquals(ints(op, "pages").toSet().size, n)
                            assertEquals("내보내기는 작업 문서를 바꾸지 않음", before, labels(v.pdf))
                            exported = true
                        }
                    }
                    errors.put(false)
                } catch (e: DocOpError) {
                    errors.put(e.message)
                    // 오류면 편집 전 그대로(문서·판 번호)
                    assertEquals("$name[$i] 오류 뒤 문서 그대로", before, labels(v.pdf))
                    assertEquals("$name[$i] 오류 뒤 판 번호 그대로", ver, es.version)
                }
            }
            if (!exported) Saver.saveVerified(v.pdf, outPdf)
            File(out, "an_$name.json").writeText(JSONObject().put("errors", errors).put("labels", JSONArray(labels(v.pdf))).toString(1))
        }
    }

    private fun ints(o: JSONObject, k: String): List<Int> = o.getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } }

    // ---- b: 되돌리기 한도 ----

    @Test fun b_historyLimits() {
        // 50단계: 55번 돌리면 이력은 50개, 50번 되돌리면 5번 돌린 판(90도)
        val v = openOk(asset("pages_case.pdf"))
        val es = session(v, "steps")
        repeat(55) { es.edit { PageOps.rotate(it, listOf(0), 90) } }
        assertEquals(50, es.undoCount)
        repeat(50) { assertTrue(es.undo()) }
        assertFalse(es.canUndo)
        assertEquals(90, PageOps.rotation(v.pdf, 0))
        assertTrue(es.redo())
        assertEquals(180, PageOps.rotation(v.pdf, 0))
        // 다시 하기 뒤 새 편집이면 다시 하기 이력은 버림
        es.edit { PageOps.rotate(it, listOf(1), 90) }
        assertFalse(es.canRedo)

        // 합계 한도: 3판 남짓만
        val v2 = openOk(asset("pages_case.pdf", File(tmp, "bytes.pdf")))
        val es2 = session(v2, "bytes")
        es2.edit { PageOps.rotate(it, listOf(0), 90) }
        val one = es2.historyBytes
        es2.limitBytes = one * 3 + one / 2
        repeat(9) { es2.edit { PageOps.rotate(it, listOf(0), 90) } }
        assertTrue("이력 ${es2.undoCount}", es2.undoCount in 1..3)
        assertTrue("합계 ${es2.historyBytes} <= ${es2.limitBytes}", es2.historyBytes <= es2.limitBytes)
        assertFalse(es2.singleStep)

        // 한도보다 큰 문서: 직전 판 하나만
        val v3 = openOk(asset("pages_case.pdf", File(tmp, "big.pdf")))
        val es3 = session(v3, "big")
        es3.limitBytes = 1000 // 견본(약 7KB)이 한도보다 큼
        repeat(3) { es3.edit { PageOps.rotate(it, listOf(0), 90) } }
        assertTrue(es3.singleStep)
        assertEquals(1, es3.undoCount)
        assertTrue(es3.undo())
        assertFalse(es3.undo())
        assertEquals(180, PageOps.rotation(v3.pdf, 0))
    }

    @Test fun c_noSpaceBlocksEdit() {
        val v = openOk(asset("pages_case.pdf"))
        var free = 0L
        val es = EditSession(v, File(tmp, "w_ns"), File(tmp, "u_ns"), 10_000L) { free }
        val before = labels(v.pdf)
        try {
            es.edit { PageOps.rotate(it, listOf(0), 90) }
            fail("공간 부족인데 편집됨")
        } catch (e: NoSpaceError) {
            assertEquals(20_000L, e.need)
        }
        assertEquals(before, labels(v.pdf))
        assertEquals(0L, es.version)
        assertFalse(File(tmp, "w_ns/pre.pdf").exists())
        // 문서 크기의 2배 공간이 있으면 된다
        free = 20_000L
        es.edit { PageOps.rotate(it, listOf(0), 90) }
        assertEquals(90, PageOps.rotation(v.pdf, 0))
    }

    // ---- d: 편집 실패 복구 ----

    @Test fun d_failureRollsBack() {
        val v = openOk(asset("pages_case.pdf"))
        val es = session(v, "fail")
        es.edit { PageOps.rotate(it, listOf(2), 90) } // 저장 안 한 편집 하나
        assertTrue(es.dirty)
        val before = labels(v.pdf)
        val pdfBefore = v.pdf
        val ver = es.version
        val undo = es.undoCount
        val sizes = v.sizes
        // 여러 쪽 작업 도중 실패: 돌리기·지우기·옮기기를 한 뒤 실패
        try {
            es.edit { d ->
                PageOps.rotate(d, listOf(0, 1), 90)
                PageOps.delete(d, listOf(4))
                PageOps.move(d, listOf(0), 5)
                assertNotEquals(before, labels(d))
                throw DocOpError("중간 실패")
            }
            fail("실패해야 함")
        } catch (e: DocOpError) {
            assertEquals("중간 실패", e.message)
        }
        assertEquals("내용 그대로", before, labels(v.pdf))
        assertNotEquals("편집 전 사본을 다시 열었음", pdfBefore, v.pdf)
        assertEquals("판 번호 그대로", ver, es.version)
        assertTrue("저장 안 함 그대로", es.dirty)
        assertEquals("이력 그대로", undo, es.undoCount)
        assertEquals("쪽 크기 그대로", sizes, v.sizes)
        // 끼워 넣기 도중 실패(넣은 뒤 단계에서 실패하게)
        es.failAt = "op"
        try {
            es.edit { d ->
                val src = PageOps.openSource(asset("pages_insert.pdf").path, null)
                try { PageOps.insertPdf(d, src, 1) } finally { src.destroy() }
            }
            fail("실패해야 함")
        } catch (e: DocOpError) { /* 시험용 실패 */ }
        es.failAt = null
        assertEquals(before, labels(v.pdf))
        assertEquals(8, v.pdf.countPages())
        // 복구한 문서로 계속 편집·되돌리기가 된다
        es.edit { PageOps.delete(it, listOf(7)) }
        assertEquals(7, v.pdf.countPages())
        assertTrue(es.undo())
        assertEquals(before, labels(v.pdf))
        assertTrue(es.undo())
        assertFalse(es.dirty)
        // 편집 전 사본을 못 만들면 편집을 시작하지 않는다
        es.failAt = "snapshot"
        try { es.edit { PageOps.rotate(it, listOf(0), 90) }; fail() } catch (e: DocOpError) { }
        es.failAt = null
        assertEquals(0, PageOps.rotation(v.pdf, 0))
    }

    // ---- e: 저장·다시 열기, 저장 뒤 이어서 편집 ----

    @Test fun e_saveReopenAndContinue() {
        val annots = Annots(target.assets.open(Widths.ASSET).use { Widths.load(it) })
        val v = openOk(asset("pages_case.pdf"))
        val es = session(v, "save")
        es.edit { PageOps.rotate(it, listOf(0), 90) }
        es.edit { PageOps.move(it, listOf(7), 0) }
        val s1 = File(tmp, "s1.pdf")
        Saver.saveVerified(v.pdf, s1)
        es.markSaved()
        assertFalse(es.dirty)
        val l1 = labels(v.pdf)
        // 저장 뒤 닫지 않고 쪽 작업 + 글자 상자, 다시 저장
        es.edit { PageOps.delete(it, listOf(3)) }
        assertTrue(es.dirty)
        es.edit { annots.addTextBox(it, 0, PRect(72.0, 400.0, 300.0, 480.0), "저장 뒤 상자\n둘째 줄", 14, "left", "#112233") }
        val s2 = File(tmp, "s2.pdf")
        Saver.saveVerified(v.pdf, s2)
        es.markSaved()
        val r1 = openOk(s1)
        assertEquals("첫 저장 파일은 그대로", l1, labels(r1.pdf))
        val r2 = openOk(s2)
        assertEquals(labels(v.pdf), labels(r2.pdf))
        assertEquals(Saver.countAnnots(v.pdf), Saver.countAnnots(r2.pdf))
        val p = r2.pdf.loadPage(0) as PDFPage
        val ft = p.annotations.filter { it.type == com.artifex.mupdf.fitz.PDFAnnotation.TYPE_FREE_TEXT }
        p.destroy()
        assertEquals(1, ft.size)
        assertEquals("저장 뒤 상자\n둘째 줄", ft[0].contents)
        // 둘째 상자 → 또 저장
        annots.addTextBox(v.pdf, 1, PRect(72.0, 400.0, 300.0, 480.0), "둘째 상자", 12, "right", "#000000")
        val s3 = File(tmp, "s3.pdf")
        Saver.saveVerified(v.pdf, s3)
        val r3 = openOk(s3)
        val q = r3.pdf.loadPage(1) as PDFPage
        assertEquals(listOf("둘째 상자"), q.annotations.filter { it.type == com.artifex.mupdf.fitz.PDFAnnotation.TYPE_FREE_TEXT }.map { it.contents })
        q.destroy()
    }

    @Test fun f_encryptedSavesWithoutPassword() {
        val v = openOk(asset("pages_locked.pdf"), "pw")
        val es = session(v, "enc")
        es.edit { PageOps.rotate(it, listOf(0), 90) }
        es.undo(); es.redo() // 되돌리기 사본도 암호 없이 다시 열려야 함
        val s = File(tmp, "enc_saved.pdf")
        Saver.saveVerified(v.pdf, s)
        val r = Intake.open(s.path)
        assertTrue("암호 없이 열림 $r", r is OpenResult.Ok)
        (r as OpenResult.Ok).doc.also { opened.add(it) }
        assertEquals(listOf("L1@90"), labels(r.doc.pdf))
    }

    // ---- g: 덮어쓰기 안전성 ----

    /** 파일 대상 + 고장 흉내. */
    private class FaultTarget(val file: File) : SaveTarget {
        var failWrites = emptySet<Int>() // 이 차례의 쓰기는 1000바이트 쓴 뒤 실패
        var failOpens = emptySet<Int>()  // 이 차례의 "wt" 열기 자체가 실패(원본에 손대지 않음)
        var truncateThenFail = emptySet<Int>() // 이 차례는 원본을 비운 뒤 열기 실패
        var corrupt = false               // 다음 쓰기 한 번은 바이트 하나를 바꿔 씀
        var writes = 0
        override val id get() = Uri.fromFile(file).toString()
        override fun openRead(): InputStream = file.inputStream()
        override fun openWrite(): OutputStream {
            writes++
            if (writes in failOpens) throw IOException("시험용 열기 실패")
            if (writes in truncateThenFail) { FileOutputStream(file).close(); throw IOException("시험용 비운 뒤 열기 실패") }
            val fail = writes in failWrites
            val failAfter = 1000L
            val bad = corrupt
            corrupt = false
            val raw = FileOutputStream(file)
            return object : FilterOutputStream(raw) {
                var n = 0L
                override fun write(b: ByteArray, off: Int, len: Int) {
                    for (i in off until off + len) write(b[i].toInt())
                }
                override fun write(b: Int) {
                    if (fail && n >= failAfter) throw IOException("시험용 쓰기 실패")
                    raw.write(if (bad && n == 100L) b xor 0x55 else b)
                    n++
                }
            }
        }
    }

    private fun sha(f: File) = Saver.sha256(f)

    /** 편집 → 검증 파일. */
    private fun verifiedAfter(v: ViewDoc, es: EditSession, deg: Int, name: String): File {
        es.edit { PageOps.rotate(it, listOf(0), deg) }
        return File(tmp, name).also { Saver.saveVerified(v.pdf, it) }
    }

    @Test fun g_overwriteSequenceAndFailures() {
        val rec = File(tmp, "rec")
        val orig = asset("pages_case.pdf", File(tmp, "user_original.pdf")) // '원본'(A)
        val shaA = sha(orig)
        val t = FaultTarget(orig)
        val v = openOk(asset("pages_case.pdf", File(tmp, "work_orig.pdf")))
        val es = session(v, "ow")
        // A → B 성공
        val b = verifiedAfter(v, es, 90, "B.pdf")
        assertTrue(Overwrite.run(t, b, rec, "doc") is Overwrite.Result.Ok)
        assertEquals(sha(b), sha(orig))
        assertTrue("표시 정리", Recovery.list(rec).isEmpty() && (rec.listFiles()?.isEmpty() ?: true))
        // C는 쓰다 실패 → 원본은 A가 아니라 B
        val c = verifiedAfter(v, es, 90, "C.pdf")
        t.failWrites = setOf(t.writes + 1)
        val r = Overwrite.run(t, c, rec, "doc")
        assertTrue("되돌림 $r", r is Overwrite.Result.Restored)
        assertEquals("원본은 직전 저장판 B", sha(b), sha(orig))
        assertNotEquals(shaA, sha(orig))
        assertTrue(rec.listFiles()?.isEmpty() ?: true)
        // 쓴 내용이 다름(SHA 불일치) → 되돌림
        t.corrupt = true
        val r2 = Overwrite.run(t, c, rec, "doc")
        assertTrue("불일치 되돌림 $r2", r2 is Overwrite.Result.Restored)
        assertEquals(sha(b), sha(orig))
        // 되써 넣기도 실패 → 복구본 다른 이름으로 저장 안내
        t.failWrites = setOf(t.writes + 1, t.writes + 2)
        val r3 = Overwrite.run(t, c, rec, "doc")
        assertTrue("되써 넣기 실패 $r3", r3 is Overwrite.Result.RestoreFailed)
        val p = (r3 as Overwrite.Result.RestoreFailed).pending
        assertEquals(sha(b), p.beforeSha)
        assertEquals(1, Recovery.list(rec).size)
        val copy = FaultTarget(File(tmp, "rescued.pdf"))
        Recovery.saveCopy(p, copy)
        assertEquals(sha(b), sha(copy.file))
        assertTrue(Recovery.list(rec).isEmpty())
        // 원본을 읽을 수 없으면 손대지 않음
        val gone = FaultTarget(File(tmp, "missing.pdf"))
        assertTrue(Overwrite.run(gone, c, rec, "doc") is Overwrite.Result.Untouched)
        assertFalse(gone.file.exists())
        assertTrue(rec.listFiles()?.isEmpty() ?: true)
    }

    /** 덮는 도중 프로세스가 죽은 것을 단계마다 흉내 낸 뒤, 다음 실행의 복구. */
    @Test fun h_overwriteDeathRecovery() {
        val v = openOk(asset("pages_case.pdf", File(tmp, "w.pdf")))
        val es = session(v, "death")
        val nw = verifiedAfter(v, es, 90, "new.pdf")
        for (step in listOf("before", "json", "write", "written", "verify", "cleanup")) {
            val rec = File(tmp, "rec_$step")
            val orig = asset("pages_case.pdf", File(tmp, "orig_$step.pdf"))
            val shaA = sha(orig)
            val t = FaultTarget(orig)
            Overwrite.hook = { if (it == step) throw SimulatedDeath(it) }
            try {
                Overwrite.run(t, nw, rec, "doc_$step")
                fail("$step: 죽지 않음")
            } catch (e: SimulatedDeath) { /* 죽음 */ }
            Overwrite.hook = null
            // 다음 실행
            val pending = Recovery.list(rec)
            when (step) {
                "before" -> {
                    assertTrue("$step: 표시 없음", pending.isEmpty())
                    assertTrue("$step: 남은 폴더 정리", rec.listFiles()?.isEmpty() ?: true)
                    assertEquals(shaA, sha(orig))
                }
                "json" -> {
                    assertEquals(1, pending.size)
                    assertEquals("$step: 원본 그대로라 묻지 않음", true, Recovery.originalIsBefore(pending[0], t))
                    Recovery.finish(pending[0])
                }
                "written", "verify", "cleanup" -> {
                    // 다 쓴 뒤 죽음: 원본은 이미 새 판 → 저장은 끝났으므로 묻지 않고 정리(판 1 MEDIUM)
                    assertEquals(1, pending.size)
                    assertEquals("$step: 원본은 새 판", Recovery.State.NEW, Recovery.state(pending[0], t))
                    assertEquals(sha(nw), sha(orig))
                    Recovery.finish(pending[0])
                }
                else -> {
                    // 쓰는 도중 죽음: 원본이 덮기 전과도 새 판과도 다름 → '원본 복구'
                    assertEquals("$step 표시", 1, pending.size)
                    assertEquals("$step", "doc_$step", pending[0].title)
                    assertEquals("$step: 원본이 바뀜", Recovery.State.OTHER, Recovery.state(pending[0], t))
                    assertEquals(false, Recovery.originalIsBefore(pending[0], t))
                    assertTrue(Recovery.restore(pending[0], t))
                    assertEquals("$step: 원본 복구", shaA, sha(orig))
                }
            }
            assertTrue("$step: 처리 뒤 표시 없음", Recovery.list(rec).isEmpty())
        }
    }

    // ---- i: 화면 모델: 작업 폴더 정리·저장 안 함 물음·복구 물음 ----

    private fun <T> onMain(f: () -> T): T {
        var r: Any? = null
        instr.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun newModel(): ViewerModel =
        onMain { ViewerModel(target.applicationContext as Application) }.also { models.add(it) }

    private fun waitUntil(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(20)
        }
        fail("기다림 초과: $what")
    }

    private fun openInModel(m: ViewerModel, uri: Uri) {
        val before = onMain { m.doc }
        onMain { m.openUri(uri) }
        waitUntil("열기 $uri") { !m.busy && m.pendingOpen == null && m.doc != null && m.doc !== before }
    }

    /** 덮기 도중(쓰기 시작 직후) 죽어 남은 표시를 만든다(원본은 비워져 덮기 전과도 새 판과도 다름). */
    private fun leavePending(m: ViewerModel, orig: File): String {
        val v = openOk(asset("pages_case.pdf", File(tmp, "pend_w.pdf")))
        val es = session(v, "pend")
        val nw = verifiedAfter(v, es, 90, "pend_new.pdf")
        val shaA = sha(orig)
        Overwrite.hook = { if (it == "write") throw SimulatedDeath(it) }
        try { Overwrite.run(FaultTarget(orig), nw, m.recRoot, "pending.pdf") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        return shaA
    }

    @Test fun i_modelKeepsRecoveryAndAsks() {
        val app = target.applicationContext as Application
        File(app.filesDir, "recovery").deleteRecursively()
        val orig = asset("pages_case.pdf", File(tmp, "model_orig.pdf"))
        val m0 = newModel()
        val shaA = leavePending(m0, orig)
        val recDir = File(app.filesDir, "recovery")
        assertFalse("복구 표시는 work/ 밖", recDir.canonicalPath.startsWith(File(app.filesDir, "work").canonicalPath))
        // 문서를 두 번 바꿔 열어도(작업 폴더 정리) 복구 표시는 남는다
        val m = newModel()
        waitUntil("복구 물음") { m.recovery != null }
        openInModel(m, Uri.fromFile(asset("pages_case.pdf", File(tmp, "x1.pdf"))))
        openInModel(m, Uri.fromFile(asset("pages_insert.pdf", File(tmp, "x2.pdf"))))
        assertEquals(1, Recovery.list(recDir).size)
        assertTrue(onMain { m.recoveryReadable })
        onMain { m.recoverRestore() }
        waitUntil("복구 끝") { !m.busy && m.recovery == null }
        assertEquals(shaA, sha(orig))
        assertTrue(Recovery.list(recDir).isEmpty())
        // 원본이 이미 덮기 전 판이면 묻지 않고 조용히 정리
        val orig2 = asset("pages_case.pdf", File(tmp, "model_orig2.pdf"))
        val v = openOk(asset("pages_case.pdf", File(tmp, "q.pdf")))
        val nw = verifiedAfter(v, session(v, "q"), 90, "q_new.pdf")
        Overwrite.hook = { if (it == "json") throw SimulatedDeath(it) }
        try { Overwrite.run(FaultTarget(orig2), nw, recDir, "quiet.pdf") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        val m2 = newModel()
        waitUntil("정리") { Recovery.list(recDir).isEmpty() }
        assertNull(onMain { m2.recovery })
    }

    @Test fun j_modelAsksBeforeDiscardingEdits() {
        val m = newModel()
        val a = asset("pages_case.pdf", File(tmp, "dirty_a.pdf"))
        val b = asset("pages_insert.pdf", File(tmp, "dirty_b.pdf"))
        openInModel(m, Uri.fromFile(a))
        onMain { m.rotatePages(listOf(0, 1), 90) }
        waitUntil("편집") { !m.busy && m.dirty }
        val d = onMain { m.doc }
        onMain { m.openUri(Uri.fromFile(b)) }
        waitUntil("물음") { m.pendingOpen != null }
        assertTrue("문서 그대로", onMain { m.doc } === d)
        onMain { m.cancelOpen() }
        assertTrue(onMain { m.dirty })
        assertTrue(onMain { m.canUndo })
        onMain { m.undo() }
        waitUntil("되돌림") { !m.busy && !m.dirty }
        onMain { m.redo() }
        waitUntil("다시") { !m.busy && m.dirty }
        onMain { m.openUri(Uri.fromFile(b)) }
        waitUntil("물음2") { m.pendingOpen != null }
        onMain { m.confirmOpen() }
        waitUntil("새 문서") { !m.busy && m.doc != null && m.doc !== d }
        assertEquals(3, onMain { m.doc!!.pageCount })
        assertFalse(onMain { m.dirty })
    }

    // ---- n~u: 독립 검토 판 1 지적(고치기 전에는 실패) ----

    /** 판 1 HIGH(Codex): 같은 원본의 미해결 복구 기록이 남은 채 새 저장이 성공하면, 나중 복구가 최신 저장을 옛 판으로 덮는다. */
    @Test fun n_staleRecoveryBlocksNewSave() {
        val rec = File(tmp, "rec_stale")
        val orig = asset("pages_case.pdf", File(tmp, "stale_orig.pdf"))
        val shaA = sha(orig)
        val t = FaultTarget(orig)
        val v = openOk(asset("pages_case.pdf", File(tmp, "stale_w.pdf")))
        val es = session(v, "stale")
        val c = verifiedAfter(v, es, 90, "stale_C.pdf")
        Overwrite.hook = { if (it == "write") throw SimulatedDeath(it) }
        try { Overwrite.run(t, c, rec, "doc"); fail("죽지 않음") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        assertEquals("복구를 미룸", 1, Recovery.list(rec).size)
        // 같은 원본에 B 저장: 미해결 복구가 있으면 덮지 않는다
        val b = verifiedAfter(v, es, 90, "stale_B.pdf")
        val shaBroken = sha(orig)
        val writes = t.writes
        val r = Overwrite.run(t, b, rec, "doc")
        assertFalse("미해결 복구가 있는데 덮음 $r", r is Overwrite.Result.Ok)
        assertTrue("먼저 처리하게 물음 $r", r is Overwrite.Result.Unresolved && r.readable && r.pending.uri == t.id)
        assertEquals("원본에 손대지 않음", writes, t.writes)
        assertEquals(shaBroken, sha(orig))
        assertEquals("기록 그대로", 1, Recovery.list(rec).size)
        // 복구를 처리한 뒤에는 저장된다
        assertTrue(Recovery.restore(Recovery.list(rec)[0], t))
        assertEquals(shaA, sha(orig))
        assertTrue(Overwrite.run(t, b, rec, "doc") is Overwrite.Result.Ok)
        assertEquals(sha(b), sha(orig))
        assertTrue(Recovery.list(rec).isEmpty())
        // 남은 기록이 이미 끝난 저장(원본 == 그 새 판)이면 조용히 정리하고 이번 저장을 한다
        val c2 = verifiedAfter(v, es, 90, "stale_C2.pdf")
        Overwrite.hook = { if (it == "verify") throw SimulatedDeath(it) }
        try { Overwrite.run(t, c2, rec, "doc"); fail("죽지 않음") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        assertEquals(1, Recovery.list(rec).size)
        val b2 = verifiedAfter(v, es, 90, "stale_B2.pdf")
        assertTrue(Overwrite.run(t, b2, rec, "doc") is Overwrite.Result.Ok)
        assertEquals(sha(b2), sha(orig))
        assertTrue(Recovery.list(rec).isEmpty())
    }

    /** 판 1 HIGH(Codex): 이력 보관에 실패하면 편집을 취소한다(되돌리기가 직전 판을 건너뛰지 않게). */
    @Test fun o_keepFailureCancelsEdit() {
        val v = openOk(asset("pages_case.pdf", File(tmp, "keep.pdf")))
        val es = session(v, "keep")
        val l0 = labels(v.pdf)
        es.edit { PageOps.rotate(it, listOf(0), 90) } // B
        es.markSaved()
        val l1 = labels(v.pdf)
        val ver = es.version
        es.failAt = "keep"
        try { es.edit { PageOps.rotate(it, listOf(1), 90) }; fail("보관 실패인데 편집 성공") } catch (e: DocOpError) { }
        es.failAt = null
        assertEquals("내용 B 그대로", l1, labels(v.pdf))
        assertEquals(ver, es.version)
        assertFalse("저장 상태 그대로", es.dirty)
        assertEquals(1, es.undoCount)
        assertTrue(es.undo())
        assertEquals("되돌리면 A", l0, labels(v.pdf))
        assertTrue(es.redo())
        assertEquals(l1, labels(v.pdf))
    }

    /** 판 1 HIGH(opus): 화면이 둘일 때 다른 모델의 복구 확인이 진행 중인 덮기의 표시를 지우거나 묻지 않는다. */
    @Test fun p_otherModelSkipsActiveOverwrite() {
        val app = target.applicationContext as Application
        val recDir = File(app.filesDir, "recovery").apply { deleteRecursively() }
        val orig = asset("pages_case.pdf", File(tmp, "two_orig.pdf"))
        val shaA = sha(orig)
        val t = FaultTarget(orig)
        val v = openOk(asset("pages_case.pdf", File(tmp, "two_w.pdf")))
        val nw = verifiedAfter(v, session(v, "two"), 90, "two_new.pdf")
        val m2 = newModel()
        Thread.sleep(300) // 처음 확인이 끝나게
        var askedMid: Any? = null
        t.failWrites = setOf(1) // 이번 덮기의 쓰기는 실패, 되써 넣기는 성공
        Overwrite.hook = {
            if (it == "json" || it == "write") {
                runBlocking { m2.checkRecovery() }
                if (askedMid == null) askedMid = m2.recovery
            }
        }
        val r = try { Overwrite.run(t, nw, recDir, "two.pdf") } finally { Overwrite.hook = null }
        assertNull("진행 중인 덮기를 물음", askedMid)
        assertTrue("되돌림 $r", r is Overwrite.Result.Restored)
        assertEquals("원본 A", shaA, sha(orig))
        assertTrue(Recovery.list(recDir).isEmpty())
    }

    /** 판 1 HIGH(opus): 되써 넣기는 before.pdf를 먼저 확인하고 원본을 연다(없거나 다르면 원본에 손대지 않음). */
    @Test fun q_restoreChecksBeforeFirst() {
        val orig = asset("pages_case.pdf", File(tmp, "rb_orig.pdf"))
        val shaA = sha(orig)
        val t = FaultTarget(orig)
        val dir = File(tmp, "rec_rb/s1").apply { mkdirs() }
        File(dir, Recovery.JSON).writeText("{}") // 아직 처리되지 않은 기록(판 2: 처리된 기록이면 해시를 보기 전에 거절)
        val p = Recovery.Pending(dir, t.id, "rb", shaA, "00")
        assertFalse(Recovery.restore(p, t))
        assertEquals("before 없음: 원본 그대로", shaA, sha(orig))
        File(dir, Recovery.BEFORE).writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(Recovery.restore(p, t))
        assertEquals("before 해시 다름: 원본 그대로", shaA, sha(orig))
        assertEquals(0, t.writes)
    }

    /** 판 1 MEDIUM(opus): 원본이 이미 새 판(저장은 끝났고 정리 전에 죽음)이면 묻지 않고 정리한다. */
    @Test fun r_finishedSaveNotAsked() {
        val app = target.applicationContext as Application
        val recDir = File(app.filesDir, "recovery").apply { deleteRecursively() }
        val orig = asset("pages_case.pdf", File(tmp, "done_orig.pdf"))
        val v = openOk(asset("pages_case.pdf", File(tmp, "done_w.pdf")))
        val nw = verifiedAfter(v, session(v, "done"), 90, "done_new.pdf")
        Overwrite.hook = { if (it == "verify") throw SimulatedDeath(it) }
        try { Overwrite.run(FaultTarget(orig), nw, recDir, "done.pdf") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        assertEquals(1, Recovery.list(recDir).size)
        val m = newModel()
        waitUntil("조용히 정리") { Recovery.list(recDir).isEmpty() }
        Thread.sleep(200)
        assertNull("다 끝난 저장을 물음", onMain { m.recovery })
        assertEquals(sha(nw), sha(orig))
    }

    /** 판 1 MEDIUM(opus): 바꿔 끼우기가 디스크에 닿기 전 꺼지면 saving.json.tmp만 남는다. 그 폴더(before.pdf)를 지우지 않는다. */
    @Test fun s_jsonTmpKeepsFolder() {
        val rec = File(tmp, "rec_tmp")
        val orig = asset("pages_case.pdf", File(tmp, "tmp_orig.pdf"))
        val d = File(rec, "s1").apply { mkdirs() }
        val before = File(d, Recovery.BEFORE)
        orig.copyTo(before)
        File(d, Recovery.JSON + ".tmp").writeText(JSONObject().put("uri", FaultTarget(orig).id).put("title", "t")
            .put("before", sha(before)).put("new", "ab").put("time", 1L).toString())
        val l = Recovery.list(rec)
        assertEquals("tmp만 남은 표시도 복구 대상", 1, l.size)
        assertTrue(before.exists())
        // 쓰다 만 tmp(fsync 전, 원본에 손대기 전)는 지운다
        val d2 = File(rec, "s2").apply { mkdirs() }
        File(d2, Recovery.JSON + ".tmp").writeText("{\"uri\":")
        Recovery.list(rec)
        assertFalse(d2.exists())
        Recovery.finish(l[0])
        assertFalse(d.exists())
    }

    /** 판 1 LOW(opus): Error(메모리 부족 등)로 덮기가 멈추면 같은 실행에서 바로 복구를 묻는다. */
    @Test fun t_errorDuringOverwriteAsks() {
        val app = target.applicationContext as Application
        File(app.filesDir, "recovery").deleteRecursively()
        val u = putDoc("err_in.pdf", "pages_case.pdf")
        val shaIn = docSha(u)
        val m = newModel()
        openInModel(m, u)
        assertTrue("쓰기 권한", onMain { m.canOverwrite })
        onMain { m.rotatePages(listOf(0), 90) }
        waitUntil("돌림") { !m.busy && m.dirty }
        var done = false
        Overwrite.hook = { if (it == "write") throw OutOfMemoryError("시험용 메모리 부족") }
        try {
            onMain { m.overwrite { done = true } }
            waitUntil("덮기 끝") { !m.busy && done }
        } finally { Overwrite.hook = null }
        waitUntil("복구 물음", 5_000) { m.recovery != null }
        assertTrue(onMain { m.dirty })
        onMain { m.recoverRestore() }
        waitUntil("복구 끝") { !m.busy && m.recovery == null }
        assertEquals(shaIn, docSha(u))
    }

    /** 판 1 LOW(opus): 실패 되돌리기마저 실패하면 반쯤 고친 문서를 '저장함'으로 두지 않고 세션을 잠근다. */
    @Test fun u_rollbackFailureLocksSession() {
        val v = openOk(asset("pages_case.pdf", File(tmp, "lock.pdf")))
        val es = session(v, "lock")
        es.edit { PageOps.rotate(it, listOf(0), 90) }
        es.markSaved()
        val pre = File(tmp, "work_lock/pre.pdf")
        try {
            es.edit { d -> PageOps.rotate(d, listOf(1), 90); pre.delete(); throw DocOpError("중간 실패") }
            fail("실패해야 함")
        } catch (e: Exception) { }
        assertTrue("반쯤 고친 문서가 저장함으로 남음", es.dirty)
        assertTrue(es.broken)
        try { es.edit { PageOps.rotate(it, listOf(2), 90) }; fail("잠긴 세션에서 편집됨") } catch (e: DocOpError) { }
        try { es.undo(); fail("잠긴 세션에서 되돌림") } catch (e: DocOpError) { }
        es.markSaved()
        assertTrue("잠긴 세션은 저장 표시 안 됨", es.dirty)
        // 시험 훅으로도: 이력 보관 실패 뒤 되돌리기마저 실패하면 잠근다
        val v2 = openOk(asset("pages_case.pdf", File(tmp, "lock2.pdf")))
        val es2 = session(v2, "lock2")
        es2.failAt = "keep,rollback"
        try { es2.edit { PageOps.rotate(it, listOf(0), 90) }; fail() } catch (e: DocOpError) { }
        es2.failAt = null
        assertTrue(es2.broken && es2.dirty)
    }

    /** 판 1 LOW(opus) 모델 쪽: 잠긴 세션은 다른 이름 저장·공유도 막는다. */
    @Test fun v_modelRefusesSaveWhenLocked() {
        val m = newModel()
        openInModel(m, Uri.fromFile(asset("pages_case.pdf", File(tmp, "lockm.pdf"))))
        val s = onMain { m.session }!!
        s.failAt = "op,rollback"
        onMain { m.rotatePages(listOf(0), 90) }
        waitUntil("실패") { !m.busy && m.message != null }
        s.failAt = null
        assertTrue(onMain { m.dirty })
        onMain { m.consumeMessage() }
        val asUri = putDoc("locked_as.pdf", null)
        var ok: Boolean? = null
        onMain { m.saveAs(asUri) { ok = it } }
        waitUntil("저장 시도") { !m.busy && ok != null }
        assertEquals("잠긴 세션이 저장됨", false, ok)
        var sent: Intent? = Intent()
        onMain { m.share { sent = it } }
        waitUntil("공유 시도") { !m.busy && sent == null }
    }

    // ---- w~ab: 독립 검토 판 2 지적(고치기 전에는 실패) ----

    /** 판 2 HIGH(Codex·opus): 같은 원본에 덮어쓰기 둘이 겹치면 먼저 시작한 쪽이 뒤에 성공한 저장을 옛 판으로 되돌린다. */
    @Test fun w_sameUriOverwritesSerialize() {
        val rec = File(tmp, "rec_race")
        val orig = asset("pages_case.pdf", File(tmp, "race_orig.pdf"))
        val v = openOk(asset("pages_case.pdf", File(tmp, "race_w.pdf")))
        val es = session(v, "race")
        val c = verifiedAfter(v, es, 90, "race_C.pdf")
        val b = verifiedAfter(v, es, 90, "race_B.pdf")
        val firstWritten = java.util.concurrent.CountDownLatch(1)
        val secondDone = java.util.concurrent.CountDownLatch(1)
        // 첫 덮기가 C를 다 쓴 직후 멈춰 있는 사이 둘째 덮기(B)를 시작한다(잠금이 있으면 둘째는 첫째가 끝날 때까지 기다림)
        Overwrite.hook = { s ->
            if (s == "written" && Thread.currentThread().name == "first") {
                firstWritten.countDown()
                secondDone.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        var first: Overwrite.Result? = null
        var second: Overwrite.Result? = null
        val t1 = Thread({ first = Overwrite.run(FaultTarget(orig), c, rec, "doc") }, "first")
        val t2 = Thread({
            firstWritten.await()
            second = Overwrite.run(FaultTarget(orig), b, rec, "doc")
            secondDone.countDown()
        }, "second")
        try {
            t1.start(); t2.start()
            t1.join(30_000); t2.join(30_000)
        } finally { Overwrite.hook = null }
        assertTrue("첫 덮기 $first", first is Overwrite.Result.Ok)
        assertTrue("둘째 덮기 $second", second is Overwrite.Result.Ok)
        assertEquals("나중에 끝난 저장(B)이 원본에 남음", sha(b), sha(orig))
        assertTrue(Recovery.list(rec).isEmpty())
    }

    /** 판 2 HIGH(opus): 둘째 화면이 문서를 열어도 첫 화면의 작업 폴더·되돌리기 이력을 지우지 않는다. */
    @Test fun x_otherModelOpenKeepsFirstSession() {
        val m1 = newModel()
        openInModel(m1, Uri.fromFile(asset("pages_case.pdf", File(tmp, "keep1.pdf"))))
        onMain { m1.rotatePages(listOf(0), 90) }
        waitUntil("돌림") { !m1.busy && m1.dirty }
        val dir1 = onMain { m1.workDir }!!
        val m2 = newModel()
        openInModel(m2, Uri.fromFile(asset("pages_insert.pdf", File(tmp, "keep2.pdf"))))
        val dir2 = onMain { m2.workDir }!!
        openInModel(m2, Uri.fromFile(asset("pages_case.pdf", File(tmp, "keep3.pdf"))))
        assertFalse("둘째 화면의 지난 폴더는 지움", dir2.exists())
        assertTrue("첫 화면 작업 폴더 그대로", dir1.exists())
        onMain { m1.undo() }
        waitUntil("되돌림") { !m1.busy && !m1.dirty }
        assertNull("되돌리기 오류 ${onMain { m1.message }}", onMain { m1.message })
        onMain { m1.redo() }
        waitUntil("다시") { !m1.busy && m1.dirty }
        val asUri = putDoc("keep_as.pdf", null)
        var ok: Boolean? = null
        onMain { m1.saveAs(asUri) { ok = it } }
        waitUntil("저장") { !m1.busy && ok != null }
        assertEquals("첫 화면 저장 ${onMain { m1.message }}", true, ok)
    }

    /** 판 2 LOW(opus): 앱 시작 정리는 그 실행 전에 있던 폴더만 지운다(이번 실행에서 막 연 세션은 남김). */
    @Test fun y_startupCleanKeepsNewSession() {
        val files = File(tmp, "y_files")
        val cache = File(tmp, "y_cache")
        val oldWork = File(files, "work/d1").apply { mkdirs() }
        File(oldWork, "orig.pdf").writeText("x")
        val oldUndo = File(cache, "undo/d1").apply { mkdirs() }
        File(oldUndo, "u1.pdf").writeText("x")
        val job = HanjangApp.startupCleaner(files, cache)
        // 정리 스레드가 돌기 전에 이번 실행의 새 세션이 생김
        val newWork = File(files, "work/d2").apply { mkdirs() }
        val newUndo = File(cache, "undo/d2").apply { mkdirs() }
        File(newUndo, "u1.pdf").writeText("y")
        job()
        assertTrue("새 세션 이력 유지", File(newUndo, "u1.pdf").exists())
        assertTrue("새 작업 폴더 유지", newWork.exists())
        assertFalse("지난 이력 지움", oldUndo.exists())
        assertFalse("지난 작업 폴더 지움", oldWork.exists())
    }

    /** 판 2 LOW(opus): "wt" 열기 자체가 실패하면(원본 그대로) 되써 넣지 않고 Untouched. 비운 뒤 실패면 되써 넣는다. */
    @Test fun z_openWriteFailureUntouched() {
        val rec = File(tmp, "rec_open")
        val orig = asset("pages_case.pdf", File(tmp, "open_orig.pdf"))
        val shaA = sha(orig)
        val t = FaultTarget(orig)
        val v = openOk(asset("pages_case.pdf", File(tmp, "open_w.pdf")))
        val c = verifiedAfter(v, session(v, "open"), 90, "open_C.pdf")
        t.failOpens = setOf(1)
        val r = Overwrite.run(t, c, rec, "doc")
        assertTrue("열기 실패는 손대지 않음 $r", r is Overwrite.Result.Untouched)
        assertEquals("되써 넣지 않음", 1, t.writes)
        assertEquals(shaA, sha(orig))
        assertTrue(rec.listFiles()?.isEmpty() ?: true)
        t.truncateThenFail = setOf(t.writes + 1)
        val r2 = Overwrite.run(t, c, rec, "doc")
        assertTrue("비운 뒤 실패는 되돌림 $r2", r2 is Overwrite.Result.Restored)
        assertEquals(shaA, sha(orig))
        assertTrue(rec.listFiles()?.isEmpty() ?: true)
    }

    /** 스스로 훑은 것: 두 화면이 같은 복구를 물을 때, 한쪽이 복구한 뒤 다른 쪽의 '원본 복구'가 실패 안내를 띄우지 않는다. */
    @Test fun aa_secondModelRestoreAfterFirstQuiet() {
        val app = target.applicationContext as Application
        val recDir = File(app.filesDir, "recovery").apply { deleteRecursively() }
        val orig = asset("pages_case.pdf", File(tmp, "dup_orig.pdf"))
        val m0 = newModel()
        val shaA = leavePending(m0, orig)
        val m1 = newModel()
        val m2 = newModel()
        waitUntil("둘 다 물음") { m1.recovery != null && m2.recovery != null }
        onMain { m1.recoverRestore() }
        waitUntil("복구 끝") { !m1.busy && m1.recovery == null }
        assertEquals(shaA, sha(orig))
        onMain { m2.recoverRestore() }
        waitUntil("둘째 복구 끝") { !m2.busy }
        assertFalse("이미 처리된 복구를 실패로 안내", onMain { m2.restoreFailed })
        assertNull(onMain { m2.recovery })
        assertEquals(shaA, sha(orig))
        assertTrue(Recovery.list(recDir).isEmpty())
    }

    /** 판 2 3항: 화면은 하나(singleTask). 다른 앱의 '열기'는 같은 화면이 받는다. */
    @Test fun ab_singleTaskReusesScreen() {
        val info = target.packageManager.getActivityInfo(android.content.ComponentName(target, MainActivity::class.java), 0)
        assertEquals("화면 하나(singleTask)", android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        val u1 = putDoc("task1.pdf", "pages_case.pdf")
        val u2 = putDoc("task2.pdf", "pages_insert.pdf")
        fun view(u: Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf")
            .setClassName(target, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // ActivityScenario는 singleTask 화면에 새 Intent를 보낸 뒤 닫기를 기다리다 멈춰, 다른 앱처럼 직접 띄우고 닫는다.
        // 메인 스레드에서 부른다(waitUntil 조건은 이미 메인 스레드)
        fun live(stage: androidx.test.runner.lifecycle.Stage) =
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(stage).filterIsInstance<MainActivity>()
        target.startActivity(view(u1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil("첫 화면") { live(androidx.test.runner.lifecycle.Stage.RESUMED).size == 1 }
        val act = onMain { live(androidx.test.runner.lifecycle.Stage.RESUMED)[0] }
        try {
            val model = act.model
            waitUntil("열기1") { !model.busy && model.title == "task1.pdf" }
            target.startActivity(view(u2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("같은 화면이 둘째 문서를 받음") { !model.busy && model.title == "task2.pdf" }
            val now = onMain { live(androidx.test.runner.lifecycle.Stage.RESUMED) }
            assertEquals("화면 하나", 1, now.size)
            assertTrue("같은 화면", now[0] === act)
        } finally {
            onMain { act.finish() }
            waitUntil("닫힘") { live(androidx.test.runner.lifecycle.Stage.DESTROYED).contains(act) || act.isDestroyed }
        }
    }

    // ---- k: 다른 앱이 content://로 넘긴 PDF(ACTION_VIEW·SEND) → 편집 → 저장 → 공유 ----

    private val docsAuthority = "com.bubigter.hanjangpdf.testdocs"
    private fun docUri(name: String) = Uri.parse("content://$docsAuthority/$name")

    /** 시험 APK의 '다른 앱' 제공자에 파일을 넣는다. */
    /** '다른 앱' 제공자가 asset(없으면 빈 파일)을 넣고 한장PDF에 그 문서 권한(읽기, [write]면 쓰기도)을 준다. */
    private fun putDoc(name: String, asset: String?, write: Boolean = true): Uri {
        // 제공자는 앱에 없는 권한으로 막혀 있어 셸로 부른다(셸은 DUMP 권한이 있음)
        val cmd = "content call --uri content://$docsAuthority --method put --arg $name --extra write:b:$write" +
            (asset?.let { " --extra asset:s:$it" } ?: "")
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        val outText = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        assertTrue("넣기 $name: $outText", outText.contains("Result"))
        return docUri(name)
    }

    private fun docSha(u: Uri) = target.contentResolver.openInputStream(u)!!.use { Saver.sha256(it) }

    @Test fun k_actionViewSendSaveShare() {
        // 권한을 주지 않은 문서: 읽기는 되지만 원본 덮어쓰기는 막힌다
        val ro = putDoc("readonly.pdf", "pages_insert.pdf", write = false)
        val mr = newModel()
        openInModel(mr, ro)
        assertFalse("권한 없으면 덮어쓰기 안 됨", onMain { mr.canOverwrite })
        val roSha = docSha(ro)
        onMain { mr.rotatePages(listOf(0), 90) }
        waitUntil("돌림") { !mr.busy && mr.dirty }
        onMain { mr.overwrite() }
        instr.waitForIdleSync()
        waitUntil("그대로") { !mr.busy }
        assertEquals("원본 그대로", roSha, docSha(ro))
        assertTrue(onMain { mr.dirty })

        val viewUri = putDoc("shared_in.pdf", "pages_case.pdf")
        val shaIn = docSha(viewUri)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(viewUri, "application/pdf")
            .setClassName(target, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(view).use { scn ->
            var m: ViewerModel? = null
            scn.onActivity { m = it.model }
            val model = m!!
            waitUntil("다른 앱 열기") { !model.busy && model.doc != null }
            assertEquals("shared_in.pdf", onMain { model.title })
            assertEquals(8, onMain { model.doc!!.pageCount })
            onMain { model.movePages(listOf(7), 0) }
            waitUntil("옮김") { !model.busy && model.dirty }
            // 원본 덮어쓰기(쓰기 권한이 있을 때만)
            assertTrue("쓰기 권한", onMain { model.canOverwrite })
            var res: Overwrite.Result? = null
            onMain { model.overwrite { res = it } }
            waitUntil("덮어쓰기") { !model.busy && res != null }
            assertTrue("덮어쓰기 $res", res is Overwrite.Result.Ok)
            assertFalse(onMain { model.dirty })
            assertNotEquals(shaIn, docSha(viewUri))
            val tmpRead = File(tmp, "back.pdf")
            target.contentResolver.openInputStream(viewUri)!!.use { Intake.copy(it, tmpRead) }
            assertEquals("P8@0", labels(openOk(tmpRead).pdf)[0])
            // 다른 이름으로 저장
            val asUri = putDoc("saved_as.pdf", null)
            onMain { model.rotatePages(listOf(0), 90) }
            waitUntil("돌림") { !model.busy && model.dirty }
            var ok: Boolean? = null
            onMain { model.saveAs(asUri) { ok = it } }
            waitUntil("다른 이름") { !model.busy && ok != null }
            assertEquals(true, ok)
            assertEquals("saved_as.pdf", onMain { model.title })
            val back2 = File(tmp, "back2.pdf")
            target.contentResolver.openInputStream(asUri)!!.use { Intake.copy(it, back2) }
            assertEquals("P8@90", labels(openOk(back2).pdf)[0])
        }
        // 공유: 화면 없는 모델로(고르기 창을 띄우지 않게)
        val sendUri = putDoc("sent_in.pdf", "pages_insert.pdf")
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, sendUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val m = newModel()
        onMain { m.openIntent(send) }
        waitUntil("공유로 열기") { !m.busy && m.doc != null }
        assertEquals("sent_in.pdf", onMain { m.title })
        onMain { m.deletePages(listOf(2)) }
        waitUntil("지움") { !m.busy && m.dirty }
        var sent: Intent? = null
        onMain { m.share { sent = it } }
        waitUntil("공유") { !m.busy && sent != null }
        val su = sent!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${target.packageName}.share", su.authority)
        assertTrue(sent!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, sent!!.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, sent!!.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val shared = onMain { m.lastShared }!!
        assertTrue(shared.canonicalPath.startsWith(File(target.cacheDir, "share").canonicalPath))
        assertEquals(sha(shared), target.contentResolver.openInputStream(su)!!.use { Saver.sha256(it) })
        val rd = File(tmp, "shared_read.pdf")
        target.contentResolver.openInputStream(su)!!.use { Intake.copy(it, rd) }
        assertEquals(listOf("I1@0", "I2@0"), labels(openOk(rd).pdf))
        assertTrue("공유는 저장 표시를 바꾸지 않음", onMain { m.dirty })
        // FileProvider는 share/ 밖 파일을 내주지 않는다
        try {
            androidx.core.content.FileProvider.getUriForFile(target, "${target.packageName}.share", File(target.filesDir, "x.pdf"))
            fail("share/ 밖이 열림")
        } catch (e: IllegalArgumentException) { }
    }

    // ---- l: 공유 사본 정리 ----

    @Test fun l_shareStoreClean() {
        val cache = File(tmp, "cache")
        val now = 1_800_000_000_000L
        val h = 3600_000L
        fun dir(name: String, ageH: Double, bytes: Long): File {
            val d = File(ShareStore.root(cache), name).apply { mkdirs() }
            val f = File(d, "a.pdf")
            RandomAccessFile(f, "rw").use { it.setLength(bytes) } // 빈 자리 파일(디스크를 거의 안 씀)
            f.setLastModified(now - (ageH * h).toLong())
            return d
        }
        val old = dir("old", 25.0, 1000)
        val a = dir("a", 3.0, 300L shl 20)
        val b = dir("b", 2.0, 300L shl 20)
        val c = dir("c", 0.5, 300L shl 20)
        val keepSmall = dir("small", 1.5, 1000) // 합계가 한도 밑으로 내려간 뒤라 남음
        ShareStore.clean(cache, now)
        assertFalse("24시간 지난 것", old.exists())
        assertFalse("500MB 넘어 가장 오래된 것", a.exists())
        assertFalse("그다음", b.exists())
        assertTrue("1시간 안", c.exists())
        assertTrue("24시간 안 작은 것", keepSmall.exists())
        // 1시간 안 것만으로 넘으면 지우지 않음
        val c2 = dir("c2", 0.2, 300L shl 20)
        ShareStore.clean(cache, now)
        assertTrue(c.exists() && c2.exists())
        // 이름 정리·사본 확인
        val src = asset("pages_insert.pdf")
        val put = ShareStore.put(cache, src, "a/b:c?.PDF", now)
        assertEquals("a_b_c_.PDF", put.name)
        assertEquals(sha(src), sha(put))
        assertEquals("x.pdf", ShareStore.safeName("x"))
    }

    // ---- m: 200MB급 문서 메모리(에뮬에서만: -e big 1) ----

    @Test fun m_bigDocumentMemory() {
        assumeTrue("큰 문서 시험은 -e big 1 일 때만", args.getString("big") == "1")
        val big = File(tmp, "big_src.pdf")
        makeBigPdf(big, pages = 100, w = 1000, h = 700)
        val rss = ArrayList<Pair<String, Long>>()
        val report = JSONObject().put("file_bytes", big.length())
        fun phase(name: String, body: () -> Unit) {
            val peak = longArrayOf(rssKb())
            val heap = longArrayOf(0)
            val run = java.util.concurrent.atomic.AtomicBoolean(true)
            val t = Thread {
                while (run.get()) {
                    peak[0] = maxOf(peak[0], rssKb())
                    heap[0] = maxOf(heap[0], Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
                    Thread.sleep(10)
                }
            }
            val start = rssKb()
            t.start()
            val t0 = System.currentTimeMillis()
            try { body() } finally { run.set(false); t.join() }
            report.put(name, JSONObject().put("rss_start_kb", start).put("rss_peak_kb", peak[0])
                .put("java_heap_peak_kb", heap[0] / 1024).put("ms", System.currentTimeMillis() - t0))
            rss.add(name to peak[0])
        }
        val work = File(tmp, "bigwork").apply { mkdirs() }
        val orig = File(work, "orig.pdf")
        phase("copy") { big.inputStream().use { Intake.copy(it, orig) } }
        lateinit var v: ViewDoc
        phase("open") { v = openOk(orig) }
        val es = EditSession(v, File(work, "w"), File(tmp, "bigundo"), orig.length())
        assertTrue("200MB 넘음", es.singleStep)
        phase("edit_rotate") { es.edit { PageOps.rotate(it, listOf(0), 90) } }
        phase("edit_rotate2") { es.edit { PageOps.rotate(it, listOf(1), 90) } }
        assertEquals("큰 문서는 되돌리기 한 단계", 1, es.undoCount)
        val saved = File(work, "saved.pdf")
        phase("save_verified") { Saver.saveVerified(v.pdf, saved) }
        // 고장 흉내 대상은 한 바이트씩 써서 느리다. 앱의 UriTarget처럼 스트림 그대로 쓰는 대상으로 잰다.
        val t = object : SaveTarget {
            override val id get() = Uri.fromFile(big).toString()
            override fun openRead(): InputStream = big.inputStream()
            override fun openWrite(): OutputStream = FileOutputStream(big)
        }
        phase("overwrite") { assertTrue(Overwrite.run(t, saved, File(tmp, "bigrec"), "big") is Overwrite.Result.Ok) }
        phase("share_copy") { ShareStore.put(File(tmp, "bigcache"), saved, "big.pdf") }
        report.put("saved_bytes", saved.length()).put("undo_count", es.undoCount)
        File(out, "big_memory.json").writeText(report.toString(1))
        es.dropHistory()
    }

    private fun rssKb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: 0L

    /** 압축이 안 되는 무작위 그림을 쪽마다 넣어 큰 PDF를 지어낸다(쪽마다 w*h*3 바이트). */
    private fun makeBigPdf(f: File, pages: Int, w: Int, h: Int) {
        val doc = PDFDocument()
        val rnd = java.util.Random(7)
        val bytes = ByteArray(w * h * 3)
        for (i in 0 until pages) {
            rnd.nextBytes(bytes)
            val img = doc.newDictionary()
            img.put("Type", doc.newName("XObject")); img.put("Subtype", doc.newName("Image"))
            img.put("Width", w); img.put("Height", h)
            img.put("ColorSpace", doc.newName("DeviceRGB")); img.put("BitsPerComponent", 8)
            val buf = Buffer(bytes.size).apply { writeBytes(bytes) }
            val imgRef = doc.addStream(buf, img)
            val xo = doc.newDictionary(); xo.put("Im0", imgRef)
            val res = doc.newDictionary(); res.put("XObject", xo)
            val page = doc.addPage(Rect(0f, 0f, 595f, 842f), 0, res, "q 500 0 0 350 50 400 cm /Im0 Do Q")
            doc.insertPage(-1, page)
            buf.destroy()
        }
        doc.save(f.path, "")
        doc.destroy()
    }
}
