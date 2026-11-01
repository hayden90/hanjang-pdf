package com.bubigter.hanjangpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.OpenResult
import com.bubigter.hanjangpdf.core.PageOps
import com.bubigter.hanjangpdf.core.Saver
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 8-4 판 1 고침 중 찾음: 끼워 넣기(PageOps.merge)가 fitz Java addObject·push(PDFObject)를 썼는데, 둘 다 넘긴 껍데기의
 * 참조를 내려 그 껍데기가 가비지 수거될 때 작업 문서 안 객체가 한 번 더 풀렸다. 끼워 넣기 뒤 수거가 돌면 저장 검증에서
 * "cycle in page tree"가 나거나 GC 도중 앱이 죽었다(고치기 전 에뮬 10/10, 고친 뒤 0/10). 끼워 넣기마다 수거를 강제로 돌린다.
 */
@RunWith(AndroidJUnit4::class)
class InsertGcTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext

    @Test fun a_insertTwiceManyTimes() {
        val tmp = File(target.filesDir, "insert_gc").apply { deleteRecursively(); mkdirs() }
        val ins = File(tmp, "ins.pdf"); instr.context.assets.open("pages_insert.pdf").use { Intake.copy(it, ins) }
        repeat(20) { k ->
            val src0 = File(tmp, "case$k.pdf"); instr.context.assets.open("pages_case.pdf").use { Intake.copy(it, src0) }
            val v = (Intake.open(src0.path) as OpenResult.Ok).doc
            try {
                val pages = v.pdf.countPages()
                repeat(2) {
                    val src = PageOps.openSource(ins.path, null)
                    try { PageOps.insertPdf(v.pdf, src, v.pdf.countPages()) } finally { src.destroy() }
                    Runtime.getRuntime().gc(); System.runFinalization()
                }
                val out = File(tmp, "out$k.pdf")
                Saver.saveVerified(v.pdf, out) // 쪽 수·주석 수 검증 포함
                assertTrue(out.exists())
                assertTrue(v.pdf.countPages() > pages)
            } finally {
                v.close()
            }
        }
        tmp.deleteRecursively()
    }
}
