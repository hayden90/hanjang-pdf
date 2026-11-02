package com.bubigter.hanjangpdf

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bubigter.hanjangpdf.ui.About
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 8-5 정보 화면(설계 8항): 앱에 든 고지·전문이 실제 빌드 산출물과 맞는지.
 * - 부품 고지는 빌드 때 런타임 의존성에서 뽑았다(MuPDF fitz = AGPL, 나머지 Apache 2.0, 글꼴 줄).
 * - AGPL v3·Apache 2.0 전문이 앱 안에 있다(인터넷 없이 읽힘).
 * - 소스 태그는 android-v판, 주소는 그 태그. 인터넷 권한은 없다(링크는 브라우저로 넘기기만).
 */
@RunWith(AndroidJUnit4::class)
class AboutTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noticesFromRealDependencies() {
        val n = About.notices(ctx)
        assertTrue("MuPDF fitz AGPL: $n", n.any { it.first.startsWith("com.artifex.mupdf:fitz:") && "AGPL v3" in it.second })
        assertTrue("androidx 부품: $n", n.any { it.first.startsWith("androidx.compose.") && "Apache" in it.second })
        assertTrue("kotlin 표준 라이브러리: $n", n.any { it.first.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") })
        assertTrue("글꼴 줄: $n", n.any { "Droid Sans Fallback" in it.first })
        // 일반 jar 부품도 빠지지 않는다(고치기 전에는 산출물 종류 맞추기에서 빠졌다)
        assertTrue("코루틴 core·jspecify: $n", n.any { it.first.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:") } &&
            n.any { it.first.startsWith("org.jspecify:jspecify:") })
        assertTrue("판 맞추기 목록(bom)은 넣지 않음: $n", n.none { "-bom:" in it.first })
        assertTrue("시험 전용 부품(junit·espresso)은 고지에 넣지 않음: $n",
            n.none { "junit" in it.first || "espresso" in it.first })
        assertTrue("라이선스 칸이 비지 않음", n.all { it.second.isNotBlank() && it.first.isNotBlank() })
    }

    @Test fun fullTextsBundled() {
        val agpl = About.text(ctx, "LICENSE.txt")
        assertTrue(agpl.trimStart().startsWith("GNU AFFERO GENERAL PUBLIC LICENSE"))
        assertTrue("AGPL 전문 끝까지", "END OF TERMS AND CONDITIONS" in agpl && agpl.length > 30_000)
        val apache = About.text(ctx, "APACHE-2.0.txt")
        assertTrue("Apache License" in apache && "END OF TERMS AND CONDITIONS" in apache)
    }

    @Test fun sourceTagAndNoInternet() {
        val v = About.version(ctx)
        assertEquals("android-v$v", About.tag(ctx))
        assertEquals("${About.REPO}/tree/android-v$v/android", About.sourceUrl(ctx))
        assertTrue(About.buildUrl(ctx).endsWith("/android-v$v/android/README.md"))
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toList().orEmpty()
        assertFalse("인터넷 권한 없음: $perms", "android.permission.INTERNET" in perms)
        assertFalse("저장소 권한 없음: $perms", perms.any { "STORAGE" in it })
    }
}
