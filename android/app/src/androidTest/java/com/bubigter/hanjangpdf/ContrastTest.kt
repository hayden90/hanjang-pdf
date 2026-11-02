package com.bubigter.hanjangpdf

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 8-5 색 대비(WCAG AA: 글 4.5:1, 굵고 큰 글·단추 모양 3:1). 밝은·어두운 화면에서 앱이 쓰는 글·바탕 짝을 직접 잰다
 * (ATF의 Compose 연동은 화면을 찍지 않아 대비를 보지 못한다).
 * 글자 상자 칸의 글 색(검정·빨강·파랑·초록)은 사용자가 고른 잉크 색 미리보기라 화면 글 기준에서 뺀다.
 */
@RunWith(AndroidJUnit4::class)
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }

    private fun pairs(c: ColorScheme) = listOf(
        "본문(onSurface/surface)" to (c.onSurface to c.surface),
        "안내 글(onSurfaceVariant/surface)" to (c.onSurfaceVariant to c.surface),
        "글 단추(primary/surface)" to (c.primary to c.surface),
        "오류 글(error/surface)" to (c.error to c.surface),
        "고른 칩 글(onSecondaryContainer/secondaryContainer)" to (c.onSecondaryContainer to c.secondaryContainer),
        "메뉴 글(onSurface/surfaceContainer)" to (c.onSurface to c.surfaceContainer),
        "대화상자 글(onSurfaceVariant/surfaceContainerHigh)" to (c.onSurfaceVariant to c.surfaceContainerHigh),
        "쓰는 칸 손잡이 안내(onSurfaceVariant/surfaceContainerLow)" to (c.onSurfaceVariant to c.surfaceContainerLow),
    )

    @Test fun themeTextPairs() {
        val bad = ArrayList<String>()
        for ((name, scheme) in listOf("밝은" to lightColorScheme(), "어두운" to darkColorScheme())) {
            for ((what, p) in pairs(scheme)) {
                val r = ratio(p.first, p.second)
                if (r < 4.5) bad += "$name $what ${"%.2f".format(r)}"
            }
        }
        assertTrue("4.5:1 미만: $bad", bad.isEmpty())
    }

    /** 앱이 직접 정한 색: 쪽 번호(흰 글, 반투명 검정 바탕이 회색 바깥·흰 쪽 위에 놓임), 손잡이 ✥(흰 글/파랑). */
    @Test fun customColors() {
        val label = Color(0xAA000000)
        val list = listOf(
            "쪽 번호 위 회색 바깥" to ratio(Color.White, label.compositeOver(Color(0xFFE6E6E6))),
            "쪽 번호 위 흰 쪽" to ratio(Color.White, label.compositeOver(Color.White)),
            "손잡이 ✥" to ratio(Color.White, Color(0xFF1565C0)),
        )
        val bad = list.filter { it.second < 4.5 }.map { "${it.first} ${"%.2f".format(it.second)}" }
        assertTrue("4.5:1 미만: $bad", bad.isEmpty())
    }

    /** 검사가 빈말이 아닌지: 옅은 회색 글은 잡는다. */
    @Test fun catchesLowContrast() {
        assertTrue(ratio(Color(0xFFDDDDDD), Color.White) < 4.5)
        assertTrue(ratio(Color.Black, Color.White) > 20)
    }
}
