package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import java.io.File
import java.io.FileOutputStream

/*
 * 8-3 되돌리기 바탕(설계 7항). 편집 하나마다 '편집 전 문서 전체'를 파일로 남긴다.
 * - 실패 복구용 직전 사본(pre.pdf)은 filesDir 작업 폴더에 둔다(시스템이 지우지 않는 곳, 한도와 상관없이 지우지 않음).
 *   편집 도중 실패하면 그 사본을 다시 열어 문서·이력·'저장 안 함' 상태를 편집 전 그대로 돌려놓는다.
 * - 편집이 끝나면 그 사본을 되돌리기 이력(cacheDir)으로 옮긴다. 합계 200MB·50단계 한도를 넘으면 오래된 것부터 버린다.
 *   문서 하나가 200MB를 넘으면 직전 판 하나만 둔다([singleStep]).
 * - 직전 판 하나를 쓸 공간(문서 크기의 2배)이 없으면 편집을 시작하지 않고 [NoSpaceError]를 낸다.
 * MuPDF 객체를 다루므로 한 줄 실행기에서만 부른다.
 */

/** 공간이 모자라 편집을 막았다(화면이 알림을 띄운다). */
class NoSpaceError(val need: Long, val free: Long) : DocOpError("저장 공간이 모자라 편집할 수 없습니다.")

class EditSession(
    val view: ViewDoc,
    /** filesDir/work/<문서> (실패 복구 사본과, 되돌리기로 연 판의 파일). */
    private val dir: File,
    /** cacheDir/undo/<문서> (되돌리기·다시 하기 이력). */
    private val undoDir: File,
    /** 지금 문서가 열린 파일의 크기(처음에는 orig.pdf). */
    startSize: Long,
    /** 남은 공간을 재는 함수(시험에서 바꿔 끼운다). */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    private class Snap(val file: File, val size: Long, val version: Long)

    private val undo = ArrayDeque<Snap>()
    private val redo = ArrayDeque<Snap>()
    private var seq = 0
    /** 되돌리기로 연 판의 파일(지금 문서가 읽고 있어 지우면 안 됨). 처음 orig.pdf는 화면 쪽이 관리한다. */
    private var base: File? = null

    /** 지금 문서 판 번호와 마지막으로 저장한 판 번호. 다르면 '저장 안 함'. */
    var version = 0L
        private set
    private var nextVersion = 1L
    var savedVersion = 0L
        private set
    /** 실패한 편집을 편집 전 판으로 되돌리지도 못했다(문서가 반쯤 고친 상태일 수 있음). 편집·되돌리기·저장을 막는다. */
    var broken = false
        private set
    val dirty get() = broken || version != savedVersion

    var limitBytes = LIMIT_BYTES
    var limitSteps = LIMIT_STEPS
    /** 문서 크기 추정(마지막으로 쓴 판의 크기). 공간 확인에 쓴다. */
    var docSize = startSize
        private set
    /** 문서가 [limitBytes]보다 커서 되돌리기를 한 단계만 두는 중. */
    val singleStep get() = docSize > limitBytes

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val undoCount get() = undo.size
    val historyBytes get() = undo.sumOf { it.size } + redo.sumOf { it.size }

    /** 시험 전용: 이 단계 이름("snapshot"·"op"·"keep"·"rollback", 쉼표로 여럿)에서 실패시킨다. 앱은 늘 null. */
    @Volatile var failAt: String? = null
    private fun fails(name: String) = failAt?.split(',')?.contains(name) == true

    /** 잠긴 세션이면 [DocOpError]. */
    fun checkUsable() {
        if (broken) throw DocOpError("편집 전 판으로 돌아가지 못해 이 문서는 더 편집하거나 저장할 수 없습니다. 문서를 다시 여세요.")
    }

    init {
        dir.mkdirs()
        undoDir.mkdirs()
    }

    private val pdf get() = view.pdf

    /** 지금 판을 저장했다고 표시한다(잠긴 세션은 표시하지 않음). */
    fun markSaved() { if (!broken) savedVersion = version }

    /**
     * 편집 하나. [op]가 실패하면 편집 전 판으로 돌아가고(문서·이력·저장 안 함 그대로) 그 오류를 다시 낸다.
     * 편집 전 사본을 되돌리기 이력으로 옮기지 못해도 편집을 취소한다(되돌리기가 직전 판을 건너뛰지 않게).
     * 쪽 크기도 다시 잰다.
     * [measure]가 참이면(다른 PDF 끼워 넣기처럼 문서가 크게 커질 수 있는 편집) 편집 뒤 문서를 한 번 써 보아
     * 그 크기를 [docSize]로 삼는다(큰 문서 한 단계 되돌리기·공간 확인이 바로 이 편집부터 맞게).
     * 거짓이면 편집 전 크기를 쓴다(주석·돌리기·지우기·옮기기는 크기가 거의 그대로이고, 다음 편집의 사본이 실제 크기를 잰다).
     */
    fun <T> edit(measure: Boolean = false, op: (PDFDocument) -> T): T {
        checkUsable()
        val need = docSize * 2
        val free = freeSpace(undoDir)
        if (free < need) throw NoSpaceError(need, free)
        val pre = File(dir, "pre.pdf")
        pre.delete()
        try {
            if (fails("snapshot")) throw DocOpError("시험용 실패: snapshot")
            write(pdf, pre)
        } catch (e: Exception) {
            pre.delete()
            throw e as? DocOpError ?: DocOpError("편집 전 사본을 만들지 못했습니다: ${e.message}")
        }
        val result = try {
            val r = op(pdf)
            if (fails("op")) throw DocOpError("시험용 실패: op")
            view.measure()
            r
        } catch (e: Throwable) {
            rollback(pre)
            if (e is Error && e !is OutOfMemoryError) throw e
            throw e as? DocOpError ?: DocOpError("편집하지 못했습니다: ${e.message}")
        }
        val size = pre.length()
        val newSize = if (!measure) size else try {
            val post = File(dir, "post.pdf")
            try { write(pdf, post); post.length() } finally { post.delete() }
        } catch (e: Throwable) {
            rollback(pre)
            if (e is Error && e !is OutOfMemoryError) throw e
            throw e as? DocOpError ?: DocOpError("편집하지 못했습니다: ${e.message}")
        }
        // 성공: 편집 전 사본을 되돌리기 이력으로 옮긴다. 옮기지 못하면 편집을 취소하고 편집 전 판으로 돌아간다.
        val snap = File(undoDir, "u${++seq}.pdf")
        try {
            if (fails("keep")) throw DocOpError("시험용 실패: keep")
            moveFile(pre, snap)
        } catch (e: Exception) {
            // 반쯤 옮겼으면 사본을 되살린다(이름 바꾸기가 됐으면 되돌리고, 복사하다 실패했으면 쓰다 만 것을 지움).
            if (pre.exists()) snap.delete() else try { moveFile(snap, pre) } catch (x: Exception) { /* 아래 rollback이 잠근다 */ }
            rollback(pre)
            throw DocOpError("되돌리기 이력을 남기지 못해 편집을 취소했습니다: ${e.message}")
        }
        undo.addLast(Snap(snap, size, version))
        clearRedo()
        version = nextVersion++
        docSize = newSize
        trim()
        return result
    }

    /**
     * 편집 전 사본으로 돌아간다. 사본을 지금 판의 파일로 삼아 연다.
     * 이것마저 실패하면 문서가 반쯤 고친 상태일 수 있어 세션을 잠근다([broken]: 저장 안 함, 편집·저장 막음).
     */
    private fun rollback(pre: File) {
        try {
            if (fails("rollback")) throw DocOpError("시험용 실패: rollback")
            val file = File(dir, "b${++seq}.pdf")
            moveFile(pre, file)
            openAs(file)
        } catch (e: Throwable) {
            broken = true
            throw DocOpError("편집에 실패했고 편집 전 판으로도 돌아가지 못했습니다: ${e.message}")
        }
    }

    /** [file]을 열어 지금 문서로 삼고, 앞서 쓰던 판의 파일은 지운다. */
    private fun openAs(file: File) {
        val d = Document.openDocument(file.path).asPDF() ?: throw DocOpError("사본이 PDF가 아닙니다.")
        val old = base
        try {
            view.swap(d)
        } catch (e: RuntimeException) {
            throw DocOpError("사본을 열지 못했습니다: ${e.message}")
        }
        base = file
        if (old != null && old != file) old.delete()
    }

    /** 되돌리기. 지금 판을 다시 하기 이력에 남기고 바로 앞 판을 연다. 이력이 없으면 false. */
    fun undo(): Boolean = step(undo, redo)

    /** 다시 하기. */
    fun redo(): Boolean = step(redo, undo)

    private fun step(from: ArrayDeque<Snap>, to: ArrayDeque<Snap>): Boolean {
        checkUsable()
        val target = from.lastOrNull() ?: return false
        val need = docSize * 2
        val free = freeSpace(undoDir)
        if (free < need) throw NoSpaceError(need, free)
        val cur = File(undoDir, "u${++seq}.pdf")
        try {
            write(pdf, cur)
        } catch (e: Exception) {
            cur.delete()
            throw DocOpError("지금 판을 남기지 못했습니다: ${e.message}")
        }
        from.removeLast()
        val file = File(dir, "b${++seq}.pdf")
        try {
            moveFile(target.file, file)
            openAs(file)
        } catch (e: Exception) {
            // 그 판을 열지 못하면 지금 판 그대로 두고 이력도 되돌린다.
            if (file.exists() && !target.file.exists()) moveFile(file, target.file)
            from.addLast(target)
            cur.delete()
            throw e as? DocOpError ?: DocOpError("되돌리지 못했습니다: ${e.message}")
        }
        to.addLast(Snap(cur, cur.length(), version))
        version = target.version
        docSize = target.size
        trim()
        return true
    }

    private fun clearRedo() {
        for (s in redo) s.file.delete()
        redo.clear()
    }

    /** 한도: 200MB 넘는 문서면 한 단계만, 아니면 50단계·합계 200MB(오래된 되돌리기부터 버림, 적어도 하나는 남김). */
    private fun trim() {
        val keep = if (singleStep) 1 else limitSteps
        while (undo.size > keep) undo.removeFirst().file.delete()
        while (undo.size > 1 && historyBytes > limitBytes) undo.removeFirst().file.delete()
    }

    /** 문서를 닫을 때 이력 파일을 지운다(지금 판 파일은 문서를 닫은 뒤 화면 쪽이 폴더째 지운다). */
    fun dropHistory() {
        for (s in undo) s.file.delete()
        undo.clear()
        clearRedo()
    }

    companion object {
        const val LIMIT_BYTES = 200L shl 20
        const val LIMIT_STEPS = 50

        /** 문서 전체를 [out]에 쓰고(정리 없이, 암호는 풀어) fsync한다. 바이트 배열을 거치지 않는다. */
        fun write(doc: PDFDocument, out: File) {
            doc.save(out.path, DocOps.SAVE_PLAIN)
            FileOutputStream(out, true).use { it.fd.sync() }
        }

        /** 같은 저장소 안이면 이름 바꾸기, 안 되면 복사 뒤 지우기. */
        fun moveFile(from: File, to: File) {
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) return
            from.inputStream().use { i -> FileOutputStream(to).use { o -> i.copyTo(o, 64 * 1024); o.fd.sync() } }
            from.delete()
        }
    }
}
