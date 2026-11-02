package com.bubigter.hanjangpdf

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.then
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.PPoint
import com.bubigter.hanjangpdf.core.PRect
import com.bubigter.hanjangpdf.ui.NoteEdit
import com.bubigter.hanjangpdf.ui.TextEdit
import com.bubigter.hanjangpdf.ui.Tool
import com.bubigter.hanjangpdf.ui.ViewerModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 8-5 접근성: 주요 화면 상태마다 Compose 의미 트리를 읽어 본다(화면 탭·입력 주입 없음, 단추는 의미 동작으로만 누름).
 * - 누를 수 있는 것은 모두 읽힐 이름이 있다(기호만 있는 단추는 contentDescription).
 * - 누르는 영역 48dp 이상.
 * - 글이 잘리거나(넘침) 누를 것끼리 겹치지 않는다(제목은 말줄임이 설계).
 * - Accessibility Test Framework(ATF) 검사(대비·이름·누르는 영역 등)를 통과한다.
 * 언어(한·영), 글꼴 200%, 어두운 화면은 [DeviceConfigurationOverride]로 바꾼다(기기 설정은 건드리지 않음).
 */
@RunWith(AndroidJUnit4::class)
class A11yTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as Application
    private val tmp = File(instr.targetContext.filesDir, "a11y_test").apply { deleteRecursively(); mkdirs() }
    private var model: ViewerModel? = null
    private val problems = ArrayList<String>()

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

    private fun density() = rule.density.density

    private fun label(n: SemanticsNode): String {
        val c = n.config
        return (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ").trim()
    }

    private fun all(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher("모두") { true }).fetchSemanticsNodes(atLeastOneRootRequired = true)

    /** 지금 화면 상태 [state]를 검사하고 문제를 모은다. [overlap]은 대화상자·메뉴가 없을 때만(창이 달라 좌표가 다름). */
    private fun check(state: String, overlap: Boolean = true) {
        rule.waitForIdle()
        val nodes = all()
        val min = 48f * density() - 1f
        val clickable = nodes.filter { it.config.contains(SemanticsActions.OnClick) }
        for (n in clickable) {
            val l = label(n)
            val tag = n.config.getOrNull(SemanticsProperties.TestTag) ?: "?"
            if (!Regex("[\\p{L}\\p{N}]").containsMatchIn(l)) problems += "$state: 이름 없는 단추(tag=$tag, 글='$l')"
            // 누르는 영역은 Material의 최소 누르는 영역(48dp)으로 넓힌 touchBounds로 잰다. 가로로 밀어 보는 줄에서 화면 밖으로
            // 잘린 단추는 잘린 만큼 작게 나오므로 건너뛴다(밀어서 보이면 다른 상태에서 잰다).
            val b = n.boundsInRoot
            val clipped = b.width < n.size.width - 1f || b.height < n.size.height - 1f
            val t = n.touchBoundsInRoot
            if (!clipped && (t.width < min || t.height < min))
                problems += "$state: 누르는 영역이 48dp보다 작음(tag=$tag '$l' ${t.width / density()}x${t.height / density()}dp)"
            // Compose는 이웃과 겹치지 않으면 어떤 누르는 것이든 손가락 영역을 48dp로 넓혀 잡으므로, 보이는 크기도 본다.
            // Material 칩(32dp)·글 단추(40dp)는 둘레 여백으로 48dp를 채운다. 32dp보다 작게 보이면 문제(ATF TouchTargetSizeCheck와 같은 선).
            val vis = 32f * density() - 1f
            if (!clipped && (n.size.width < vis || n.size.height < vis))
                problems += "$state: 보이는 단추가 32dp보다 작음(tag=$tag '$l' ${n.size.width / density()}x${n.size.height / density()}dp)"
        }
        val rootW = rule.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.width }.toFloat()
        for (n in nodes) {
            if (n.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()) continue
            // 문서 이름은 말줄임이 설계. 다만 단추에 밀려 '…'만 남지 않게 화면 폭의 4분의 1은 남아야 한다(글꼴 200% 영어에서 봄)
            if (n.config.getOrNull(SemanticsProperties.TestTag) == "title") {
                if (n.size.width < rootW / 4) problems += "$state: 문서 이름 칸이 너무 좁음(${n.size.width / density()}dp)"
                continue
            }
            // 가로로 밀어 보는 줄 안의 안내 문장이 화면 밖으로 잘림(누르는 단추·한 낱말 이름표는 밀어서 보면 되므로 뺌)
            val sentence = label(n).trim().contains(Regex("\\s"))
            val bb = n.boundsInRoot // 세로로 다 보이는데(세로 스크롤 아래에 있는 것 뺌) 가로만 잘린 것
            if (sentence && !n.config.contains(SemanticsActions.OnClick) && bb.height >= n.size.height - 1f && bb.width < n.size.width - 1f)
                problems += "$state: 안내 문장이 화면 밖으로 잘림 '${label(n)}'"
            val act = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
            val res = ArrayList<TextLayoutResult>()
            if (!act(res)) continue
            for (r in res) {
                // didOverflowWidth는 여유가 있어도 참이 나와 보지 않는다. 줄 수 넘침·말줄임·높이 넘침·낱말이 쪼개짐을 본다.
                val txt = r.layoutInput.text.text
                val ell = (0 until r.lineCount).any { r.isLineEllipsized(it) }
                val split = r.lineCount > 1 && txt.isNotBlank() && !txt.trim().contains(Regex("\\s"))
                if (r.multiParagraph.didExceedMaxLines || ell || r.didOverflowHeight || split)
                    problems += "$state: 글이 잘림 '${label(n)}' (줄넘침=${r.multiParagraph.didExceedMaxLines} 말줄임=$ell 높이=${r.didOverflowHeight} 낱말쪼개짐=$split)"
            }
        }
        if (overlap) {
            val boxes = clickable.filter { it.config.getOrNull(SemanticsProperties.TestTag) != "pdfview" }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                if (isAncestor(a, b) || isAncestor(b, a)) continue
                val r: Rect = a.boundsInRoot.intersect(b.boundsInRoot)
                if (r.width > 1f && r.height > 1f) problems += "$state: 단추가 겹침 '${label(a)}' / '${label(b)}'"
            }
        }
        // ATF(대비·이름·누르는 영역 등). 창(뿌리)마다 따로.
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
            try { rule.onAllNodes(isRoot())[i].tryPerformAccessibilityChecks() }
            catch (e: Throwable) { problems += "$state: ATF ${e.message?.take(600)}" }
        }
    }

    private fun isAncestor(a: SemanticsNode, b: SemanticsNode): Boolean {
        var p = b.parent
        while (p != null) { if (p.id == a.id) return true; p = p.parent }
        return false
    }

    /** 단추를 의미 동작으로 누른다(화면 탭 아님). 없으면 문제로 적고 넘어간다(고치기 전 판에서도 끝까지 보게). */
    private fun click(tag: String) {
        try { rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick) }
        catch (e: Throwable) { problems += "단추 없음: $tag" }
    }

    private fun run(cfg: DeviceConfigurationOverride) {
        rule.enableAccessibilityChecks()
        val m = onMain { ViewerModel(app) }.also { model = it }
        rule.setContent {
            DeviceConfigurationOverride(cfg) { HanjangTheme { Surface(Modifier.fillMaxSize()) { App(m) } } }
        }
        check("빈 화면")
        val f = File(tmp, "a.pdf")
        instr.context.assets.open("annots_base.pdf").use { Intake.copy(it, f) }
        onMain { m.openUri(Uri.fromFile(f)) }
        waitUntil("열기") { !m.busy && m.doc != null }
        check("보기")
        click("searchToggle"); check("찾기"); click("searchToggle")
        onMain { m.tools.visible = true }
        for (t in Tool.entries) { onMain { m.tools.tool = t }; check("도구 $t") }
        onMain { m.tools.tool = Tool.TEXT; m.tools.editor = TextEdit(0, PRect(80.0, 80.0, 280.0, 110.0), null, "글자 Text", 12, "left", "#000000") }
        check("쓰는 칸")
        onMain { m.tools.cancelEditor(); m.tools.note = NoteEdit(0, PPoint(40.0, 40.0), null, "") }
        check("메모 창", overlap = false)
        onMain { m.tools.cancelNote(); m.tools.tool = Tool.VIEW }
        click("pagesToggle"); waitUntil("쪽 목록") { true }; check("쪽 목록"); click("closePages")
        click("menu"); check("메뉴", overlap = false)
        click("aboutOpen"); check("정보")
        click("agplFull"); check("라이선스 전문"); click("aboutClose"); click("aboutClose")
        check("정보 닫음")
        assertTrue("접근성 문제 ${problems.size}건:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    /**
     * 검사가 빈말이 아닌지: 일부러 나쁘게 만든 화면(이름 없는 기호 단추, 20dp 단추, 옅은 글, 잘린 글)을 잡는다.
     */
    @Test fun checksCatchBadUi() {
        rule.enableAccessibilityChecks()
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().androidx_background()) {
                androidx.compose.material3.Text("↶", Modifier.size20().androidx_clickable())
                androidx.compose.material3.Text("옅은 글 low contrast", color = androidx.compose.ui.graphics.Color(0xFFDDDDDD))
                androidx.compose.material3.Text("아주 긴 글이 한 줄에 다 들어가지 않아 말줄임이 됩니다 very long text that cannot fit", maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.size20w())
            }
        }
        check("나쁜 화면")
        val p = problems.joinToString("\n")
        assertTrue("이름 없는 단추를 잡음:\n$p", problems.any { "이름 없는 단추" in it })
        // 손가락 영역(touchBounds)은 Compose가 48dp로 넓혀 잡으므로 보이는 크기 검사가 잡는다(ATF는 기기 판에 따라 안 잡기도 함, A90에서 확인)
        assertTrue("작은 단추를 잡음:\n$p", problems.any { "32dp보다 작음" in it })
        assertTrue("잘린 글을 잡음:\n$p", problems.any { "글이 잘림" in it })
        // 글자 대비는 ATF Compose 연동이 화면을 찍지 않아 보지 못한다 → ContrastTest가 색 짝을 직접 잰다
    }

    private fun Modifier.size20() = this.then(Modifier.size(20.dp))
    private fun Modifier.size20w() = this.then(Modifier.width(120.dp))
    private fun Modifier.androidx_clickable() = this.then(Modifier.clickable {})
    private fun Modifier.androidx_background() = this.then(Modifier.background(androidx.compose.ui.graphics.Color.White))

    @Test fun korean() = run(DeviceConfigurationOverride.Locales(LocaleList("ko-KR")))
    @Test fun english() = run(DeviceConfigurationOverride.Locales(LocaleList("en-US")))
    @Test fun koreanFont200() = run(DeviceConfigurationOverride.Locales(LocaleList("ko-KR")) then DeviceConfigurationOverride.FontScale(2f))
    @Test fun englishFont200() = run(DeviceConfigurationOverride.Locales(LocaleList("en-US")) then DeviceConfigurationOverride.FontScale(2f))
    @Test fun koreanDark() = run(DeviceConfigurationOverride.Locales(LocaleList("ko-KR")) then DeviceConfigurationOverride.DarkMode(true))
    @Test fun englishDarkFont200() = run(DeviceConfigurationOverride.Locales(LocaleList("en-US")) then
        DeviceConfigurationOverride.DarkMode(true) then DeviceConfigurationOverride.FontScale(2f))
}
