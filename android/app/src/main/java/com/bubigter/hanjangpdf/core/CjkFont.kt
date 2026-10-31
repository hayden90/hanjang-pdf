package com.bubigter.hanjangpdf.core

import android.content.Context
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.File
import java.security.MessageDigest

/**
 * PC판과 같은 CJK 대체 글꼴(Droid Sans Fallback Regular, Apache 2.0)을 fitz에 설치한다.
 *
 * 안드로이드 fitz는 글리프가 없는 글자를 /system/fonts의 폰 글꼴로 채우는데(A90: NotoSerifCJKkr 19MB를
 * 통째로 넣음), PC판 PyMuPDF는 내장 Droid Sans Fallback을 쓴다. 같은 바이트(tools/gen_android_cjk_font.py가
 * 뽑음)를 assets에서 앱 전용 폴더로 한 번 꺼내 두고, Font.installFontLoader로 CJK 요청에 이 글꼴을 돌려준다.
 * 앱 시작(HanjangApp)과 계측 시험에서 install()을 부른다. 여러 번 불러도 한 번만 설치한다.
 */
object CjkFont {
    const val ASSET = "droid_sans_fallback.ttf"
    const val SIZE = 3556308L
    const val SHA256 = "ee38813ea00c3e32add4268fff7fff9e39417b4913cb13be2415164a47807cc2"
    /** PC판(PyMuPDF)이 PDF에 적는 이 글꼴의 이름(BaseFont·FontName). */
    const val PDF_NAME = "Droid Sans Fallback Regular"

    /** fitz가 대체 글꼴을 찾을 때 넘기는 글자 체계 이름 가운데 CJK인 것(소문자로 견줌). */
    private val CJK_SCRIPTS = setOf("han", "hangul", "hiragana", "katakana", "bopomofo",
        "kr", "jp", "sc", "tc", "ko", "ja", "zh", "korean", "japanese", "chinese")

    @Volatile private var font: Font? = null

    /**
     * fitz Java의 Font(경로)는 파일 경로 앞 31자를 글꼴 이름으로 삼아 PDF에 그대로 적는다
     * (예: "/data/user/0/com.bubigter.hanja"). 그 이름을 [fixNames]가 PC와 같은 이름으로 바꾼다.
     */
    @Volatile var loadedName: String? = null
        private set

    /** 시험·진단용: 로더가 받은 요청(이름|글자 체계|굵게|기울임 → 돌려준 것). 앞 1000건만. */
    val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    @Synchronized
    fun install(ctx: Context) {
        if (font != null) return
        val f = File(ctx.noBackupFilesDir, ASSET)
        if (!isIntact(f)) {
            val tmp = File(f.parentFile, "$ASSET.tmp")
            ctx.assets.open(ASSET).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            check(isIntact(tmp)) { "CJK 글꼴 사본이 기록된 값과 다릅니다." }
            check(tmp.renameTo(f)) { "CJK 글꼴 사본을 바꿔 끼우지 못했습니다." }
        }
        val loaded = Font(f.path, 0)
        font = loaded
        loadedName = loaded.getName()
        Font.installFontLoader { name, script, bold, italic ->
            val hit = isCjk(script)
            if (calls.size < 1000) calls.add("$name|$script|$bold|$italic -> ${if (hit) "droid" else "null"}")
            if (hit) loaded else null
        }
    }

    /**
     * 앱이 넣은 Droid Sans Fallback 글꼴 사전의 이름(Type0·CIDFont의 BaseFont, FontDescriptor의 FontName)
     * 가운데 [loadedName](앱 경로)인 것만 [PDF_NAME]으로 바꾼다. 바꾼 자리 수를 돌려준다.
     *
     * 이름이 같다는 것만으로는 바꾸지 않는다(검토 판 1 지적 1). 그 글꼴의 FontFile2를 풀어 낸 바이트가
     * assets 글꼴과 크기·sha256이 같을 때만 앱 글꼴로 본다. Type0는 DescendantFonts 첫 CIDFont의
     * FontDescriptor, CIDFont·단순 글꼴은 자기 FontDescriptor, FontDescriptor는 자기 FontFile2로 판단하므로
     * 한 사슬(Type0→CIDFont→FontDescriptor)의 이름은 같은 글꼴 파일로 함께 바뀐다.
     *
     * 간접 객체마다 그 안의 직접 사전·배열(쪽 Resources 안 /Font 직접 사전 등)도 훑는다(지적 3).
     * 간접 참조는 따라가지 않는다(그 객체는 바깥 반복에서 따로 훑음) — 그래서 참조 순환에 빠지지 않고,
     * 직접 객체는 나무꼴이라 깊이 제한([MAX_DEPTH])만 둔다.
     *
     * 작업 문서가 아니라 저장 사본에만 부른다([DocOps.atomicSave]).
     */
    fun fixNames(doc: PDFDocument): Int {
        val bad = loadedName ?: return 0
        if (bad == PDF_NAME) return 0
        val fix = NameFix(bad)
        for (num in 1 until doc.countObjects()) {
            val o = try { doc.newIndirect(num, 0).resolve() } catch (e: RuntimeException) { continue }
            if (o == null || o.isNull) continue
            fix.walk(doc, o, 0)
        }
        return fix.n
    }

    private const val MAX_DEPTH = 64

    private class NameFix(val bad: String) {
        var n = 0
        /** 글꼴 파일 스트림 객체 번호 → 앱 글꼴인가(같은 스트림을 여러 번 풀지 않게). */
        private val isApp = HashMap<Int, Boolean>()

        fun walk(doc: PDFDocument, o: PDFObject, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (o.isDictionary) {
                fixOne(doc, o)
                for (k in o) {
                    val v = o.get(k)
                    if (!v.isIndirect && (v.isDictionary || v.isArray)) walk(doc, v, depth + 1)
                }
            } else if (o.isArray) {
                for (i in 0 until o.size()) {
                    val v = o.get(i)
                    if (!v.isIndirect && (v.isDictionary || v.isArray)) walk(doc, v, depth + 1)
                }
            }
        }

        private fun fixOne(doc: PDFDocument, d: PDFObject) {
            val type = d.get("Type")
            if (!type.isName) return
            val key = when (type.asName()) { "Font" -> "BaseFont"; "FontDescriptor" -> "FontName"; else -> return }
            val v = d.get(key)
            if (!v.isName || v.asName() != bad) return
            val ok = if (key == "FontName") descriptorIsApp(d) else fontIsApp(d)
            if (ok) {
                d.put(key, doc.newName(PDF_NAME))
                n++
            }
        }

        private fun fontIsApp(font: PDFObject): Boolean {
            val f = font.resolve() ?: return false
            if (!f.isDictionary) return false
            val sub = f.get("Subtype")
            if (sub.isName && sub.asName() == "Type0") {
                val desc = f.get("DescendantFonts").resolve() ?: return false
                if (!desc.isArray || desc.size() < 1) return false
                val cid = desc.get(0).resolve() ?: return false
                val cs = cid.get("Subtype")
                if (!cid.isDictionary || (cs.isName && cs.asName() == "Type0")) return false
                return fontIsApp(cid)
            }
            val fd = f.get("FontDescriptor").resolve() ?: return false
            return fd.isDictionary && descriptorIsApp(fd)
        }

        private fun descriptorIsApp(fd: PDFObject): Boolean {
            val ref = fd.get("FontFile2")
            if (!ref.isIndirect) return false // 스트림은 늘 간접 객체
            return isApp.getOrPut(ref.asIndirect()) {
                // fitz의 isStream·readStream은 간접 참조에서만 동작한다(resolve한 사전에선 늘 false)
                if (!ref.isStream) false
                else try {
                    val b = ref.readStream()
                    b.size.toLong() == SIZE && sha256(b) == SHA256
                } catch (e: RuntimeException) { false }
            }
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun isCjk(script: String?): Boolean = script != null && script.lowercase() in CJK_SCRIPTS

    private fun isIntact(f: File): Boolean {
        if (!f.isFile || f.length() != SIZE) return false
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == SHA256
    }
}
