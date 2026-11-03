package com.bubigter.hanjangpdf

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.ui.About
import com.bubigter.hanjangpdf.ui.ViewerModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 부빅터 연결(대표님 "권고대로", 2026-11-03): 빈 화면·⋮ 메뉴·정보 화면의 링크가 자리별 출처 표시를 붙여 브라우저로 넘어간다.
 * 브라우저는 실제로 띄우지 않고 ActivityMonitor로 나가는 Intent만 잡는다.
 */
@RunWith(AndroidJUnit4::class)
class BubigterLinkTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as Application
    /** 브라우저로 나가는 주소(잡은 Intent는 띄우지 않고 막는다). */
    private val caught = ArrayList<String>()
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW || intent.data?.scheme != "https") return null
            synchronized(caught) { caught += intent.data.toString() }
            return Instrumentation.ActivityResult(0, null)
        }
    }.also { instr.addMonitor(it) }

    @After fun tearDown() { instr.removeMonitor(monitor) }

    private fun clickAndCatch(tag: String): String {
        val before = synchronized(caught) { caught.size }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        return synchronized(caught) {
            assertEquals("$tag 링크가 브라우저로 한 번 넘어감", before + 1, caught.size)
            caught.last()
        }
    }

    @Test fun urlsCarrySource() {
        for (w in listOf("empty", "menu", "about"))
            assertEquals("https://www.bubigter.com/?utm_source=hanjangpdf_android&utm_medium=app&utm_content=$w", About.bubigterUrl(w))
    }

    @Test fun emptyMenuAndAboutOpenBubigter() {
        val m = instr.let { var r: ViewerModel? = null; it.runOnMainSync { r = ViewerModel(app) }; r!! }
        rule.setContent { HanjangTheme { Surface(Modifier.fillMaxSize()) { App(m) } } }
        assertEquals(About.bubigterUrl("empty"), clickAndCatch("bubigterEmpty"))
        rule.onNodeWithTag("menu").performClick()
        assertEquals(About.bubigterUrl("menu"), clickAndCatch("bubigterMenu"))
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("aboutOpen").performClick()
        assertEquals(About.bubigterUrl("about"), clickAndCatch("bubigterAbout"))
    }
}
