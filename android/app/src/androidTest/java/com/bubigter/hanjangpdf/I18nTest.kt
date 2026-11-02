package com.bubigter.hanjangpdf

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.ui.CoreText
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * 8-5 한국어·영어: core가 던지는 한국어 알림 글이 영어 화면에서 영어로 바뀌는지([CoreText]).
 * (모든 한국어 글에 틀이 있는지는 PC 시험 test_android_i18n.py가 본다.)
 */
@RunWith(AndroidJUnit4::class)
class I18nTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as Application
    private val tmp = File(instr.targetContext.filesDir, "i18n_test").apply { deleteRecursively(); mkdirs() }
    private var model: ViewerModel? = null
    private val hangul = Regex("[가-힣]")

    @After fun tearDown() {
        model?.let { m ->
            var ds: List<com.bubigter.hanjangpdf.core.ViewDoc> = emptyList()
            instr.runOnMainSync { ds = listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        tmp.deleteRecursively()
    }

    private fun ctx(tag: String): Context {
        val c = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return app.createConfigurationContext(c)
    }

    /** 모든 틀을 한국어로 채워(자리에는 안쪽 한국어 글) 영어로 바꾸면 한글이 남지 않는다. */
    @Test fun everyCoreTemplateTranslates() {
        val ko = ctx("ko-KR"); val en = ctx("en-US")
        val inner = ko.getString(R.string.core_not_pdf)
        for (id in CoreText.IDS) {
            val t = ko.getString(id)
            val n = Regex("%(\\d)\\\$s").findAll(t).count()
            val msg = if (n == 0) t else ko.getString(id, *Array(n) { inner })
            val out = CoreText.localize(en, msg)
            assertFalse("영어에 한글이 남음: ${en.resources.getResourceEntryName(id)} → $out", hangul.containsMatchIn(out))
            assertEquals("한국어 화면은 그대로", msg, CoreText.localize(ko, msg))
        }
    }

    /** 감싼 글(편집 실패 안에 열기 실패 안에 PDF 아님)도 끝까지 바뀐다. 모르는 글은 그대로 둔다. */
    @Test fun nestedAndUnknown() {
        val en = ctx("en-US")
        assertEquals("Could not edit: Could not open the PDF: Not a PDF.",
            CoreText.localize(en, "편집하지 못했습니다: PDF를 열 수 없습니다: PDF가 아닙니다."))
        assertEquals("Choose a text size from 6 to 72.", CoreText.localize(en, "글자 크기는 6~72 사이로 골라 주세요."))
        assertEquals("java.io.IOException: x", CoreText.localize(en, "java.io.IOException: x"))
    }

    /**
     * 화면 모델이 띄우는 알림(편집 실패)이 기기 언어를 따른다. 기기가 영어면 한글이 없어야 한다
     * (고치기 전에는 core의 한국어 글이 그대로 들어갔다. 에뮬을 영어로 두고 돌린다. 기기가 한국어면 한글이 있어야 한다).
     */
    @Test fun modelMessageFollowsDeviceLanguage() {
        val m = run { var x: ViewerModel? = null; instr.runOnMainSync { x = ViewerModel(app) }; x!! }.also { model = it }
        val f = File(tmp, "a.pdf")
        instr.context.assets.open("annots_base.pdf").use { Intake.copy(it, f) }
        instr.runOnMainSync { m.openUri(Uri.fromFile(f)) }
        val end = System.currentTimeMillis() + 20_000
        fun idle(): Boolean { var b = false; instr.runOnMainSync { b = !m.busy && m.doc != null }; return b }
        while (!idle() && System.currentTimeMillis() < end) Thread.sleep(20)
        var msg: String? = null
        instr.runOnMainSync { m.consumeMessage(); m.rotatePages(listOf(0), 45) } // core: '회전은 90도 단위만 됩니다.'
        val end2 = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end2) {
            instr.runOnMainSync { msg = m.message }
            if (msg != null) break
            Thread.sleep(20)
        }
        assertNotNull("알림", msg)
        val lang = app.resources.configuration.locales[0].language
        if (lang == "ko") assert(hangul.containsMatchIn(msg!!)) { "한국어 기기: $msg" }
        else assertFalse("기기 언어 $lang 인데 한글이 남음: $msg", hangul.containsMatchIn(msg!!))
    }
}
