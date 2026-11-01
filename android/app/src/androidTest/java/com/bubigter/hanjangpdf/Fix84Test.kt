package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.OpenResult
import com.bubigter.hanjangpdf.core.Overwrite
import com.bubigter.hanjangpdf.core.PageOps
import com.bubigter.hanjangpdf.core.Recovery
import com.bubigter.hanjangpdf.core.SaveTarget
import com.bubigter.hanjangpdf.core.Saver
import com.bubigter.hanjangpdf.core.SimulatedDeath
import com.bubigter.hanjangpdf.core.ViewDoc
import com.bubigter.hanjangpdf.core.EditSession
import com.bubigter.hanjangpdf.ui.ViewerModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.TimeUnit

/**
 * 8-4 앞: 8-3 판 3 남은 지적(MEDIUM 3·LOW 3) 재현. 지적마다 고치기 전 코드에서는 실패하고 고친 뒤 통과한다.
 * 고치기 전 코드로도 컴파일되게 예전부터 있던 공개 함수만 쓴다(newDir 시험 하나만 새 함수).
 */
@RunWith(AndroidJUnit4::class)
class Fix84Test {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val app get() = target.applicationContext as Application
    private val tmp = File(target.filesDir, "fix84_test").apply { deleteRecursively(); mkdirs() }
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
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovery_kept").deleteRecursively()
    }

    private fun asset(name: String, to: File = File(tmp, name)): File {
        testCtx.assets.open(name).use { Intake.copy(it, to) }
        return to
    }

    private fun openOk(f: File): ViewDoc {
        val r = Intake.open(f.path)
        assertTrue("열림 $r", r is OpenResult.Ok)
        return (r as OpenResult.Ok).doc.also { opened.add(it) }
    }

    private fun <T> onMain(f: () -> T): T {
        var r: Any? = null
        instr.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun newModel(): ViewerModel = onMain { ViewerModel(app) }.also { models.add(it) }

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

    private fun sha(f: File) = Saver.sha256(f)

    /** 파일 대상(아이디를 바꿔 '같은 파일, 다른 URI 문자열'을 흉내 낸다). */
    private class FileTarget(val file: File, override val id: String = Uri.fromFile(file).toString()) : SaveTarget {
        override fun openRead(): InputStream = file.inputStream()
        override fun openWrite(): OutputStream = FileOutputStream(file)
    }

    /** [root] 아래(시험 폴더 빼고) 해시가 [want]인 파일이 있는지. */
    private fun hasFileWithSha(root: File, want: String): Boolean =
        root.walkTopDown().filter { it.isFile && !it.path.startsWith(tmp.path) }.any {
            try { sha(it) == want } catch (e: Exception) { false }
        }

    /**
     * MEDIUM(opus) 1: 같은 파일이 다른 URI 문자열로 들어와 정상 새 판(B)이 저장된 뒤, 옛 기록으로 '원본 되돌리기'를 하면
     * 고치기 전에는 B가 어디에도 남지 않았다. 고친 뒤에는 되돌리기 직전 원본(B)을 앱 폴더에 보관한다.
     */
    @Test fun a_restoreKeepsCurrentOriginal() {
        File(app.filesDir, "recovery").deleteRecursively()
        val orig = asset("pages_case.pdf", File(tmp, "same_file.pdf"))
        val shaA = sha(orig)
        // 첫 URI로 덮다가 쓰기 시작 직후 죽음 → 기록이 남고 원본은 비워짐
        val v = openOk(asset("pages_case.pdf", File(tmp, "w.pdf")))
        val es = EditSession(v, File(tmp, "work"), File(tmp, "undo"), 1L)
        es.edit { PageOps.rotate(it, listOf(0), 90) }
        val nw = File(tmp, "new.pdf").also { Saver.saveVerified(v.pdf, it) }
        val rec = File(app.filesDir, "recovery")
        Overwrite.hook = { if (it == "write") throw SimulatedDeath(it) }
        try { Overwrite.run(FileTarget(orig), nw, rec, "same_file.pdf") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        // 같은 파일을 다른 URI 문자열로 열어 정상 저장(B): 아이디가 달라 미해결 기록 검사를 지나간다
        val b = asset("pages_insert.pdf", File(tmp, "b_src.pdf"))
        val shaB = sha(b)
        assertTrue(Overwrite.run(FileTarget(orig, "content://other/same_file.pdf"), b, rec, "same_file.pdf") is Overwrite.Result.Ok)
        assertEquals(shaB, sha(orig))
        // 다음 실행: 옛 기록을 묻고, 사용자가 '원본 되돌리기'
        val m = newModel()
        waitUntil("복구 물음") { m.recovery != null }
        onMain { m.recoverRestore() }
        waitUntil("복구 끝") { !m.busy && m.recovery == null }
        assertEquals("원본은 덮기 전 판으로", shaA, sha(orig))
        assertTrue("되돌리기 직전 원본(B)이 앱 폴더에 남음", hasFileWithSha(app.filesDir, shaB))
    }

    /**
     * MEDIUM(opus) 2: '다른 이름으로 저장'에서 이미 내용이 있는 파일을 고르면 고치기 전에는 before.pdf 없이 "wt"로 썼다.
     * 쓰다 실패하면 그 파일이 깨진 채 남았다. 고친 뒤에는 덮어쓰기 경로로 써서 실패하면 원래 내용으로 되돌린다.
     */
    @Test fun b_saveAsExistingFileIsProtected() {
        val m = newModel()
        openInModel(m, Uri.fromFile(asset("pages_case.pdf", File(tmp, "src.pdf"))))
        onMain { m.rotatePages(listOf(0), 90) }
        waitUntil("돌림") { !m.busy && m.dirty }
        val existing = asset("pages_insert.pdf", File(tmp, "existing.pdf"))
        val shaE = sha(existing)
        val steps = ArrayList<String>()
        Overwrite.hook = { synchronized(steps) { steps.add(it) }; if (it == "written") throw IOException("시험용 실패: written") }
        var ok: Boolean? = null
        onMain { m.saveAs(Uri.fromFile(existing)) { ok = it } }
        waitUntil("저장 끝") { !m.busy && ok != null }
        Overwrite.hook = null
        assertTrue("덮어쓰기 경로(before.pdf)를 거침: $steps", synchronized(steps) { "before" in steps && "json" in steps })
        assertEquals("실패했으니 저장 안 함", false, ok)
        assertEquals("있던 파일 내용 그대로", shaE, sha(existing))
        assertTrue("편집은 그대로", onMain { m.dirty })
        // 실패 없이 다시 저장하면 그 파일이 새 원본이 된다
        ok = null
        onMain { m.saveAs(Uri.fromFile(existing)) { ok = it } }
        waitUntil("저장 끝2") { !m.busy && ok != null }
        assertEquals(true, ok)
        assertNotEquals(shaE, sha(existing))
        assertFalse(onMain { m.dirty })
        assertTrue("기록 정리", Recovery.list(m.recRoot).isEmpty())
    }

    /**
     * MEDIUM(Codex): 편집 뒤 docSize에 편집 전 크기를 넣어, 큰 PDF를 끼워 넣어도 '큰 문서 한 단계 되돌리기'가
     * 한 편집 늦게 켜졌다. 고친 뒤에는 끼워 넣은 바로 그 편집부터 켜진다.
     */
    @Test fun c_singleStepRightAfterInsert() {
        val m = newModel()
        val small = asset("pages_insert.pdf", File(tmp, "small.pdf"))
        openInModel(m, Uri.fromFile(small))
        val big = asset("pages_case.pdf", File(tmp, "big_insert.pdf"))
        val s = onMain { m.session }!!
        runBlocking(m.mupdf) { s.limitBytes = small.length() + big.length() / 2 }
        assertFalse(onMain { m.singleStep })
        onMain { m.insertFrom(Uri.fromFile(big), 0) }
        waitUntil("끼워 넣음") { !m.busy && m.dirty }
        assertTrue("끼워 넣은 편집부터 한 단계 되돌리기", onMain { m.singleStep })
    }

    /**
     * LOW 1: 원본 덮어쓰기에서 고치기 전에는 원본을 "wt"로 비운 뒤에야 쓸 파일을 열었다. 그 사이 쓸 파일을 못 열면
     * 원본을 비웠다가 되써 넣어야 했다. 고친 뒤에는 쓸 파일을 먼저 열어, 못 열면 원본에 손대지 않는다.
     */
    @Test fun d_overwriteOpensInputFirst() {
        val orig = asset("pages_insert.pdf", File(tmp, "ow_orig.pdf"))
        val shaO = sha(orig)
        val nw = asset("pages_case.pdf", File(tmp, "ow_new.pdf"))
        var writes = 0
        val t = object : SaveTarget {
            override val id = Uri.fromFile(orig).toString()
            override fun openRead(): InputStream = orig.inputStream()
            override fun openWrite(): OutputStream { writes++; return FileOutputStream(orig) }
        }
        Overwrite.hook = { if (it == "json") nw.delete() } // 표시를 남긴 뒤 쓸 파일이 사라짐
        val r = Overwrite.run(t, nw, File(tmp, "rec_in"), "ow")
        Overwrite.hook = null
        assertEquals("원본을 열어 쓰지 않음($r)", 0, writes)
        assertTrue("원본 그대로라 Untouched: $r", r is Overwrite.Result.Untouched)
        assertEquals(shaO, sha(orig))
        assertTrue(Recovery.list(File(tmp, "rec_in")).isEmpty())
    }

    /** LOW 3: 도는 중에 들어온 '열기' Intent를 고치기 전에는 조용히 버렸다. 고친 뒤에는 끝나면 연다. */
    @Test fun e_openDuringBusyIsQueued() {
        val m = newModel()
        openInModel(m, Uri.fromFile(asset("pages_case.pdf", File(tmp, "first.pdf"))))
        val first = onMain { m.doc }
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        // MuPDF 실행기를 잠시 막아 편집이 확실히 도는 중이게 한다
        m.viewModelScope.launch(m.mupdf) { started.countDown(); gate.await(20, TimeUnit.SECONDS) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        onMain { m.rotatePages(listOf(0), 90) }
        assertTrue(onMain { m.busy })
        val second = asset("pages_insert.pdf", File(tmp, "second.pdf"))
        onMain { m.openIntent(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.fromFile(second))) }
        gate.countDown()
        // 돌린 편집이 있으니 버리고 열지 묻는다(조용히 버리지 않음)
        waitUntil("미룬 열기 물음") { !m.busy && m.pendingOpen != null }
        onMain { m.confirmOpen() }
        waitUntil("둘째 문서") { !m.busy && m.doc != null && m.doc !== first }
        assertEquals(3, onMain { m.doc!!.pageCount })
    }

    /**
     * 8-4 판 1 전체 시험에서 잡힘: 복구 폴더가 비면 checkRecovery가 생성자 안에서 끝까지 돌아
     * 아직 만들어지지 않은 kept 상태를 써서 앱이 죽었다(첫 실행·새로 설치한 폰). 비운 채 여러 번 만들어도 살아 있어야 한다.
     */
    @Test fun f_newModelWithEmptyRecovery() {
        repeat(30) {
            File(app.filesDir, "recovery").deleteRecursively()
            File(app.filesDir, "recovery_kept").deleteRecursively()
            val m = newModel()
            waitUntil("복구 확인") { m.recovery == null && m.kept == null }
        }
    }
}
