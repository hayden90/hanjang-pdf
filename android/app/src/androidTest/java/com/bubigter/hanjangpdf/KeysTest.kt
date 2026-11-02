package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
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
 * 8-5 키보드·스위치 접근: 화면을 누르지 않고 키(포커스 + 키 입력)와 접근성 사용자 동작(스위치 접근·TalkBack이 쓰는 것)만으로
 * 쪽 넘기기·확대·쪽 고르기·쪽 옮기기가 되는지.
 */
@OptIn(ExperimentalTestApi::class) // performKeyInput
@RunWith(AndroidJUnit4::class)
class KeysTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as Application
    private val tmp = File(instr.targetContext.filesDir, "keys_test").apply { deleteRecursively(); mkdirs() }
    private var model: ViewerModel? = null

    @After fun tearDown() {
        model?.let { m ->
            var ds: List<com.bubigter.hanjangpdf.core.ViewDoc> = emptyList()
            instr.runOnMainSync { ds = listOfNotNull(m.doc, m.pwDoc) }
            runBlocking(m.mupdf) { ds.forEach { it.close() } }
        }
        tmp.deleteRecursively()
    }

    private fun <T> onMain(f: () -> T): T {
        var r: Any? = null
        instr.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) { if (onMain(cond)) return; Thread.sleep(20) }
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

    /** 이름이 [label]인 사용자 동작을 부른다. */
    private fun custom(tag: String, label: String) {
        val acts = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        val a = acts.firstOrNull { it.label == label } ?: throw AssertionError("$tag 에 동작 '$label' 없음: ${acts.map { it.label }}")
        rule.runOnIdle { assertTrue(a.action()) }
        rule.waitForIdle()
    }

    private fun str(id: Int) = instr.targetContext.getString(id)

    @Test fun pageViewKeys() {
        val m = open()
        val v = rule.onNodeWithTag("pdfview")
        v.requestFocus()
        val z0 = onMain { m.viewPos[0] }
        v.performKeyInput { pressKey(Key.Plus) }; rule.waitForIdle()
        assertEquals("+로 1.25배", z0 * 1.25f, onMain { m.viewPos[0] }, 0.01f)
        // 시험 문서 두 쪽이 한 화면에 다 들어오므로 더 키워 넘치게 한 뒤 넘긴다
        repeat(3) { v.performKeyInput { pressKey(Key.Plus) }; rule.waitForIdle() }
        val y0 = onMain { m.viewPos[2] }
        v.performKeyInput { pressKey(Key.PageDown) }; rule.waitForIdle()
        assertTrue("PageDown으로 내려감", onMain { m.viewPos[2] } > y0)
        v.performKeyInput { pressKey(Key.MoveEnd) }; rule.waitForIdle()
        val yEnd = onMain { m.viewPos[2] }
        v.performKeyInput { pressKey(Key.MoveHome) }; rule.waitForIdle()
        assertEquals("Home으로 맨 위", 0f, onMain { m.viewPos[2] }, 0.5f)
        assertTrue("End로 끝 쪽", yEnd > 0f)
        v.performKeyInput { pressKey(Key.Minus) }; rule.waitForIdle()
        assertTrue("−로 줄임", onMain { m.viewPos[0] } < z0 * 1.25f * 1.25f * 1.25f * 1.25f - 0.01f)
    }

    @Test fun pageViewSwitchActions() {
        val m = open()
        val z0 = onMain { m.viewPos[0] }
        custom("pdfview", str(R.string.zoom_in))
        assertEquals("확대", z0 * 1.25f, onMain { m.viewPos[0] }, 0.01f)
        custom("pdfview", str(R.string.zoom_out))
        assertEquals("축소", z0, onMain { m.viewPos[0] }, 0.01f)
        // 두 쪽이 한 화면에 다 들어오므로 키워 넘치게 한 뒤 넘긴다
        repeat(4) { custom("pdfview", str(R.string.zoom_in)) }
        val y0 = onMain { m.viewPos[2] }
        custom("pdfview", str(R.string.next_page))
        val y1 = onMain { m.viewPos[2] }
        assertTrue("다음 쪽", y1 > y0)
        custom("pdfview", str(R.string.prev_page)) // 앞 쪽의 맨 위로(확대한 자리가 쪽 가운데여도)
        assertTrue("앞 쪽", onMain { m.viewPos[2] } < y1 && onMain { m.viewPos[2] } <= y0)
    }

    @Test fun pageListKeysAndMove() {
        val m = open()
        rule.onNodeWithTag("pagesToggle").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val t0 = rule.onNodeWithTag("thumb0", useUnmergedTree = true)
        t0.requestFocus()
        t0.performKeyInput { pressKey(Key.Spacebar) }; rule.waitForIdle()
        assertEquals("Space로 1쪽 고름", true, t0.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        val before = onMain { m.doc!!.pageCount }
        custom("thumb0", str(R.string.move_later))
        waitUntil("쪽 옮기기") { !m.busy }
        rule.waitForIdle()
        assertEquals("쪽 수 그대로", before, onMain { m.doc!!.pageCount })
        assertEquals("옮긴 쪽(이제 2쪽)이 고른 채로", true,
            rule.onNodeWithTag("thumb1", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertTrue("문서가 바뀜", onMain { m.dirty })
    }

    /** 8-5 판 1 LOW: 다른 편집이 도는 중이라 쪽 옮기기가 막히면 사용자 동작은 거짓(TalkBack이 '안 됨'으로 알림). */
    @Test fun pageListMoveBlockedReturnsFalse() {
        val m = open()
        rule.onNodeWithTag("pagesToggle").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val t0 = rule.onNodeWithTag("thumb0", useUnmergedTree = true)
        t0.requestFocus()
        t0.performKeyInput { pressKey(Key.Spacebar) }; rule.waitForIdle()
        // MuPDF 실행기를 막고 편집 하나를 걸어 '처리 중'으로 만든다
        val go = java.util.concurrent.CountDownLatch(1)
        val inside = java.util.concurrent.CountDownLatch(1)
        Thread { runBlocking(m.mupdf) { inside.countDown(); go.await() } }.start()
        inside.await()
        try {
            onMain { m.movePages(listOf(1), 0) }
            assertTrue("처리 중", onMain { m.busy })
            val acts = t0.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
            val a = acts.first { it.label == str(R.string.move_later) }
            var r = true
            rule.runOnIdle { r = a.action() }
            assertEquals("막힌 옮기기는 거짓", false, r)
        } finally {
            go.countDown()
        }
        waitUntil("앞 편집 끝") { !m.busy }
    }
}
