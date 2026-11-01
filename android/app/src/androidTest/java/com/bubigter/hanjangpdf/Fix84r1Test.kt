package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.EditSession
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.OpenResult
import com.bubigter.hanjangpdf.core.Overwrite
import com.bubigter.hanjangpdf.core.PageOps
import com.bubigter.hanjangpdf.core.Recovery
import com.bubigter.hanjangpdf.core.SaveTarget
import com.bubigter.hanjangpdf.core.Saver
import com.bubigter.hanjangpdf.core.SimulatedDeath
import com.bubigter.hanjangpdf.core.ViewDoc
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 8-4 독립 검토 판 1 지적 1·2·5(저장 대상 보호·보관 폴더) 재현. 고르는 파일은 시험 APK의 '다른 앱' 제공자
 * (TestDocsProvider: 이름에 failw1_ = 첫 쓰기가 비운 뒤 실패, noread_ = 읽기 실패)로 흉내 낸다.
 * 고치기 전 코드로도 컴파일되게 화면 모델의 예전부터 있던 함수만 쓴다.
 */
@RunWith(AndroidJUnit4::class)
class Fix84r1Test {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    private val testCtx = instr.context
    private val app get() = target.applicationContext as Application
    private val tmp = File(target.filesDir, "fix84r1_test").apply { deleteRecursively(); mkdirs() }
    private val opened = ArrayList<ViewDoc>()
    private val models = ArrayList<ViewerModel>()
    private val docsAuthority = "com.bubigter.hanjangpdf.testdocs"

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

    private fun asset(name: String, to: File = File(tmp, name)): File {
        testCtx.assets.open(name).use { Intake.copy(it, to) }
        return to
    }

    private fun assetSha(name: String) = testCtx.assets.open(name).use { Saver.sha256(it) }

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        // 바로 닫는다(닫지 않으면 기술자가 가비지 수거 때 늦게 닫힌다)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.bufferedReader().readText() }
    }

    /** '다른 앱' 제공자에 asset(없으면 빈 파일)을 넣고 한장PDF에 읽기·쓰기 권한을 준다(사용자가 고른 파일 흉내). */
    private fun putDoc(name: String, asset: String?): Uri {
        val out = shell("content call --uri content://$docsAuthority --method put --arg $name --extra write:b:true" +
            (asset?.let { " --extra asset:s:$it" } ?: ""))
        assertTrue("넣기 $name: $out", out.contains("Result"))
        return Uri.parse("content://$docsAuthority/$name")
    }

    /** 제공자 쪽에서 잰 파일 해시(읽기 실패 흉내 파일도). */
    private fun docSha(name: String): String {
        val out = shell("content call --uri content://$docsAuthority --method sha --arg $name")
        return Regex("sha=([0-9a-f]{64})").find(out)?.groupValues?.get(1) ?: error("해시 못 읽음: $out")
    }

    private fun openInModel(m: ViewerModel, uri: Uri) {
        val before = onMain { m.doc }
        onMain { m.openUri(uri) }
        waitUntil("열기 $uri") { !m.busy && m.pendingOpen == null && m.doc != null && m.doc !== before }
    }

    private class FileTarget(val file: File) : SaveTarget {
        override val id = Uri.fromFile(file).toString()
        override fun openRead(): InputStream = file.inputStream()
        override fun openWrite(): OutputStream = FileOutputStream(file)
    }

    /** 앱 폴더에 '되돌리기 직전 보관한 원본'(recovery_kept/k…/current.pdf + info.json)을 만든다. */
    private fun makeKept(asset: String): File {
        val d = File(app.filesDir, "recovery_kept/k1_test").apply { mkdirs() }
        val f = asset(asset, File(d, Recovery.CURRENT))
        File(d, Recovery.INFO).writeText(JSONObject().put("uri", "content://x/kept.pdf").put("title", "kept.pdf")
            .put("size", f.length()).put("sha", Saver.sha256(f)).put("time", 1L).toString())
        return d
    }

    /**
     * 지적 1(HIGH, 보관본): '보관한 원본 다른 이름으로 저장'에서 내용 있는 파일을 고르고 쓰다 실패하면, 고치기 전에는
     * 그 파일이 비워진 채 남았다(보호 없이 "wt"). 고친 뒤에는 덮기 경로로 써서 원래 내용으로 되돌리고 보관본도 남긴다.
     */
    @Test fun a_keptSaveAsExistingIsProtected() {
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovery_kept").deleteRecursively()
        val kd = makeKept("pages_case.pdf")
        val dest = putDoc("failw1_kept_dest.pdf", "pages_insert.pdf")
        val m = newModel()
        waitUntil("보관본 물음") { m.kept != null && m.recovery == null }
        onMain { m.keptSaveAs(dest) }
        waitUntil("저장 끝") { !m.busy }
        assertEquals("고른 파일의 원래 내용 그대로", assetSha("pages_insert.pdf"), docSha("failw1_kept_dest.pdf"))
        assertTrue("보관본은 남음", File(kd, Recovery.CURRENT).exists())
        // 빈 새 파일에는 그냥 저장되고 보관본이 정리된다
        val fresh = putDoc("kept_fresh.pdf", null)
        waitUntil("보관본 물음2") { m.kept != null || !File(kd, Recovery.CURRENT).exists() }
        onMain { m.keptSaveAs(fresh) }
        waitUntil("저장 끝2") { !m.busy && !File(kd, Recovery.CURRENT).exists() }
        assertEquals(assetSha("pages_case.pdf"), docSha("kept_fresh.pdf"))
    }

    /** 끝나지 않은 덮기 기록 하나를 만든다(덮다가 죽음). 원본 파일을 돌려준다. */
    private fun makePending(): File {
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovery_kept").deleteRecursively()
        val orig = asset("pages_case.pdf", File(tmp, "pend_orig.pdf"))
        val r = Intake.open(asset("pages_case.pdf", File(tmp, "pend_w.pdf")).path)
        assertTrue(r is OpenResult.Ok)
        val v = (r as OpenResult.Ok).doc.also { opened.add(it) }
        EditSession(v, File(tmp, "work"), File(tmp, "undo"), 1L).edit { PageOps.rotate(it, listOf(0), 90) }
        val nw = File(tmp, "pend_new.pdf").also { Saver.saveVerified(v.pdf, it) }
        Overwrite.hook = { if (it == "write") throw SimulatedDeath(it) }
        try { Overwrite.run(FileTarget(orig), nw, File(app.filesDir, "recovery"), "pend_orig.pdf") } catch (e: SimulatedDeath) { }
        Overwrite.hook = null
        return orig
    }

    /**
     * 지적 1(HIGH, 복구본): '복구본 다른 이름으로 저장'(saveCopy)도 같은 결함. 고친 뒤에는 고른 파일 내용을 지키고
     * 복구 기록(before.pdf)도 남긴다.
     */
    @Test fun b_recoverSaveAsExistingIsProtected() {
        makePending()
        val dest = putDoc("failw1_rec_dest.pdf", "pages_insert.pdf")
        val m = newModel()
        waitUntil("복구 물음") { m.recovery != null }
        onMain { m.recoverSaveAs(dest) }
        waitUntil("저장 끝") { !m.busy }
        assertEquals("고른 파일의 원래 내용 그대로", assetSha("pages_insert.pdf"), docSha("failw1_rec_dest.pdf"))
        assertEquals("복구 기록은 남음", 1, Recovery.list(m.recRoot).size)
    }

    /**
     * 지적 2(HIGH): 고른 파일을 읽을 수 없을 때 고치기 전에는 '빈 파일'로 보고 보호 없이 썼다(쓰다 실패하면 내용 소실).
     * 고친 뒤에는 내용이 있는지 모르므로 쓰지 않는다.
     */
    @Test fun c_saveAsUnreadableTargetIsNotWritten() {
        val m = newModel()
        openInModel(m, Uri.fromFile(asset("pages_case.pdf", File(tmp, "src.pdf"))))
        onMain { m.rotatePages(listOf(0), 90) }
        waitUntil("돌림") { !m.busy && m.dirty }
        val dest = putDoc("noread_failw1_dest.pdf", "pages_insert.pdf")
        var ok: Boolean? = null
        onMain { m.saveAs(dest) { ok = it } }
        waitUntil("저장 끝") { !m.busy && ok != null }
        assertEquals("저장 안 됨", false, ok)
        assertEquals("고른 파일 내용 그대로", assetSha("pages_insert.pdf"), docSha("noread_failw1_dest.pdf"))
        assertTrue("편집 그대로", onMain { m.dirty })
    }

    /** 같은 꼴(스스로 훑어 찾음): 쪽 내보내기도 고른 파일에 보호 없이 "wt"로 썼다. */
    @Test fun d_exportPagesExistingIsProtected() {
        val m = newModel()
        openInModel(m, Uri.fromFile(asset("pages_case.pdf", File(tmp, "src2.pdf"))))
        val dest = putDoc("failw1_exp_dest.pdf", "pages_insert.pdf")
        var ok: Boolean? = null
        onMain { m.exportPages(listOf(0), dest) { ok = it } }
        waitUntil("내보내기 끝") { !m.busy && ok != null }
        assertEquals(false, ok)
        assertEquals("고른 파일 내용 그대로", assetSha("pages_insert.pdf"), docSha("failw1_exp_dest.pdf"))
        // 빈 새 파일에는 내보내진다
        val fresh = putDoc("exp_fresh.pdf", null)
        ok = null
        onMain { m.exportPages(listOf(0), fresh) { ok = it } }
        waitUntil("내보내기 끝2") { !m.busy && ok != null }
        assertEquals(true, ok)
    }

    /** 지적 5(LOW): 쓰는 중인 보관 폴더(info.json 아직 없음)를 다른 화면의 목록 읽기가 지우지 않는다. */
    @Test fun e_keptSkipsActiveDir() {
        val root = File(tmp, "kept_root")
        val d = File(root, "k_writing").apply { mkdirs() }
        File(d, Recovery.CURRENT).writeText("쓰는 중")
        assertTrue(Recovery.begin(d))
        try {
            Recovery.kept(root)
            assertTrue("쓰는 중인 폴더는 그대로", d.exists())
        } finally {
            Recovery.end(d)
        }
        Recovery.kept(root)
        assertFalse("끝난 뒤 쓰다 만 폴더는 정리", d.exists())
        assertNotNull(root)
    }
}
