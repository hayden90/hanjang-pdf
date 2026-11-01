package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 8-4 판 1 고침 중 찾음: 쪽 내보내기·끼워 넣기(PageOps.merge)가 fitz Java addObject·push(PDFObject)를 썼는데, 둘 다
 * 넘긴 껍데기의 참조를 내려 그 껍데기가 가비지 수거될 때 문서 안 객체가 한 번 더 풀렸다(GC 도중 앱이 죽음). 이 시험 뒤에
 * PagesTest·ViewerTest를 이어 돌리면 고치기 전에는 에뮬에서 10번 중 9번 나빴고, 고친 뒤 0/10. 시험 하나만으로는 GC 시점이
 * 안 와 잡지 못할 수 있다.
 */
@RunWith(AndroidJUnit4::class)
class ExportGcTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext
    /** 화면 모델의 쪽 내보내기 두 번(빈 파일 대상). */
    @Test fun a_exportTwice() {
        val tmp = File(target.filesDir, "probe").apply { deleteRecursively(); mkdirs() }
        val src = File(tmp, "src.pdf"); instr.context.assets.open("pages_case.pdf").use { Intake.copy(it, src) }
        var m: ViewerModel? = null
        instr.runOnMainSync { m = ViewerModel(target.applicationContext as Application) }
        val mm = m!!
        instr.runOnMainSync { mm.openUri(Uri.fromFile(src)) }
        while (true) { var ok = false; instr.runOnMainSync { ok = !mm.busy && mm.doc != null }; if (ok) break; Thread.sleep(20) }
        for (k in 1..2) {
            val out = File(tmp, "out$k.pdf").apply { createNewFile() }
            var r: Boolean? = null
            instr.runOnMainSync { mm.exportPages(listOf(0), Uri.fromFile(out)) { r = it } }
            while (true) { var d = false; instr.runOnMainSync { d = r != null && !mm.busy }; if (d) break; Thread.sleep(20) }
            assertEquals(true, r)
        }
        val d = mm.doc!!
        runBlocking(mm.mupdf) { d.close() }
    }

    /** 같은 내보내기를 화면 모델 없이 직접. */
    @Test fun b_exportTwiceDirect() {
        val tmp = File(target.filesDir, "probe_b").apply { deleteRecursively(); mkdirs() }
        val src = File(tmp, "src.pdf"); instr.context.assets.open("pages_case.pdf").use { Intake.copy(it, src) }
        val r = Intake.open(src.path) as com.bubigter.hanjangpdf.core.OpenResult.Ok
        for (k in 1..2) com.bubigter.hanjangpdf.core.PageOps.exportPages(r.doc.pdf, listOf(0), File(tmp, "out$k.pdf").path)
        r.doc.close()
    }
}
