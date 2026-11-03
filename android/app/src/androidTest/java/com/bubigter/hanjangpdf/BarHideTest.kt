package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.ui.Tool
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 스크롤이 멈추면 제목 줄이 접히고, 화면을 한 번 누르면 다시 펼쳐진다(대표님 S25U 가로 읽기 의견, 2026-11-03).
 * 접힌 상태에서의 첫 누르기는 펼치기만 하고 메모·글자 상자를 열지 않는다. 보기 도구가 아니면 접지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class BarHideTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as Application
    private val tmp = File(instr.targetContext.filesDir, "bar_test").apply { deleteRecursively(); mkdirs() }
    private var model: ViewerModel? = null

    @After fun tearDown() {
        model?.let { m ->
            val ds = onMain { listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        tmp.deleteRecursively()
    }

    private fun <T> onMain(f: () -> T): T {
        var r: Any? = null
        instr.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun waitUntil(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(20)
        }
        throw AssertionError("기다림 초과: $what")
    }

    private fun open(): ViewerModel {
        val m = onMain { ViewerModel(app) }.also { model = it }
        rule.setContent { HanjangTheme { Surface(Modifier.fillMaxSize()) { App(m) } } }
        val f = File(tmp, "a.pdf")
        instr.context.assets.open("annots_base.pdf").use { Intake.copy(it, f) }
        onMain { m.openUri(Uri.fromFile(f)) }
        waitUntil("열기") { !m.busy && m.doc != null }
        rule.waitForIdle()
        return m
    }

    private fun titleShown() = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("title")).fetchSemanticsNodes().isNotEmpty()

    @Test fun scrollStopHidesBarAndTapShowsIt() {
        val m = open()
        assertTrue("처음엔 제목 줄이 보임", titleShown())
        rule.onNodeWithTag("pdfview").performTouchInput { swipeUp() }
        // 관성 애니메이션은 시험 시계로 돌므로 rule.waitUntil(시계를 넘김)로 기다린다
        rule.waitUntil(20_000) { m.barHidden }
        rule.waitForIdle()
        assertTrue("접히면 제목·메뉴가 사라짐", !titleShown())
        rule.onNodeWithTag("pdfview").performTouchInput { click() }
        // 한 번 누르기는 두 번 누르기 기다림(약 300ms)이 지나야 확정된다
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("누르면 다시 펼침", false, onMain { m.barHidden })
        assertTrue("제목 줄이 다시 보임", titleShown())
        assertEquals("펼치는 누르기는 메모 창을 열지 않음", null, onMain { m.tools.note })
    }

    @Test fun changingToolShowsBarAndFirstTapDoesNotOpenNote() {
        val m = open()
        onMain { m.tools.visible = true }
        rule.waitForIdle()
        rule.onNodeWithTag("pdfview").performTouchInput { swipeUp() }
        rule.waitUntil(20_000) { m.barHidden }
        // 접힌 채 남아 있는 도구 모음에서 메모 도구를 고르면 제목 줄이 펼쳐진다
        onMain { m.tools.tool = Tool.NOTE }
        rule.waitForIdle()
        assertEquals(false, onMain { m.barHidden })
        assertTrue(titleShown())
        // 그래도 접힌 상태라면(다른 길로) 첫 누르기는 펼치기만 하고 메모 창을 열지 않는다
        onMain { m.barHidden = true }
        rule.waitForIdle()
        rule.onNodeWithTag("pdfview").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(false, onMain { m.barHidden })
        assertEquals(null, onMain { m.tools.note })
    }

    @Test fun otherToolDoesNotHide() {
        val m = open()
        onMain { m.tools.visible = true; m.tools.tool = Tool.HIGHLIGHT }
        rule.waitForIdle()
        // 한 손가락 끌기는 형광펜 획이 되므로 스크롤이 아니다. 도구 상태에서 접지 않는지만 본다
        rule.onNodeWithTag("pdfview").performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertEquals(false, onMain { m.barHidden })
        assertTrue(titleShown())
    }
}
