package com.bubigter.hanjangpdf.core

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/*
 * 8-2 열기·보기·찾기(화면 없음). 좌표는 모두 MuPDF 쪽 좌표: 쪽 /Rotate와 CropBox 원점을 반영한
 * '화면에 보이는 방향', 원점 (0,0), 1배 = 1pt. PC판 doc_ops.search(사각형 * rotation_matrix)와 같은 공간이다.
 * MuPDF 객체는 한 스레드에서만 다룬다(부르는 쪽이 한 줄 실행기를 씀).
 */

/** 화면 방향 사각형(pt). */
data class VRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w get() = x1 - x0
    val h get() = y1 - y0
}

/** 찾은 곳 하나(PC판처럼 줄 조각 하나가 결과 하나). */
data class Hit(val page: Int, val rect: VRect)

sealed class OpenResult {
    class Ok(val doc: ViewDoc) : OpenResult()
    /** 암호가 필요하다. [ViewDoc.unlock]으로 푼다. */
    class NeedsPassword(val doc: ViewDoc) : OpenResult()
    /** 깨진 파일이나 PDF가 아닌 파일. 화면은 알림만 띄운다. */
    class Broken(val reason: String) : OpenResult()
}

object Intake {
    /**
     * 원본 바이트를 [dest]에 그대로 복사한다(바이트 배열에 다 담지 않고 64KB씩).
     * 같은 폴더의 임시 파일에 다 쓰고 fsync한 뒤 바꿔 끼우므로 도중에 실패하면 [dest]는 생기지 않는다.
     * 돌려주는 값은 복사한 바이트 수.
     */
    fun copy(input: InputStream, dest: File): Long {
        val dir = dest.absoluteFile.parentFile ?: throw IOException("폴더 없음")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("폴더를 만들지 못했습니다.")
        val tmp = File.createTempFile("intake", ".part", dir)
        try {
            var n = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    n += r
                }
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) throw IOException("작업 사본을 만들지 못했습니다.")
            return n
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 작업 사본을 연다. 깨졌거나 PDF가 아니면 [OpenResult.Broken](예외를 밖으로 내지 않는다). */
    fun open(path: String): OpenResult {
        val head = try {
            File(path).inputStream().use { s -> ByteArray(1024).let { b -> b.copyOf(maxOf(0, s.read(b))) } }
        } catch (e: IOException) {
            return OpenResult.Broken("read")
        }
        // PDF 머리('%PDF-')가 앞 1KB 안에 없으면 PDF가 아니다(MuPDF는 그런 파일도 고쳐 열려고 하므로 먼저 거른다).
        if (!String(head, Charsets.ISO_8859_1).contains("%PDF-")) return OpenResult.Broken("notpdf")
        val d: Document = try {
            Document.openDocument(path)
        } catch (e: RuntimeException) {
            return OpenResult.Broken("open")
        }
        val pdf = d.asPDF()
        if (pdf == null) {
            d.destroy()
            return OpenResult.Broken("notpdf")
        }
        val vd = ViewDoc(pdf)
        if (pdf.needsPassword()) return OpenResult.NeedsPassword(vd)
        return vd.load()
    }
}

class ViewDoc internal constructor(val pdf: PDFDocument) {
    /** 쪽 크기(화면 방향, pt). [load] 뒤에 채워진다. */
    var sizes: List<VRect> = emptyList()
        private set

    /** 암호를 맞추면 true와 함께 쪽을 읽는다. 틀리면 false(다시 물으면 된다). */
    fun unlock(password: String): OpenResult? =
        if (pdf.authenticatePassword(password)) load() else null

    internal fun load(): OpenResult = try {
        val n = pdf.countPages()
        if (n <= 0) {
            close()
            OpenResult.Broken("empty")
        } else {
            sizes = List(n) { i ->
                val p = pdf.loadPage(i)
                try {
                    val b = p.bounds
                    VRect(0f, 0f, b.x1 - b.x0, b.y1 - b.y0)
                } finally {
                    p.destroy()
                }
            }
            OpenResult.Ok(this)
        }
    } catch (e: RuntimeException) {
        close()
        OpenResult.Broken("pages")
    }

    val pageCount get() = sizes.size

    /**
     * 쪽 [page]을 [scale]배로 그렸을 때의 픽셀 구역 [x, y, w, h](쪽 왼쪽 위 = 0,0)만 그린다(확대 타일).
     * 같은 배율의 전체 그림에서 그 구역을 잘라 낸 것과 같은 픽셀이 나온다.
     */
    fun renderTile(page: Int, scale: Float, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val p = pdf.loadPage(page)
        try {
            val b = p.bounds
            // 쪽 경계가 0이 아닐 수 있어 원점을 빼고, 타일 왼쪽 위를 비트맵 (0,0)에 둔다.
            val m = Matrix(scale, 0f, 0f, scale, -b.x0 * scale, -b.y0 * scale)
            val dev = AndroidDrawDevice(bmp, x, y, false)
            try {
                p.run(dev, m, null)
                dev.close()
            } finally {
                dev.destroy()
            }
        } finally {
            p.destroy()
        }
        return bmp
    }

    /**
     * 쪽 전체 그림(폭 맞춤 기본 그림·썸네일용). 아주 긴 쪽·넓은 쪽도 메모리를 넘치지 않게
     * [cappedScale]로 배율을 낮춰 [maxPixels]·[MAX_PAGE_SIDE] 안에 그린다(그때 선명한 부분은 화면이 타일로 채운다).
     */
    fun renderPage(page: Int, scale: Float, maxPixels: Long = MAX_PAGE_PIXELS): Bitmap {
        val s = sizes[page]
        val k = cappedScale(s.w, s.h, scale, maxPixels)
        return renderTile(page, k, 0, 0, pixels(s.w, k), pixels(s.h, k))
    }

    /** 한 쪽에서 찾기. 대소문자 구분 없음(PC판 search_for와 같음). */
    fun searchPage(page: Int, query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val p = pdf.loadPage(page) as PDFPage
        try {
            // PC판 page.search_for와 같은 글자 옵션(CropBox에 완전히 잘린 글자는 뽑을 때 버림)과 같은 찾기 규칙.
            // 자세한 근거는 TextSearch.kt 머리말.
            return TextSearch.searchPage(p, p.bounds, q).map {
                Hit(page, VRect(it.x0.toFloat(), it.y0.toFloat(), it.x1.toFloat(), it.y1.toFloat()))
            }
        } finally {
            p.destroy()
        }
    }

    /** 모든 쪽에서 찾기(쪽 순서). [cancelled]가 true가 되면 그때까지 찾은 것만 돌려준다. */
    fun search(query: String, cancelled: () -> Boolean = { false }): List<Hit> {
        val out = ArrayList<Hit>()
        for (i in 0 until pageCount) {
            if (cancelled() || closed) break
            out.addAll(searchPage(i, query))
        }
        return out
    }

    /** 닫혔는지. MuPDF 실행기에서만 바뀌고 읽히므로, 문서를 바꾼 뒤 줄 서 있던 렌더·찾기는 이것을 보고 그만둔다. */
    @Volatile var closed = false
        private set

    fun close() {
        if (closed) return
        closed = true
        pdf.destroy()
    }
}

/** 쪽 전체 그림 한 장의 한도: 픽셀 수(ARGB라 16MB)와 한 변(화면에 올릴 그림의 텍스처 한계). */
const val MAX_PAGE_PIXELS = 4_000_000L
const val MAX_PAGE_SIDE = 4096

/** pt 길이 [len]을 [scale]배로 그린 픽셀 수(올림, 최소 1). [ViewDoc.renderPage]와 같은 셈. */
fun pixels(len: Float, scale: Float): Int = maxOf(1, Math.ceil((len * scale).toDouble()).toInt())

/**
 * 쪽 [w]×[h](pt)를 [scale]배로 그리되 픽셀 수가 [maxPixels], 한 변이 [MAX_PAGE_SIDE]를 넘지 않는 배율.
 * 한도 안이면 [scale] 그대로.
 */
fun cappedScale(w: Float, h: Float, scale: Float, maxPixels: Long = MAX_PAGE_PIXELS): Float {
    fun fits(k: Float) = pixels(w, k).toLong() * pixels(h, k) <= maxPixels &&
        maxOf(pixels(w, k), pixels(h, k)) <= MAX_PAGE_SIDE
    if (fits(scale)) return scale
    val area = w.toDouble() * h
    var k = minOf(Math.sqrt(maxPixels / maxOf(area, 1e-6)), MAX_PAGE_SIDE / maxOf(w, h, 1e-6f).toDouble()).toFloat()
    k = minOf(k, scale)
    while (k > 0f && !fits(k)) k *= 0.99f // 올림 때문에 한도를 살짝 넘는 경우
    return k
}

/** 찾기 결과 사이 이동(PC판 find_step: 처음 찾으면 첫 결과, 끝에서 다음은 처음으로). 바꾸지 않고 새 값을 돌려준다. */
data class SearchCursor(val hits: List<Hit>, val index: Int = -1) {
    val current: Hit? get() = hits.getOrNull(index)

    fun step(dir: Int): SearchCursor =
        if (hits.isEmpty()) this else copy(index = Math.floorMod(index + dir, hits.size))

    /** "3 / 12", 결과가 없으면 null(화면이 '없음'을 띄움). */
    fun label(): String? = if (hits.isEmpty()) null else "${index + 1} / ${hits.size}"
}
