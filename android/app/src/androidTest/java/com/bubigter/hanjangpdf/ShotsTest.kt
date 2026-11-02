package com.bubigter.hanjangpdf

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.PRect
import com.bubigter.hanjangpdf.ui.TextEdit
import com.bubigter.hanjangpdf.ui.Tool
import com.bubigter.hanjangpdf.ui.ViewerModel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 8-5 화면 사진(언어·글꼴 200%·어두운 화면 확인용). `-e shots <이름>`을 줄 때만 돈다(전체 시험에서는 건너뜀).
 * 기기 설정은 시험 밖에서 adb로 바꾸고(에뮬만), 여기서는 진짜 화면(MainActivity)을 화면 모델·의미 동작으로만 움직여
 * files/ui_shots/<이름>/에 찍는다(화면 탭 없음).
 */
@RunWith(AndroidJUnit4::class)
class ShotsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val target = instr.targetContext

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

    @Test fun shots() {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("shots")
        // 스토어 사진용(8-6): -e shotsAsset <시험 자산 PDF>, -e shotsWait <찍기 전 기다림 ms>(쪽 그림이 다 들어오게)
        val asset = args.getString("shotsAsset") ?: "annots_base.pdf"
        val wait = args.getString("shotsWait")?.toLongOrNull() ?: 700L
        assumeTrue("화면 사진은 -e shots <이름>일 때만", !name.isNullOrBlank())
        val dir = File(target.filesDir, "ui_shots/$name").apply { deleteRecursively(); mkdirs() }
        fun shot(n: String) {
            // 쪽 그림은 화면이 멈춘 뒤 80ms 기다렸다 그린다(PdfView). 시험 시계를 그만큼 넘겨야 그림이 들어온다
            rule.mainClock.advanceTimeBy(200); rule.waitForIdle(); instr.waitForIdleSync(); Thread.sleep(wait)
            val b = instr.uiAutomation.takeScreenshot()!!
            File(dir, "$n.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun click(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        File(target.filesDir, "recovery_kept").deleteRecursively()
        File(target.filesDir, "recovery").deleteRecursively()

        ActivityScenario.launch(MainActivity::class.java).use { shot("1_empty") }

        val doc = "shots.pdf"
        val cmd = "content call --uri content://com.bubigter.hanjangpdf.testdocs --method put --arg $doc --extra write:b:true --extra asset:s:$asset"
        ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(cmd)).bufferedReader().readText()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.bubigter.hanjangpdf.testdocs/$doc"), "application/pdf")
            .setClassName(target, MainActivity::class.java.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(view).use { scn ->
            var mm: ViewerModel? = null
            scn.onActivity { mm = it.model }
            val m = mm!!
            waitUntil("열기") { !m.busy && m.doc != null }
            shot("2_viewer")
            click("searchToggle"); shot("3_search"); click("searchToggle")
            onMain { m.tools.visible = true; m.tools.tool = Tool.BOX }
            shot("4_box_tool")
            onMain {
                m.tools.tool = Tool.TEXT
                m.tools.editor = TextEdit(0, PRect(60.0, 60.0, 300.0, 100.0), null, "글자 Text 123", 14, "left", "#000000")
            }
            shot("5_text_editor")
            onMain { m.tools.cancelEditor(); m.tools.tool = Tool.VIEW; m.tools.visible = false }
            click("pagesToggle"); shot("6_pages"); click("closePages")
            click("menu"); shot("7_menu")
            click("aboutOpen"); shot("8_about")
            click("agplFull"); shot("9_license")
            click("aboutClose"); click("aboutClose")
        }
    }
}
