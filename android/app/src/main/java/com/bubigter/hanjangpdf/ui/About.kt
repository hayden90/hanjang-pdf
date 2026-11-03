package com.bubigter.hanjangpdf.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bubigter.hanjangpdf.R

/**
 * 정보 화면(설계 8항): 앱 이름·판, 저작권, AGPL v3 안내와 전문, 이 판의 소스(GitHub 태그 android-v판)와 빌드 방법,
 * 쓰는 부품 고지(빌드 때 실제 런타임 의존성에서 뽑은 assets/about/notices.tsv). 인터넷 권한이 없으므로 링크는 브라우저로 넘기기만 한다.
 */
object About {
    const val REPO = "https://github.com/hayden90/hanjang-pdf"
    const val BUBIGTER = "https://www.bubigter.com/"

    /** 부빅터 연결 주소. [where](empty·menu·about)로 어느 자리에서 눌렀는지 방문 통계에서 가른다(PC판 BUBIGTER_URL과 같은 꼴). */
    fun bubigterUrl(where: String) = "${BUBIGTER}?utm_source=hanjangpdf_android&utm_medium=app&utm_content=$where"

    fun version(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** 이 판의 소스 태그(android-v판)와 그 태그의 주소. */
    fun tag(ctx: Context) = "android-v" + version(ctx)
    fun sourceUrl(ctx: Context) = "$REPO/tree/${tag(ctx)}/android"
    fun buildUrl(ctx: Context) = "$REPO/blob/${tag(ctx)}/android/README.md"

    /** 부품 고지 (좌표, 라이선스) 목록. */
    fun notices(ctx: Context): List<Pair<String, String>> =
        ctx.assets.open("about/notices.tsv").bufferedReader().readLines().filter { it.isNotBlank() }
            .map { it.substringBefore('\t') to it.substringAfter('\t') }

    fun text(ctx: Context, name: String): String = ctx.assets.open("about/$name").bufferedReader().readText()

    /** 링크를 브라우저(다른 앱)로 넘긴다. 열 앱이 없으면 주소를 알린다. */
    fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.err_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun AboutScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    // 전문 보기: null = 안내 화면, 아니면 그 파일 이름
    var full by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler { if (full != null) full = null else onClose() }
    val notices = remember { About.notices(ctx) }
    Column(Modifier.fillMaxSize().testTag("about")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (full == null) R.string.about else R.string.about_license_title),
                Modifier.weight(1f).padding(8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { if (full != null) full = null else onClose() }, Modifier.testTag("aboutClose")) {
                Text(stringResource(R.string.close))
            }
        }
        HorizontalDivider()
        val f = full
        if (f != null) {
            val body = remember(f) { About.text(ctx, f) }
            Text(body, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("licenseText"),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.app_name) + " " + About.version(ctx), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_desc), Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.about_copyright), Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.about_bubigter), Modifier.padding(top = 4.dp))
            TextButton(onClick = { About.open(ctx, About.bubigterUrl("about")) }, Modifier.testTag("bubigterAbout")) {
                Text(stringResource(R.string.bubigter_link))
            }
            Text(stringResource(R.string.about_agpl), Modifier.padding(top = 12.dp))
            TextButton(onClick = { full = "LICENSE.txt" }, Modifier.testTag("agplFull")) { Text(stringResource(R.string.about_agpl_full)) }
            Text(stringResource(R.string.about_source, About.tag(ctx)), Modifier.padding(top = 12.dp))
            TextButton(onClick = { About.open(ctx, About.sourceUrl(ctx)) }, Modifier.testTag("sourceLink")) {
                Text(stringResource(R.string.about_open_source))
            }
            Text(stringResource(R.string.about_build))
            TextButton(onClick = { About.open(ctx, About.buildUrl(ctx)) }, Modifier.testTag("buildLink")) {
                Text(stringResource(R.string.about_open_build))
            }
            Text(stringResource(R.string.about_privacy), Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.about_components), Modifier.padding(top = 16.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall)
            for ((c, lic) in notices) Text("$c — $lic", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { full = "APACHE-2.0.txt" }, Modifier.testTag("apacheFull")) { Text(stringResource(R.string.about_apache_full)) }
        }
    }
}
