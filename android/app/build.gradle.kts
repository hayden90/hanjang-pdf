import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 정보 화면에 넣을 글(설계 8항, 8-5): 저장소 LICENSE(AGPL v3 전문)를 그대로, 쓰는 부품 목록은 이 빌드의 실제 런타임
 * 의존성에서 뽑아 assets/about/에 넣는다. 라이선스를 모르는 부품이 들어오면 빌드를 멈춘다(고지가 빠지지 않게).
 */
abstract class AboutAssetsTask : DefaultTask() {
    @get:Input abstract val coords: ListProperty<String>
    @get:InputFile abstract val license: RegularFileProperty
    @get:InputFile abstract val apache: RegularFileProperty
    @get:InputFile abstract val modules: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun run() {
        // 그룹:모듈(판 무관) → 라이선스. 정확히 같은 것만 맞춘다(앞부분만 보면 새 부품이 끼어 들어감, 8-5 판 1)
        val known = modules.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split("\t")
            if (parts.size != 2) throw GradleException("licenses/modules.tsv 줄 형식이 틀립니다: $line")
            parts[0] to parts[1]
        }
        val lines = coords.get().map { c ->
            val lic = known[c.substringBeforeLast(":")]
                ?: throw GradleException("정보 화면 부품 고지: 라이선스를 모르는 부품 $c (android/licenses/modules.tsv에 더하세요)")
            "$c\t$lic"
        } + "Droid Sans Fallback (font bundled in assets)\tApache License 2.0" // 글자 상자에 넣는 CJK 글꼴(8-1b)
        val dir = outputDir.get().asFile.resolve("about")
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("notices.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        license.get().asFile.copyTo(dir.resolve("LICENSE.txt"), overwrite = true)
        apache.get().asFile.copyTo(dir.resolve("APACHE-2.0.txt"), overwrite = true)
    }
}

/**
 * 출시 빌드 병합 매니페스트 검사(8-5 판 1 LOW, 8-6, 8-7): 구성 요소(열림 여부와 상관없이)와 열린 것, 권한이 정한 것뿐인지.
 * 시험용 activity(ui-test-manifest)가 debug에만 들어가는지, 인터넷·저장소 권한이 끼지 않았는지를 빌드 때 막는다.
 */
abstract class ManifestCheckTask : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    /** 기준 매니페스트(android/release_manifest.golden.txt): 요소·속성 전부를 정규화한 꼴. 다르면 멈춘다. */
    @get:InputFile abstract val golden: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun run() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        fun names(tag: String, filter: (org.w3c.dom.Element) -> Boolean = { true }): Set<String> {
            val l = doc.getElementsByTagName(tag)
            return (0 until l.length).map { l.item(it) as org.w3c.dom.Element }.filter(filter)
                .map { it.getAttributeNS(ns, "name") }.toSet()
        }
        // 구성 요소는 "태그:이름"으로 비교한다(8-7 판 1 HIGH: 허용한 이름을 쓴 activity-alias가 이름만 비교하면 빠져나감)
        fun comps(tag: String, filter: (org.w3c.dom.Element) -> Boolean = { true }) = names(tag, filter).map { "$tag:$it" }
        // 매니페스트 요소는 네임스페이스가 없어야 한다(8-7 판 3 HIGH: <x:receiver xmlns:x=…>는 태그 이름 찾기에서 빠지지만
        // 기기는 로컬 이름으로 읽음). 시험용 <instrumentation>도 출시에 들어가면 안 된다(판 3 LOW).
        val everyEl = doc.getElementsByTagNameNS("*", "*")
        val strayEls = (0 until everyEl.length).map { everyEl.item(it) as org.w3c.dom.Element }
            .filter { it.namespaceURI != null || it.localName == "instrumentation" }
            .map { "${it.namespaceURI ?: ""}:${it.localName}" }
        // 요소 자리도 허용 목록으로 본다(8-7 판 4 HIGH: <permission>처럼 따로 보지 않던 태그가 그대로 통과).
        // 태그 이름을 하나씩 막는 대신, 지금 release 병합 매니페스트에 있는 자리 밖의 요소는 모두 멈춘다.
        val okPaths = setOf(
            "manifest", "manifest/uses-sdk", "manifest/permission", "manifest/uses-permission", "manifest/application",
            "manifest/application/activity", "manifest/application/provider", "manifest/application/receiver",
            "manifest/application/provider/meta-data",
            "manifest/application/activity/intent-filter", "manifest/application/receiver/intent-filter",
            "manifest/application/activity/intent-filter/action", "manifest/application/activity/intent-filter/category",
            "manifest/application/activity/intent-filter/data", "manifest/application/receiver/intent-filter/action",
        )
        fun pathOf(e: org.w3c.dom.Element): String {
            var n: org.w3c.dom.Node? = e
            val parts = ArrayList<String>()
            while (n is org.w3c.dom.Element) { parts.add(0, n.localName ?: n.tagName); n = n.parentNode }
            return parts.joinToString("/")
        }
        val badPaths = (0 until everyEl.length).map { pathOf(everyEl.item(it) as org.w3c.dom.Element) }.toSet() - okPaths
        val declared = names("permission")
        val okDeclared = setOf("com.bubigter.hanjangpdf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") // AndroidX core가 만드는 앱 전용 권한
        val tags = listOf("activity", "activity-alias", "service", "receiver", "provider")
        val all = tags.flatMap { comps(it) }.toSet()
        // exported 값은 글자 그대로 true/false만 받는다(8-7 판 2: "TRUE"·" true"·@bool/ 참조가 열림 검사를 빠져나감).
        // 값이 없으면 intent-filter가 있을 때 열림으로 본다(옛 기본값).
        val badExported = ArrayList<String>()
        fun isExported(tag: String, e: org.w3c.dom.Element): Boolean {
            val v = e.getAttributeNS(ns, "exported")
            return when (v) {
                "true" -> true
                "false" -> false
                "" -> e.getElementsByTagName("intent-filter").length > 0
                else -> { badExported += "$tag:${e.getAttributeNS(ns, "name")}=$v"; true }
            }
        }
        val exported = tags.flatMap { t -> comps(t) { isExported(t, it) } }.toSet()
        // 열린 것은 부를 때 필요한 권한까지 맞아야 한다(8-7 판 2 LOW: DUMP 권한을 떼어도 통과하던 것)
        fun permissionOf(key: String): String {
            val (tag, name) = key.split(":", limit = 2)
            val l = doc.getElementsByTagName(tag)
            return (0 until l.length).map { l.item(it) as org.w3c.dom.Element }
                .filter { it.getAttributeNS(ns, "name") == name }.joinToString(",") { it.getAttributeNS(ns, "permission") }
        }
        // 허용 목록(8-7): 숨은(exported=false) 시험·디버그 구성 요소가 끼어도 멈추게, 지금 release에 있는 것만
        val okAll = setOf(
            "activity:com.bubigter.hanjangpdf.MainActivity",
            "provider:androidx.core.content.FileProvider", // 공유 사본(share_paths.xml), 닫힘
            "provider:androidx.startup.InitializationProvider", // AndroidX 시작 초기화(profileinstaller 등), 닫힘
            "receiver:androidx.profileinstaller.ProfileInstallReceiver",
        )
        val okExported = mapOf( // 열려도 되는 것 → 붙어 있어야 하는 permission 속성("" = 없음)
            "activity:com.bubigter.hanjangpdf.MainActivity" to "",
            "receiver:androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP", // AndroidX 기본
        )
        val perms = names("uses-permission") + names("uses-permission-sdk-23")
        val okPerms = setOf("com.bubigter.hanjangpdf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val errs = strayEls.map { "정하지 않은 요소: $it" } + badPaths.map { "정하지 않은 요소 자리: $it" } +
            (declared - okDeclared).map { "선언한 권한: $it" } + (all - okAll).map { "구성 요소: $it" } + badExported.map { "exported 값이 true/false가 아님: $it" } +
            (exported - okExported.keys).map { "열린 구성 요소: $it" } +
            exported.filter { it in okExported && permissionOf(it) != okExported[it] }.map { "열린 구성 요소의 permission이 다름: $it" } +
            (perms - okPerms).map { "권한: $it" }
        // 통째 대조(8-7 판 4: 태그·속성을 하나씩 막는 방식은 네임스페이스·값 표현으로 계속 빠져나감).
        // 모든 요소를 문서 순서로, 자리(네임스페이스 포함)와 모든 속성([네임스페이스, 이름, 값], 정렬)을 JSON 한 줄씩 적어
        // 기준 파일과 글자 그대로 같아야 통과한다. 판 번호(versionCode·versionName)만 뺀다.
        val canon = (0 until everyEl.length).map { i ->
            val e = everyEl.item(i) as org.w3c.dom.Element
            val attrs = (0 until e.attributes.length).map { e.attributes.item(it) }
                .filter { !(it.namespaceURI == ns && it.localName in setOf("versionCode", "versionName")) }
                .map { listOf(it.namespaceURI ?: "", it.localName ?: it.nodeName, it.nodeValue) }
                .sortedWith(compareBy({ it[0] }, { it[1] }))
            var n: org.w3c.dom.Node? = e
            val parts = ArrayList<List<String>>()
            while (n is org.w3c.dom.Element) { parts.add(0, listOf(n.namespaceURI ?: "", n.localName ?: n.tagName)); n = n.parentNode }
            // JSON으로 적어 값 안의 글자가 다른 속성처럼 보이지 않게 한다(8-7 판 5: 공백으로 이어 붙이면 경계가 겹침)
            groovy.json.JsonOutput.toJson(listOf(parts, attrs))
        }
        report.get().asFile.parentFile.mkdirs()
        File(report.get().asFile.parentFile, "release.canon.txt").writeText(canon.joinToString("\n", postfix = "\n"))
        val want = golden.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (canon != want) {
            val extra = canon - want.toSet()
            val missing = want - canon.toSet()
            throw GradleException("출시 매니페스트가 기준(android/release_manifest.golden.txt)과 다릅니다.\n" +
                (errs.map { "- $it" } + extra.map { "+ 새로 생김: $it" } + missing.map { "- 사라짐: $it" } +
                    (if (extra.isEmpty() && missing.isEmpty()) listOf("순서가 다름") else emptyList())).joinToString("\n") +
                "\n바뀐 것이 맞으면 검토를 받은 뒤 build/manifestCheck/release.canon.txt로 기준 파일을 바꾸세요.")
        }
        if (errs.isNotEmpty()) throw GradleException("출시 매니페스트에 정하지 않은 것이 있습니다:\n" + errs.joinToString("\n"))
        report.get().asFile.writeText((all.sorted() + exported.sorted().map { "열림 $it" } + perms.sorted()).joinToString("\n", postfix = "\n"))
    }
}

/**
 * 출시 서명(설계 9항, 8-6): 업로드 키 자리와 비밀번호는 저장소에 두지 않고 빌드할 때 받는다.
 * - `-PhanjangUploadEnv=<env 파일>` 또는 환경 변수 HANJANG_UPLOAD_ENV: KEY=값 줄로 된 파일
 * - 또는 환경 변수 HANJANG_UPLOAD_STORE_FILE·_KEY_ALIAS·_STORE_PASSWORD·_KEY_PASSWORD를 바로
 * 아무것도 없으면 release는 서명하지 않고 만든다. 일부만 있으면 빌드를 멈춘다(값은 오류 글에 넣지 않는다).
 */
val uploadKeyNames = listOf("STORE_FILE", "KEY_ALIAS", "STORE_PASSWORD", "KEY_PASSWORD").map { "HANJANG_UPLOAD_$it" }
val uploadKey: Map<String, String>? = run {
    val m = HashMap<String, String>()
    val envPath = providers.gradleProperty("hanjangUploadEnv").orElse(providers.environmentVariable("HANJANG_UPLOAD_ENV")).orNull
    if (envPath != null) {
        val f = File(envPath.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home")))
        if (!f.isFile) throw GradleException("업로드 키 env 파일이 없습니다(hanjangUploadEnv)")
        providers.fileContents(layout.projectDirectory.file(f.absolutePath)).asText.get().lines().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0 && !line.trimStart().startsWith("#")) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
    }
    uploadKeyNames.forEach { k -> providers.environmentVariable(k).orNull?.let { m.putIfAbsent(k, it) } }
    val have = uploadKeyNames.filter { !m[it].isNullOrEmpty() }
    when {
        have.isEmpty() -> null
        have.size < uploadKeyNames.size -> throw GradleException("업로드 키 설정이 모자랍니다: ${uploadKeyNames - have.toSet()}")
        else -> m
    }
}

android {
    namespace = "com.bubigter.hanjangpdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bubigter.hanjangpdf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadKey != null) create("upload") {
            // env 파일 경로처럼 ~를 펼친다(8-7). 상대 경로는 Gradle 데몬 폴더 기준이 되어 엉뚱한 곳을 찾으니 받지 않는다
            val store = File(uploadKey.getValue("HANJANG_UPLOAD_STORE_FILE").replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home")))
            if (!store.isAbsolute) throw GradleException("HANJANG_UPLOAD_STORE_FILE는 절대 경로나 ~/로 시작하는 경로여야 합니다")
            storeFile = store
            storePassword = uploadKey.getValue("HANJANG_UPLOAD_STORE_PASSWORD")
            keyAlias = uploadKey.getValue("HANJANG_UPLOAD_KEY_ALIAS")
            keyPassword = uploadKey.getValue("HANJANG_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (uploadKey != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { v ->
        val t = tasks.register<AboutAssetsTask>("${v.name}AboutAssets") {
            // 산출물(artifacts) 목록은 종류 맞추기에서 일반 jar(kotlin-stdlib 등)가 빠져, 해석된 의존성 그래프 전체를 걷는다
            coords.set(v.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                val seen = LinkedHashSet<ResolvedComponentResult>()
                val todo = ArrayDeque(listOf(root))
                while (todo.isNotEmpty()) {
                    val c = todo.removeFirst()
                    if (!seen.add(c)) continue
                    c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { todo.add(it.selected) }
                }
                seen.mapNotNull { c -> (c.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" } }
                    .filterNot { it.contains("-bom:") } // 판 맞추기용 목록(코드 없음)
                    .distinct().sorted() +
                    // 시험용: 모르는 부품을 하나 더 넣어 빌드가 멈추는지 본다(-PaboutExtraCoord=그룹:모듈:판)
                    listOfNotNull(providers.gradleProperty("aboutExtraCoord").orNull)
            })
            license.set(rootProject.layout.projectDirectory.file("../LICENSE"))
            apache.set(rootProject.layout.projectDirectory.file("licenses/APACHE-2.0.txt"))
            modules.set(rootProject.layout.projectDirectory.file("licenses/modules.tsv"))
        }
        v.sources.assets?.addGeneratedSourceDirectory(t, AboutAssetsTask::outputDir)
        if (v.buildType == "release") {
            val name = v.name.replaceFirstChar { it.uppercase() }
            val check = tasks.register<ManifestCheckTask>("${v.name}ManifestCheck") {
                manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                golden.set(rootProject.layout.projectDirectory.file("release_manifest.golden.txt"))
                report.set(layout.buildDirectory.file("manifestCheck/${v.name}.txt"))
            }
            // assemble·bundle만 걸면 packageRelease 같은 패키징 작업을 바로 부를 때 검사를 건너뛴다(8-7)
            val gated = setOf("assemble$name", "bundle$name", "package$name", "package${name}Bundle", "sign${name}Bundle", "package${name}UniversalApk")
            tasks.matching { it.name in gated }.configureEach { dependsOn(check) }
        }
    }
}

dependencies {
    // PC판(PyMuPDF 2.0)이 묶은 MuPDF와 같은 판. 판을 올릴 때는 PC판과 함께 올린다.
    implementation("com.artifex.mupdf:fitz:1.28.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0") // ActivityScenario(다른 앱 열기·공유 끝까지 시험)
    // 8-5 접근성·언어 시험: Compose 의미 트리 검사와 Accessibility Test Framework(화면 탭 없이 읽기만)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
