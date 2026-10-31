package com.bubigter.hanjangpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.bubigter.hanjangpdf.core.Annots
import com.bubigter.hanjangpdf.core.CjkFont
import com.bubigter.hanjangpdf.core.DocOps
import com.bubigter.hanjangpdf.core.PPoint
import com.bubigter.hanjangpdf.core.PRect
import com.bubigter.hanjangpdf.core.Widths
import org.json.JSONArray
import org.json.JSONObject
import com.artifex.mupdf.fitz.PDFObject
import com.bubigter.hanjangpdf.core.DocOpError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 8-1b 호환 실증(계측 시험). 시험 파일은 앱 전용 폴더 files/compat/에 두고 맥이 adb로 주고받는다.
 * 입력 파일이 없으면 그 시험은 건너뛴다(맥 대조 스크립트 tests/android_compat/compat.py와 짝).
 */
@RunWith(AndroidJUnit4::class)
class CompatTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    // 기본은 getExternalFilesDir(adb push로 넣기 쉬움). 안드로이드 13+에서 셸이 넣은 파일을 앱이 못 읽으면
    // -e dir internal 로 filesDir를 쓰고 run-as로 주고받는다.
    private val dir = File(
        if (InstrumentationRegistry.getArguments().getString("dir") == "internal") target.filesDir
        else target.getExternalFilesDir(null), "compat").apply { mkdirs() }
    init { CjkFont.install(target) } // 앱 시작과 같은 초기화(이미 설치됐으면 그대로)
    private val annots = Annots(target.assets.open(Widths.ASSET).use { Widths.load(it) })
    private val sc = JSONObject(testCtx.assets.open("compat_scenario.json").bufferedReader().readText())

    private fun f(name: String) = File(dir, name)
    private fun rect(a: JSONArray) = PRect(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3))
    private fun pt(a: JSONArray) = PPoint(a.getDouble(0), a.getDouble(1))
    private fun optStr(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

    private fun apply(doc: PDFDocument, op: JSONObject): JSONObject {
        val k = op.getString("op"); val pno = op.getInt("pno")
        val out = JSONObject().put("op", k).put("pno", pno)
        when (k) {
            "add_text" -> {
                out.put("xref", annots.addText(doc, pno, pt(op.getJSONArray("anchor")), op.getString("text"),
                    op.getInt("size"), op.getString("align"), op.getString("color")))
                out.put("rows", JSONArray(annots.lastRows))
            }
            "add_text_box" -> {
                out.put("xref", annots.addTextBox(doc, pno, rect(op.getJSONArray("box")), op.getString("text"),
                    op.getInt("size"), op.getString("align"), op.getString("color")))
                out.put("rows", JSONArray(annots.lastRows))
            }
            "add_highlight" -> out.put("xref", annots.addHighlight(doc, pno, rect(op.getJSONArray("rect")), op.getString("color")))
            "add_note" -> out.put("xref", annots.addNote(doc, pno, pt(op.getJSONArray("point")), op.getString("text")))
            "add_box" -> out.put("xref", annots.addBox(doc, pno, rect(op.getJSONArray("rect")), optStr(op, "line"),
                optStr(op, "fill"), op.getDouble("width"), op.getDouble("opacity")))
            "set_text" -> {
                val hit = annots.annotAt(doc, pno, pt(op.getJSONArray("at")), PDFAnnotation.TYPE_FREE_TEXT)
                assertNotNull("글자 상자를 못 찾음: $op", hit)
                assertEquals(PDFAnnotation.TYPE_FREE_TEXT, hit!!["type"])
                val x = hit["xref"] as Int
                out.put("old", annots.textStyle(doc, pno, x).text)
                out.put("xref", annots.setText(doc, pno, x, op.getString("text"), op.getInt("size"),
                    op.getString("align"), op.getString("color")))
                out.put("rows", JSONArray(annots.lastRows))
            }
            "delete_in_rect" -> {
                val n = annots.deleteInRect(doc, pno, rect(op.getJSONArray("rect")))
                assertEquals("지운 개수: $op", op.getInt("expect"), n)
                out.put("n", n)
            }
            "set_box_style" -> {
                val x = annots.boxAt(doc, pno, pt(op.getJSONArray("at")))
                assertNotNull("상자를 못 찾음: $op", x)
                annots.setBoxStyle(doc, pno, x!!, optStr(op, "line"), optStr(op, "fill"), op.getDouble("width"), op.getDouble("opacity"))
                out.put("xref", x)
            }
            else -> error(k)
        }
        return out
    }

    /** 문서의 글자 상자 모양(AP N 스트림)과 그림에서 읽은 글자. 저장 전후 비교용. */
    private fun freeTextInfo(doc: PDFDocument): JSONArray {
        val arr = JSONArray()
        for (pno in 0 until doc.countPages()) {
            val page = doc.loadPage(pno) as PDFPage
            for (a in page.getAnnotations() ?: emptyArray()) {
                if (a.type != PDFAnnotation.TYPE_FREE_TEXT) continue
                val n = a.getObject().get("AP").get("N")
                arr.put(JSONObject().put("pno", pno).put("contents", annots.info(a, "Contents"))
                    .put("ap_len", if (n.isStream) n.readStream().size else -1)
                    .put("ap_hash", if (n.isStream) n.readStream().contentHashCode() else 0)
                    .put("drawn_key", JSONArray(Annots.readKey(annots.annotText(a))))
                    .put("own", annots.isOwn(a)))
            }
        }
        return arr
    }

    private fun run(input: String, ops: JSONArray, output: String) {
        val inp = f(input)
        assumeTrue("입력 파일 없음: $inp", inp.exists())
        val doc = DocOps.open(inp.path)
        val res = JSONArray()
        for (i in 0 until ops.length()) res.put(apply(doc, ops.getJSONObject(i)))
        val before = freeTextInfo(doc)
        DocOps.atomicSave(doc, f(output).path)
        val after = freeTextInfo(DocOps.open(f(output).path))
        // 저장해도 글자 상자 모양이 다시 그려지지 않았는지(update()로 기본 모양이 되면 달라짐)
        assertEquals("저장 전후 글자 상자 수", before.length(), after.length())
        for (i in 0 until before.length()) {
            val b = before.getJSONObject(i); val a = after.getJSONObject(i)
            assertEquals("저장 전후 모양 같음[$i] ${b.getString("contents")}", b.getInt("ap_hash"), a.getInt("ap_hash"))
            assertEquals("저장 전후 읽은 글자[$i]", b.getJSONArray("drawn_key").toString(), a.getJSONArray("drawn_key").toString())
        }
        f("font_calls.txt").appendText(CjkFont.calls.joinToString("\n", postfix = "\n"))
        f(output.replace(".pdf", ".json")).writeText(JSONObject().put("results", res).put("freetext", after).toString(1))
    }

    @Test fun a_makeAndroid() = run("base.pdf", sc.getJSONArray("make"), "android_made.pdf")

    @Test fun b_editPcMade() = run("pc_made.pdf", sc.getJSONArray("android_edits_pc"), "android_edit_pc.pdf")

    @Test fun c_editAgain() = run("pc_edit_android.pdf", sc.getJSONArray("android_edits_again"), "android_edit2.pdf")

    /** 글자 상자 → 저장 → 닫지 않고 둘째 상자 → 다시 저장 뒤 두 상자 모양·원문. */
    @Test fun d_twoSaves() {
        val inp = f("base.pdf")
        assumeTrue(inp.exists())
        val ops = sc.getJSONArray("two_saves")
        val doc = DocOps.open(inp.path)
        apply(doc, ops.getJSONObject(0))
        DocOps.atomicSave(doc, f("two_saves_1.pdf").path)
        apply(doc, ops.getJSONObject(1))
        DocOps.atomicSave(doc, f("two_saves.pdf").path)
        val re = DocOps.open(f("two_saves.pdf").path)
        val info = freeTextInfo(re)
        assertEquals(2, info.length())
        for (i in 0 until 2) {
            val op = ops.getJSONObject(i)
            val o = info.getJSONObject(i)
            assertEquals(op.getString("text"), o.getString("contents"))
            assertTrue("내 주석", o.getBoolean("own"))
            val want = Annots.readKey(op.getString("text"))
            assertEquals("그림에서 글자 그대로[$i]", JSONArray(want).toString(), o.getJSONArray("drawn_key").toString())
        }
        f("two_saves.json").writeText(info.toString(1))
    }

    /** 크기 측정만(핵심 코드는 바꾸지 않음): android_made.pdf에 글꼴 부분집합(subsetFonts)을 하면 얼마나 줄어드나. */
    @Test fun f_subsetProbe() {
        val inp = f("android_made.pdf")
        assumeTrue(inp.exists())
        val doc = DocOps.open(inp.path)
        val before = freeTextInfo(doc)
        doc.subsetFonts()
        doc.save(f("probe_subset.pdf").path, "garbage=3,compress")
        val after = freeTextInfo(DocOps.open(f("probe_subset.pdf").path))
        for (i in 0 until before.length())
            assertEquals("부분집합 뒤 읽은 글자[$i]", before.getJSONObject(i).getJSONArray("drawn_key").toString(),
                after.getJSONObject(i).getJSONArray("drawn_key").toString())
        f("probe_subset.json").writeText(JSONObject().put("before", inp.length()).put("after", f("probe_subset.pdf").length()).toString())
    }

    /** 폭 표가 PC 값과 같은 자리에서 몇 개 확인(전체 대조는 맥 스크립트가 줄 나눔으로). */
    @Test fun e_widths() {
        val w = target.assets.open(Widths.ASSET).use { Widths.load(it) }
        assertEquals(1.0, w.advance(0xAC00), 0.0)
        assertEquals(0.0, w.advance(0), 0.0)
        assertEquals(6.671999931335449 / 12 * 12, w.advance('a'.code) * 12, 1e-12)
    }

    // ---- 검토 판 1 지적 1·2·3 재현(입력: 시험 assets fixnames_case.pdf, tests/android_compat/make_fixnames_case.py) ----

    /** 시험 PDF가 이름으로 쓴 앱 경로 이름(맥에서 만들 때 넣은 값). */
    private val caseBad = "/data/user/0/com.bubigter.hanja"

    /** 쪽 Resources /Font의 한 글꼴에서 이름 자리(사전, 키) 목록: Type0·CIDFont BaseFont, FontDescriptor FontName. */
    private fun nameSlots(doc: PDFDocument, key: String): List<Pair<PDFObject, String>> {
        val font = (doc.loadPage(0) as PDFPage).getObject().get("Resources").get("Font").get(key).resolve()
        val out = mutableListOf(font to "BaseFont")
        var leaf = font
        if (font.get("Subtype").asName() == "Type0") {
            leaf = font.get("DescendantFonts").resolve().get(0).resolve()
            out.add(leaf to "BaseFont")
        }
        val fd = leaf.get("FontDescriptor").resolve()
        if (fd.isDictionary) out.add(fd to "FontName")
        return out
    }

    private fun names(doc: PDFDocument, key: String) = nameSlots(doc, key).map { (d, k) -> d.get(k).asName() }

    /** 시험 PDF를 열고, 앱이 실제로 얻은 경로 이름이 만들 때 넣은 값과 다르면 그 이름으로 맞춘다. */
    private fun openCase(): PDFDocument {
        val src = f("fixnames_case.pdf")
        testCtx.assets.open("fixnames_case.pdf").use { inp -> src.outputStream().use { inp.copyTo(it) } }
        val doc = DocOps.open(src.path)
        val bad = CjkFont.loadedName!!
        if (bad != caseBad) for (k in listOf("FA", "FB", "FC", "FD", "FE"))
            for ((d, key) in nameSlots(doc, k)) if (d.get(key).asName() == caseBad) d.put(key, doc.newName(bad))
        return doc
    }

    private fun checkFixed(doc: PDFDocument, label: String) {
        val bad = CjkFont.loadedName!!
        for (k in listOf("FA", "FB", "FE")) assertEquals("$label 남의 글꼴 $k 그대로", names(doc, k).map { bad }, names(doc, k))
        for (k in listOf("FC", "FD")) assertEquals("$label 앱 글꼴 $k 바뀜", names(doc, k).map { CjkFont.PDF_NAME }, names(doc, k))
    }

    /** 지적 1·3: 이름만 같은 남의 글꼴(FA 간접 CFF·FB 직접 단순·FE 직접 사슬+가짜 TrueType)은 그대로, 앱 글꼴(FC 간접·FD 직접 사슬)만 바뀜. */
    @Test fun g_fixNames() {
        val bad = CjkFont.loadedName!!
        assertTrue("앱 경로 이름이어야 시험이 뜻 있음: $bad", bad != CjkFont.PDF_NAME)
        val doc = openCase()
        for (k in listOf("FA", "FB", "FC", "FD", "FE")) assertTrue("시작 이름 $k", names(doc, k).all { it == bad })
        val n = CjkFont.fixNames(doc)
        assertEquals("바꾼 자리 수(FC 3 + FD 3)", 6, n)
        checkFixed(doc, "fixNames 직후")
        assertEquals("두 번째는 바꿀 것 없음", 0, CjkFont.fixNames(doc))
        // 저장 경로로도: 작업 문서는 그대로, 저장한 파일만 바뀜(지적 2)
        val work = openCase()
        val fp = fingerprint(work)
        DocOps.atomicSave(work, f("fixnames_saved.pdf").path)
        for (k in listOf("FA", "FB", "FC", "FD", "FE")) assertTrue("저장 뒤 작업 문서 이름 그대로 $k", names(work, k).all { it == bad })
        assertEquals("저장 뒤 작업 문서 객체 그대로", fp, fingerprint(work))
        checkFixed(DocOps.open(f("fixnames_saved.pdf").path), "저장한 파일")
        f("fixnames_g.json").writeText(JSONObject().put("loadedName", bad).put("changed", n).put("ok", true).toString())
    }

    /** 문서 모든 객체(사전은 글자로, 스트림은 날 바이트까지)의 sha256. */
    private fun fingerprint(doc: PDFDocument): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (num in 1 until doc.countObjects()) {
            val ref = doc.newIndirect(num, 0)
            md.update("$num:${ref.resolve()?.toString(true, true)}\n".toByteArray())
            if (ref.isStream) md.update(ref.readRawStream()) // isStream·readRawStream은 간접 참조에서만 동작
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun leftovers(): List<String> =
        listOfNotNull(DocOps.workDir, dir).flatMap { d -> (d.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("save") && (it.name.endsWith(".raw.pdf") || it.name.endsWith(".pdf.tmp")) }.map { it.path } }

    /** 지적 2: 저장 단계마다 일부러 실패시켜도 작업 문서 객체·기존 파일 바이트가 그대로이고 임시 파일이 남지 않음. */
    @Test fun h_saveFailure() {
        assertNotNull("앱 시작에서 workDir를 정함", DocOps.workDir)
        val doc = openCase()
        annots.addText(doc, 0, PPoint(20.0, 150.0), "한글 상자", 12, "left", "#000000") // 실제 앱 글꼴 경로도 거침
        val out = f("fail_out.pdf")
        val orig = testCtx.assets.open("fixnames_case.pdf").use { it.readBytes() }
        out.writeBytes(orig)
        val before = fingerprint(doc)
        val bad = CjkFont.loadedName!!
        val log = JSONArray()
        for (stepName in listOf("raw", "fix", "final", "verify", "replace")) {
            DocOps.failAt = stepName
            try {
                DocOps.atomicSave(doc, out.path)
                fail("실패해야 함: $stepName")
            } catch (e: DocOpError) {
                assertTrue("시험용 실패 메시지 $stepName: ${e.message}", e.message!!.contains("시험용 실패: $stepName"))
            } finally {
                DocOps.failAt = null
            }
            assertEquals("작업 문서 객체 그대로($stepName)", before, fingerprint(doc))
            assertTrue("작업 문서 글꼴 이름 그대로($stepName)", names(doc, "FC").all { it == bad })
            assertArrayEquals("기존 파일 바이트 그대로($stepName)", orig, out.readBytes())
            assertEquals("임시 파일 정리($stepName)", emptyList<String>(), leftovers())
            log.put(stepName)
        }
        DocOps.atomicSave(doc, out.path) // 실패 뒤에도 정상 저장
        assertEquals("성공 저장 뒤에도 작업 문서 그대로", before, fingerprint(doc))
        assertEquals("성공 저장 뒤 임시 파일 정리", emptyList<String>(), leftovers())
        checkFixed(DocOps.open(out.path), "실패 뒤 저장한 파일")
        f("fail_steps.json").writeText(JSONObject().put("failed_steps", log).put("ok", true).toString())
    }
}
