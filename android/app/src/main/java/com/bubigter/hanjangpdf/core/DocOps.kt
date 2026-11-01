package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

/** 문서 열기·저장(PC판 doc_ops.py의 open_pdf_bytes·atomic_save에 해당하는 최소 부분). */
object DocOps {
    fun open(path: String): PDFDocument {
        val d = try {
            Document.openDocument(path)
        } catch (e: RuntimeException) {
            throw DocOpError("PDF를 열 수 없습니다: ${e.message}")
        }
        return d.asPDF() ?: throw DocOpError("PDF가 아닙니다.")
    }

    /**
     * 임시 사본을 만드는 앱 폴더. 앱 시작([com.bubigter.hanjangpdf.HanjangApp])에서 cacheDir로 정한다.
     * 정하지 않았으면 저장할 파일과 같은 폴더를 쓴다.
     */
    @Volatile var workDir: File? = null

    /** 정리 없이 쓰되 암호를 풀어 쓰는 저장 옵션(되돌리기 사본·저장 임시 파일). */
    const val SAVE_PLAIN = "decrypt"

    /**
     * 시험 전용: 이 단계 이름("raw"·"fix"·"final"·"verify"·"replace")에 이르면 일부러 실패한다.
     * 앱 코드는 쓰지 않는다(늘 null).
     */
    @Volatile var failAt: String? = null

    private fun step(name: String) {
        if (failAt == name) throw DocOpError("시험용 실패: $name")
    }

    /**
     * 저장. 어느 단계에서 실패해도 작업 문서(doc)의 객체와 기존 파일은 그대로이고 임시 파일은 지운다.
     *
     * 1. 작업 문서를 정리 없이 앱 폴더 임시 파일(raw)로 쓴다. 작업 문서는 바꾸지 않는다
     *    (PC판처럼 열린 문서를 바로 garbage 저장하면 객체 번호가 바뀌어 그 뒤에 넣은 글자 상자 글꼴이 깨짐).
     * 2. raw를 따로 열어 그 사본에서만 글꼴 이름을 PC와 같게 고치고([CjkFont.fixNames], 검토 판 1 지적 2),
     *    garbage=3·compress로 저장할 파일과 같은 폴더의 최종 임시 파일에 쓴다.
     * 3. 최종 임시 파일을 다시 열어 쪽 수가 작업 문서와 같은지 본 뒤 바꿔 끼운다.
     */
    fun atomicSave(doc: PDFDocument, outPath: String) {
        val out = File(outPath).absoluteFile
        val dir = workDir ?: out.parentFile
        var raw: File? = null
        var tmp: File? = null
        try {
            raw = File.createTempFile("save", ".raw.pdf", dir)
            step("raw")
            // 암호 PDF도 저장 파일에는 암호가 없다(PC판 save()와 같음). 그래야 아래에서 암호 없이 다시 열린다.
            doc.save(raw.path, SAVE_PLAIN)
            tmp = File.createTempFile("save", ".pdf.tmp", out.parentFile)
            val copy = Document.openDocument(raw.path).asPDF() ?: throw DocOpError("임시 사본이 PDF가 아닙니다.")
            try {
                CjkFont.fixNames(copy) // 글꼴 이름을 PC판과 같게(경로가 이름으로 들어가지 않게), 사본에서만
                step("fix")
                step("final")
                copy.save(tmp.path, "garbage=3,compress")
            } finally {
                copy.destroy()
            }
            step("verify")
            val check = Document.openDocument(tmp.path)
            try {
                if (check.countPages() != doc.countPages()) throw DocOpError("저장한 파일의 쪽 수가 다릅니다.")
            } finally {
                check.destroy()
            }
            step("replace")
            if (!tmp.renameTo(out)) throw DocOpError("파일을 바꿔 끼우지 못했습니다.")
        } catch (e: DocOpError) {
            throw e
        } catch (e: Exception) {
            throw DocOpError("저장하지 못했습니다: ${e.message}")
        } finally {
            raw?.delete()
            if (tmp != null && tmp.exists()) tmp.delete()
        }
    }
}
