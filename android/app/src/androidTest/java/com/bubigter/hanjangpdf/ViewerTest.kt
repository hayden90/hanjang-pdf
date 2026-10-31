package com.bubigter.hanjangpdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Hit
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.OpenResult
import com.bubigter.hanjangpdf.core.SearchCursor
import com.bubigter.hanjangpdf.core.VRect
import com.bubigter.hanjangpdf.core.ViewDoc
import com.bubigter.hanjangpdf.ui.PageLayout
import com.bubigter.hanjangpdf.ui.TileKey
import com.bubigter.hanjangpdf.ui.ViewerModel
import com.bubigter.hanjangpdf.ui.renderKey
import com.bubigter.hanjangpdf.ui.renderPass
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlin.math.abs

/**
 * 8-2 열기·보기·찾기 계측 시험. 입력은 tests/android_compat/make_viewer_cases.py가 만든 assets,
 * 기대값(viewer_case.json)은 PC판(PyMuPDF·pdfviewer.core.doc_ops.search)이 낸 값이다.
 */
@RunWith(AndroidJUnit4::class)
class ViewerTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val dir = File(target.filesDir, "viewer_test").apply { deleteRecursively(); mkdirs() }
    private val exp = JSONObject(testCtx.assets.open("viewer_case.json").bufferedReader().readText())
    private val opened = ArrayList<ViewDoc>()
    private val models = ArrayList<ViewerModel>()
    private val instr = InstrumentationRegistry.getInstrumentation()

    @After fun tearDown() {
        opened.forEach { it.close() }
        for (m in models) {
            val ds = onMain { listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        dir.deleteRecursively()
    }

    private fun asset(name: String): File {
        val f = File(dir, name)
        testCtx.assets.open(name).use { Intake.copy(it, f) }
        return f
    }

    private fun openOk(f: File): ViewDoc {
        val r = Intake.open(f.path)
        assertTrue("열림 $r", r is OpenResult.Ok)
        return (r as OpenResult.Ok).doc.also { opened.add(it) }
    }

    @Test fun a_copyIsByteExact() {
        val f = asset("viewer_case.pdf")
        val src = File(dir, "src.bin")
        testCtx.assets.open("viewer_case.pdf").use { i -> src.outputStream().use { i.copyTo(it) } }
        assertEquals(src.length(), f.length())
        assertEquals(Intake.sha256(src), Intake.sha256(f))
        // 복사 도중 실패하면 대상 파일이 생기지 않고 임시 파일도 남지 않는다.
        val bad = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100_000) 1 else throw IOException("끊김")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n > 100_000) throw IOException("끊김")
                n += len; b.fill(1, off, off + len); return len
            }
        }
        val dest = File(dir, "cut/orig.pdf")
        try { Intake.copy(bad, dest); fail("실패해야 함") } catch (e: IOException) { }
        assertTrue("대상 없음", !dest.exists())
        assertEquals("임시 파일 없음", 0, dest.parentFile!!.listFiles()!!.size)
    }

    @Test fun b_pageSizesRotationCropBox() {
        val d = openOk(asset("viewer_case.pdf"))
        val pages = exp.getJSONArray("pages")
        assertEquals(pages.length(), d.pageCount)
        for (i in 0 until d.pageCount) {
            val p = pages.getJSONObject(i)
            val s = d.sizes[i]
            assertEquals("p$i 폭", p.getDouble("w"), s.w.toDouble(), 0.01)
            assertEquals("p$i 높이", p.getDouble("h"), s.h.toDouble(), 0.01)
        }
    }

    private fun redBox(b: Bitmap): IntArray? {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in 0 until b.width) {
                val c = row[x]
                if (Color.red(c) > 200 && Color.green(c) < 60 && Color.blue(c) < 60) {
                    if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    @Test fun c_renderDirectionMatchesPc() {
        val d = openOk(asset("viewer_case.pdf"))
        val pages = exp.getJSONArray("pages")
        val out = JSONObject()
        for (i in 0 until d.pageCount) {
            val b = d.renderPage(i, 1f)
            val p = pages.getJSONObject(i)
            assertEquals("p$i 그림 폭", Math.ceil(p.getDouble("w")).toInt(), b.width)
            assertEquals("p$i 그림 높이", Math.ceil(p.getDouble("h")).toInt(), b.height)
            val got = redBox(b) ?: run { fail("p$i 빨간 네모 없음"); return }
            val want = p.getJSONArray("red")
            out.put("p$i", got.joinToString(","))
            for (k in 0 until 4) assertTrue("p$i 빨간 네모 ${got.toList()} / PC $want",
                abs(got[k] - want.getInt(k)) <= 1)
            // 배경은 흰색(투명 아님)
            assertEquals(Color.WHITE, b.getPixel(1, 1))
        }
        File(target.filesDir, "viewer_render.json").writeText(out.toString())
    }

    @Test fun d_tilesEqualWholePage() {
        val d = openOk(asset("viewer_case.pdf"))
        val scale = 3.3f
        for (i in listOf(1, 2, 3)) { // 회전·CropBox 쪽
            val whole = d.renderPage(i, scale)
            val t = 256
            var diff = 0
            for (ty in 0 until (whole.height + t - 1) / t) for (tx in 0 until (whole.width + t - 1) / t) {
                val w = minOf(t, whole.width - tx * t); val h = minOf(t, whole.height - ty * t)
                val tile = d.renderTile(i, scale, tx * t, ty * t, w, h)
                val a = IntArray(w * h); val b = IntArray(w * h)
                tile.getPixels(a, 0, w, 0, 0, w, h)
                whole.getPixels(b, 0, w, tx * t, ty * t, w, h)
                for (k in a.indices) if (pxDiff(a[k], b[k]) > 8) diff++
            }
            // 경계의 안티에일리어싱 몇 픽셀만 허용(0.01% 미만)
            assertTrue("p$i 타일과 전체 그림 다른 픽셀 $diff", diff * 10000L < whole.width.toLong() * whole.height)
        }
    }

    private fun pxDiff(a: Int, b: Int) = maxOf(abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    @Test fun e_searchMatchesPc() {
        val d = openOk(asset("viewer_case.pdf"))
        val s = exp.getJSONObject("search")
        val log = JSONObject()
        for (q in s.keys()) {
            val want = s.getJSONArray(q)
            val got = d.search(q)
            log.put(q, got.size)
            assertEquals("'$q' 결과 개수 ${got.map { it.page }}", want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONArray(i)
                val r = w.getJSONArray(1)
                assertEquals("'$q' [$i] 쪽", w.getInt(0), got[i].page)
                val g = got[i].rect
                val ga = floatArrayOf(g.x0, g.y0, g.x1, g.y1)
                for (k in 0 until 4) assertEquals("'$q' [$i] 사각형 $g / PC $r", r.getDouble(k), ga[k].toDouble(), 0.05)
            }
        }
        // 한글·영문·결과 없음·여러 쪽
        assertTrue(d.search("찾기").map { it.page }.toSet().size == 2)
        assertTrue(d.search("apple").map { it.page }.toSet().size == 4)
        assertEquals(0, d.search("없는낱말xyz").size)
        assertEquals(0, d.search("   ").size)
        File(target.filesDir, "viewer_search.json").writeText(log.toString())
    }

    @Test fun f_searchCursor() {
        val hits = List(3) { Hit(it, VRect(0f, 0f, 1f, 1f)) }
        var c = SearchCursor(hits)
        assertNull(c.current)
        c = c.step(1); assertEquals(0, c.index); assertEquals("1 / 3", c.label())
        c = c.step(1).step(1).step(1); assertEquals(0, c.index) // 끝에서 다음은 처음
        c = c.step(-1); assertEquals(2, c.index)
        assertNull(SearchCursor(emptyList()).step(1).label())
        // 찾기 취소: 처음부터 취소면 결과 없음
        val d = openOk(asset("viewer_case.pdf"))
        assertEquals(0, d.search("apple") { true }.size)
    }

    @Test fun g_password() {
        val f = asset("viewer_locked.pdf")
        val r = Intake.open(f.path)
        assertTrue("암호 필요 $r", r is OpenResult.NeedsPassword)
        val d = (r as OpenResult.NeedsPassword).doc
        opened.add(d)
        assertNull("틀린 암호", d.unlock("wrong"))
        assertNull("빈 암호", d.unlock(""))
        val ok = d.unlock(exp.getString("password"))
        assertTrue("맞는 암호 $ok", ok is OpenResult.Ok)
        assertEquals(4, d.pageCount)
        assertEquals(5, d.search("apple").size)
    }

    @Test fun h_brokenFilesDoNotCrash() {
        val good = asset("viewer_case.pdf").readBytes()
        val cases = mapOf(
            "empty.pdf" to ByteArray(0),
            "text.pdf" to "this is not a pdf at all\n".repeat(50).toByteArray(),
            "png.pdf" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2000),
            "head_only.pdf" to "%PDF-1.7\n%garbage\n".toByteArray(),
            "cut.pdf" to good.copyOf(good.size / 3),
            "zeroed.pdf" to good.copyOf().also { b -> for (i in 200 until b.size) b[i] = 0 },
        )
        val log = JSONObject()
        for ((name, bytes) in cases) {
            val f = File(dir, name)
            Intake.copy(ByteArrayInputStream(bytes), f)
            val r = try { Intake.open(f.path) } catch (e: Throwable) { fail("$name 예외 ${e}"); return }
            log.put(name, r.javaClass.simpleName)
            when (r) {
                is OpenResult.Broken -> {}
                is OpenResult.Ok -> {
                    // 앞부분만 남은 파일을 MuPDF가 고쳐 여는 것은 괜찮다. 다만 쪽을 그리거나 찾다가 죽으면 안 된다.
                    opened.add(r.doc)
                    for (i in 0 until r.doc.pageCount) {
                        try { r.doc.renderPage(i, 0.5f); r.doc.searchPage(i, "apple") } catch (e: RuntimeException) { }
                    }
                }
                is OpenResult.NeedsPassword -> fail("$name 암호?")
            }
        }
        for (n in listOf("empty.pdf", "text.pdf", "png.pdf", "head_only.pdf")) assertEquals(n, "Broken", log.getString(n))
        File(target.filesDir, "viewer_broken.json").writeText(log.toString())
    }

    @Test fun i_layout() {
        val sizes = listOf(VRect(0f, 0f, 600f, 800f), VRect(0f, 0f, 300f, 400f), VRect(0f, 0f, 800f, 600f))
        val l = PageLayout(sizes, 1000f, 10f)
        assertEquals(1.25f, l.base, 1e-6f) // 가장 넓은 쪽(800)이 화면 폭(1000)에 맞음
        assertEquals(1000f, l.widths[2], 1e-3f)
        assertEquals((1000f - 375f) / 2f, l.lefts[1], 1e-3f) // 좁은 쪽은 가운데
        assertEquals(10f, l.tops[0], 1e-3f)
        assertEquals(10f + 1000f + 10f, l.tops[1], 1e-3f)
        assertEquals(0..1, l.pagesIn(0f, 1100f, 1f, 0f))
        assertEquals(0, l.pageAt(500f, 1f, 0f))
        assertEquals(2, l.pageAt(10f, 1f, l.tops[2]))
        // 확대 없으면 가로 스크롤 없음, 확대하면 [0, 폭*(z-1)]
        assertEquals(0f, l.clampX(300f, 1f), 1e-3f)
        assertEquals(1000f, l.clampX(5000f, 2f), 1e-3f)
        assertEquals(0f, l.clampY(-5f, 1f, 500f), 1e-3f)
        assertEquals(l.totalH - 500f, l.clampY(1e9f, 1f, 500f), 1e-3f)
        // 타일: 배율 2에서 첫 쪽 왼쪽 위 화면(1000x500) → 쪽 픽셀 x 0..2000(4조각), y 20.. 대략 2조각
        val t = l.visibleTiles(0, 2f, 0f, 0f, 500f, 2f, 512)
        assertTrue(t.isNotEmpty() && t.all { it.first in 0..1 && it.second in 0..1 })
        assertEquals(2f, l.quantize(1.6f), 1e-6f)
        assertEquals(PageLayout.MAX_ZOOM, l.quantize(100f), 1e-6f)
    }

    @Test fun j_closedDocIsSafe() {
        val r = Intake.open(asset("viewer_case.pdf").path) as OpenResult.Ok
        val d = r.doc
        d.close()
        d.close() // 두 번 닫아도 괜찮음
        assertTrue(d.closed)
        assertEquals("닫힌 문서는 찾지 않음", 0, d.search("apple").size)
    }

    // ---- 판 1 지적: 아주 긴 쪽·넓은 쪽의 기본 그림 픽셀 한도 ----

    @Test fun k_hugePageBaseRenderIsCapped() {
        val d = openOk(asset("viewer_long.pdf"))
        assertEquals(100f, d.sizes[0].w, 0.01f); assertEquals(14000f, d.sizes[0].h, 0.01f)
        assertEquals(14000f, d.sizes[1].w, 0.01f); assertEquals(100f, d.sizes[1].h, 0.01f)
        // 화면 폭 1080px에 긴 쪽(폭 100pt)을 맞춘 배율 10.8: 한도 없으면 1080x151200(약 623MiB).
        // 넓은 쪽은 8배 확대 배율 그대로 그리면 112000x800.
        for ((i, scale) in listOf(0 to 10.8f, 1 to 8f)) {
            val b = d.renderPage(i, scale)
            assertTrue("p$i 픽셀 ${b.width}x${b.height}", b.width.toLong() * b.height <= 4_000_000L)
            assertTrue("p$i 한 변 ${b.width}x${b.height}", maxOf(b.width, b.height) <= 4096)
            // 줄여 그려도 끝 가까이의 빨간 네모가 같은 비율 자리에 있다(방향·내용 유지).
            val box = redBox(b) ?: run { fail("p$i 빨간 네모 없음"); return }
            // 긴 변 쪽 픽셀 수로 실제 배율을 잰다(짧은 변은 올림 때문에 어긋남).
            if (i == 0) assertEquals(13000f * b.height / 14000f, box[1].toFloat(), 2f)
            else assertEquals(13000f * b.width / 14000f, box[0].toFloat(), 2f)
        }
        // 선명한 부분은 타일로: 긴 쪽의 빨간 네모 자리를 배율 10.8 그대로 512px 타일로 그리면 네모가 제 픽셀에 있다.
        val t = d.renderTile(0, 10.8f, 0, 140_400 - 100, 512, 512)
        val box = redBox(t) ?: run { fail("타일 빨간 네모 없음"); return }
        assertEquals(20f * 10.8f, box[0].toFloat(), 1.5f)
        assertEquals(100f, box[1].toFloat(), 1.5f)
        // 보통 쪽은 한도 안이라 배율 그대로(1배 그림 크기 = 쪽 크기 올림).
        val n = openOk(asset("viewer_case.pdf")).renderPage(0, 1f)
        assertEquals(595, n.width); assertEquals(842, n.height)
    }

    @Test fun l_layoutTilesCappedPages() {
        val d = openOk(asset("viewer_long.pdf"))
        val sizes = listOf(d.sizes[0], VRect(0f, 0f, 595f, 842f))
        val l = PageLayout(sizes, 1080f, 10f)
        // 가장 넓은 쪽 595pt가 폭 맞춤 → 긴 쪽은 181x25412px: 기본 그림이 한도로 줄어 배율 1에서도 타일.
        assertTrue(l.capped[0]); assertTrue(!l.capped[1])
        assertEquals(1f, l.tileZoom(0, 1f), 1e-6f)
        assertEquals(0f, l.tileZoom(1, 1f), 0f)
        assertEquals(2f, l.tileZoom(1, 2f), 1e-6f)
        // 보이는 타일은 화면(1080x2400) 크기만큼만.
        val tiles = l.visibleTiles(0, 1f, 0f, 5000f, 2400f, 1f, 512)
        assertTrue("타일 ${tiles.size}", tiles.isNotEmpty() && tiles.size <= 6)
        // 화면이 부르는 기본 그림(renderKey)도 한도 안.
        val full = PageLayout(d.sizes, 1080f, 10f)
        for (p in 0 until 2) {
            val b = com.bubigter.hanjangpdf.ui.renderKey(d, full, TileKey(p, 0f, 0, 0))!!
            assertTrue("p$p ${b.width}x${b.height}", b.width.toLong() * b.height <= 4_000_000L && maxOf(b.width, b.height) <= 4096)
        }
    }

    // ---- 판 1 지적: ViewerModel 동시 상황 ----

    private fun <T> onMain(f: () -> T): T {
        var r: Any? = null
        instr.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun newModel(): ViewerModel =
        onMain { ViewerModel(target.applicationContext as Application) }.also { models.add(it) }

    private fun waitUntil(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(20)
        }
        fail("기다림 초과: $what")
    }

    /** MuPDF 실행기와 주 스레드에 줄 선 일을 다 비운다. */
    private fun drain(m: ViewerModel) {
        repeat(3) { runBlocking(m.mupdf) { }; instr.waitForIdleSync() }
    }

    /** MuPDF 실행기를 막아 두고(그동안 들어온 일은 줄을 선다) 푸는 문. */
    private fun blockMupdf(m: ViewerModel): CountDownLatch {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        m.mupdf.asExecutor().execute { started.countDown(); gate.await() }
        started.await()
        return gate
    }

    private fun openInModel(m: ViewerModel, f: File) {
        onMain { m.openUri(Uri.fromFile(f)) }
        waitUntil("열기 ${f.name}") { !m.busy && (m.doc != null || m.pwDoc != null) }
    }

    /** 찾기 도중 찾기 막대를 닫으면(취소) 도는 표시가 꺼지고 다음 열기가 된다. */
    @Test fun m_searchCancelReleasesBusy() {
        val m = newModel()
        openInModel(m, asset("viewer_case.pdf"))
        val a = onMain { m.doc }!!
        val gate = blockMupdf(m)
        onMain { m.query = "apple"; m.runSearch() }
        assertTrue("찾는 중", onMain { m.busy })
        onMain { m.clearSearch() } // 찾기 막대 닫기
        gate.countDown()
        drain(m)
        waitUntil("취소 뒤 도는 표시 꺼짐", 3_000) { !m.busy }
        // 다음 열기가 무시되지 않는다.
        onMain { m.openUri(Uri.fromFile(asset("viewer_long.pdf"))) }
        waitUntil("다음 문서 열림") { !m.busy && m.doc != null && m.doc !== a }
        assertEquals(2, onMain { m.doc!!.pageCount })
        // 찾기를 새로 시작해 이전 찾기를 바꾸는 경우도 끝나면 꺼진다.
        onMain { m.query = "apple"; m.runSearch(); m.query = "xyz"; m.runSearch() }
        drain(m)
        waitUntil("새 찾기 끝") { !m.busy }
    }

    /** 암호를 푸는 사이 창을 닫으면 그 문서는 열리지 않고 닫힌다. */
    @Test fun n_passwordCancelWhileUnlocking() {
        val m = newModel()
        openInModel(m, asset("viewer_locked.pdf"))
        val d = onMain { m.pwDoc }!!
        val pw = exp.getString("password")
        var gate = blockMupdf(m)
        onMain { m.submitPassword(pw); m.cancelPassword() }
        gate.countDown()
        drain(m)
        assertNull("취소했는데 문서가 보임", onMain { m.doc })
        assertNull(onMain { m.pwDoc })
        assertTrue("취소한 문서는 닫힘", d.closed)
        // 암호를 푸는 사이 다른 문서를 열면 그 다른 문서가 남는다.
        openInModel(m, asset("viewer_locked.pdf"))
        val d2 = onMain { m.pwDoc }!!
        gate = blockMupdf(m)
        onMain { m.submitPassword(pw); m.openUri(Uri.fromFile(asset("viewer_long.pdf"))) }
        Thread.sleep(500) // 복사가 끝나 열기가 실행기 줄에 서도록
        gate.countDown()
        drain(m)
        waitUntil("다른 문서 열림") { !m.busy && m.doc != null }
        assertEquals(2, onMain { m.doc!!.pageCount })
        assertNull(onMain { m.pwDoc })
        assertTrue(d2.closed)
    }

    /** 렌더가 줄 선 사이 문서가 바뀌면 지난 문서 그림이 새 문서 자리에 남지 않는다. */
    @Test fun o_renderDuringDocSwap() {
        val m = newModel()
        openInModel(m, asset("viewer_case.pdf"))
        val a = onMain { m.doc }!!
        val key = TileKey(0, 0f, 0, 0)
        val gate = blockMupdf(m)
        val longFile = asset("viewer_long.pdf")
        onMain { m.openUri(Uri.fromFile(longFile)) }
        Thread.sleep(500) // 복사가 끝나 새 문서 열기가 실행기 줄에 서도록
        // 화면이 지난 문서의 첫 쪽 그림을 요청(새 문서 열기 뒤, 지난 문서 닫기 앞에 줄 섬).
        val scope = CoroutineScope(Dispatchers.Main)
        // 큰 쪽처럼 그리기가 오래 걸리는 렌더(그사이 주 스레드는 문서 바꾸기를 마친다).
        val job = scope.launch { m.render(a, key) { Thread.sleep(300); it.renderPage(0, 1f) } }
        gate.countDown()
        drain(m)
        waitUntil("새 문서 열림") { !m.busy && m.doc != null && m.doc !== a }
        runBlocking { job.join() }
        drain(m)
        assertTrue("지난 문서 닫힘", a.closed)
        assertNull("지난 문서 그림이 남음", onMain { m.tiles[key] })
        // 닫힌 문서로 늦게 들어온 렌더도 넣지 않고 죽지 않는다.
        runBlocking(Dispatchers.Main) { m.render(a, key) { it.renderPage(0, 1f) } }
        assertNull(onMain { m.tiles[key] })
        // 지금 문서의 렌더는 들어간다.
        val b = onMain { m.doc }!!
        runBlocking(Dispatchers.Main) { m.render(b, key) { it.renderPage(0, 1f) } }
        assertTrue(onMain { m.tiles[key] } != null)
    }

    /**
     * 판 2 검토 HIGH: CropBox 경계에 걸친 낱말. 기대값은 PC판 doc_ops.search(viewer_crop.json).
     * p0 첫 글자 완전히 밖(0)·일부만 밖(1, x0<0)·완전히 안(1)·완전히 밖(0)·끝 글자 완전히 밖(0)·위 경계 걸침(1, y0<0),
     * p1 /Rotate 90 + CropBox에서 같은 경우들.
     */
    @Test fun p_searchCropEdgeMatchesPc() {
        val d = openOk(asset("viewer_crop.pdf"))
        val want = JSONObject(testCtx.assets.open("viewer_crop.json").bufferedReader().readText()).getJSONArray("apple")
        val got = d.search("apple")
        val log = got.joinToString("; ") { "${it.page} ${it.rect}" }
        File(target.filesDir, "viewer_crop_search.txt").writeText(log)
        assertEquals("결과 개수 $log", want.length(), got.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONArray(i)
            val r = w.getJSONArray(1)
            assertEquals("[$i] 쪽 $log", w.getInt(0), got[i].page)
            val g = got[i].rect
            val ga = floatArrayOf(g.x0, g.y0, g.x1, g.y1)
            for (k in 0 until 4) assertEquals("[$i] 사각형 $g / PC $r", r.getDouble(k), ga[k].toDouble(), 0.05)
        }
        assertEquals("p0 개수", 3, got.count { it.page == 0 })
        assertEquals("p1 개수", 2, got.count { it.page == 1 })
        // 검토 재현의 쪽 밖 사각형(-20, …)은 PC가 내지 않는다
        assertTrue("완전히 잘린 첫 글자 낱말이 걸림 $log", got.none { it.rect.x0 < -10f || it.rect.y0 < -17f })
        // 쪽 하나씩 찾아도 같다
        assertEquals(got, d.searchPage(0, "apple") + d.searchPage(1, "apple"))
    }

    /**
     * 판 3 검토 HIGH: 하이픈으로 이어진 줄(FZ_STEXT_LINE_FLAGS_JOINED). 기대값은 PC판 doc_ops.search(viewer_hyphen.json).
     * 줄 끝 하이픈 이어짐·여러 줄 이어짐·줄 안 하이픈 낱말·다음 줄이 다른 블록·회전 쪽, 그리고 이어진 줄 때문에
     * PC가 뒤 글자의 칸을 밀어 칠하는 것(예: "od"가 '-'와 'o'를 칠함)까지 같아야 한다.
     */
    @Test fun q_searchHyphenMatchesPc() {
        val d = openOk(asset("viewer_hyphen.pdf"))
        val exp = JSONObject(testCtx.assets.open("viewer_hyphen.json").bufferedReader().readText())
        val s = exp.getJSONObject("search")
        val log = StringBuilder()
        for (q in s.keys()) {
            val got = d.search(q)
            log.append(q).append(": ").append(got.joinToString("; ") { "${it.page} ${it.rect}" }).append('\n')
        }
        File(target.filesDir, "viewer_hyphen_search.txt").writeText(log.toString())
        for (q in s.keys()) {
            val want = s.getJSONArray(q)
            val got = d.search(q)
            assertEquals("'$q' 결과 개수\n$log", want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONArray(i)
                val r = w.getJSONArray(1)
                assertEquals("'$q' [$i] 쪽", w.getInt(0), got[i].page)
                val g = got[i].rect
                val ga = floatArrayOf(g.x0, g.y0, g.x1, g.y1)
                for (k in 0 until 4) assertEquals("'$q' [$i] 사각형 $g / PC $r", r.getDouble(k), ga[k].toDouble(), 0.05)
            }
        }
        // 검토 재현: meth-/od에서 method → 0쪽 사각형 2개
        assertEquals(2, d.searchPage(0, "method").size)
        assertEquals(0, d.search("meth-od").size)
        // asJSON에서 읽은 줄 글·플래그가 PC의 실제 line->flags(json "lines")와 같다
        val want = exp.getJSONArray("lines")
        val got = (0 until 3).flatMap { p -> lineFlags(asset("viewer_hyphen.pdf"), p).map { Triple(p, it.first, it.second) } }
        assertEquals("줄 수 $got", want.length(), got.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONArray(i)
            assertEquals("줄 $i", Triple(w.getInt(0), w.getString(1), w.getInt(2)), got[i])
        }
        // 한글·CJK 글꼴 쪽에서도 asJSON 줄 글이 walk 줄 글과 같아 플래그를 버리지 않는다
        for (p in 0 until 4) lineFlags(asset("viewer_case.pdf"), p)
    }

    /** 쪽 [p]의 PC 옵션 글자 줄마다 (walk 글, asJSON 플래그). walk 글과 asJSON 글이 다르면 실패. */
    private fun lineFlags(f: File, p: Int): List<Pair<String, Int>> {
        val doc = com.artifex.mupdf.fitz.Document.openDocument(f.absolutePath)
        try {
            val st = doc.loadPage(p).toStructuredText(com.bubigter.hanjangpdf.core.PC_SEARCH_TEXT_OPTIONS)
            val walked = st.blocks.flatMap { b -> b.lines.map { l -> buildString { l.chars.forEach { appendCodePoint(it.c) } } } }
            val js = com.bubigter.hanjangpdf.core.TextSearch.jsonLines(st.asJSON())!!
            st.destroy()
            assertEquals("쪽 $p 줄 글", walked, js.map { it.first })
            return js
        } finally {
            doc.destroy()
        }
    }

    /**
     * 판 4 검토 HIGH: 화면이 계속 움직여 렌더가 매번 도중에 취소돼도 확대 타일이 쌓이지 않는다.
     * 240쪽·화면 1080×2000·8배, 쪽마다 스크롤해 확대 타일 하나를 그린 직후 취소(collectLatest처럼).
     */
    @Test fun r_renderCancelKeepsTilesBounded() {
        val f = File(dir, "many.pdf")
        val pd = PDFDocument()
        try {
            repeat(240) {
                pd.insertPage(-1, pd.addPage(Rect(0f, 0f, 595f, 842f), 0, pd.newDictionary(), "1 0 0 rg 100 100 50 50 re f"))
            }
            pd.save(f.path, "")
        } finally {
            pd.destroy()
        }
        val m = newModel()
        openInModel(m, f)
        val d = onMain { m.doc }!!
        val viewH = 2000f
        val z = 8f
        val layout = PageLayout(d.sizes, 1080f, 10f)
        fun zoomBytes() = onMain { m.tiles.entries.sumOf { if (it.key.zq != 0f) it.value.allocationByteCount.toLong() else 0L } }
        var cancelled = 0
        for (i in 0 until 240) {
            val y = layout.clampY(layout.tops[i] * z, z, viewH)
            val job = CoroutineScope(Dispatchers.Main).launch {
                renderPass(m, d, layout, z, 0f, y, viewH) { k ->
                    m.render(d, k) { renderKey(it, layout, k) }
                    if (k.zq != 0f) throw CancellationException("화면이 움직임")
                }
            }
            runBlocking { job.join() }
            if (job.isCancelled) cancelled++
            val b = zoomBytes()
            assertTrue("${i}쪽 뒤 확대 타일 ${b shr 20}MiB > 상한", b <= ViewerModel.ZOOM_TILE_BYTES)
        }
        assertTrue("취소가 실제로 일어남 $cancelled", cancelled >= 200)
        // 끝까지 그리면 지금 화면에 필요한 타일만 남고, 그 타일은 모두 있다.
        val y = layout.clampY(layout.tops[120] * z, z, viewH)
        runBlocking(Dispatchers.Main) { renderPass(m, d, layout, z, 0f, y, viewH) }
        val vis = layout.pagesIn(0f, viewH, z, y)
        val need = vis.flatMap { p -> layout.visibleTiles(p, z, 0f, y, viewH, layout.tileZoom(p, z), 512).map { (tx, ty) -> TileKey(p, layout.tileZoom(p, z), tx, ty) } }.toSet()
        val have = onMain { m.tiles.keys.filter { it.zq != 0f }.toSet() }
        assertEquals(need, have)
        // 상한: 필요 없는 타일을 넣어도 지금 화면에 필요한 타일은 버리지 않는다.
        runBlocking(Dispatchers.Main) {
            for (t in 0 until 80) { val k = TileKey(0, 8f, t % 16, t / 16); m.render(d, k) { renderKey(it, layout, k) } }
        }
        assertTrue("상한", zoomBytes() <= ViewerModel.ZOOM_TILE_BYTES)
        assertTrue("필요한 타일이 버려짐", onMain { m.tiles.keys.containsAll(need) })
    }
}
