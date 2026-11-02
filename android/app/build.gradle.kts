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
 * 출시 빌드 병합 매니페스트 검사(8-5 판 1 LOW, 8-6): 밖에 열린(exported) 구성 요소와 권한이 정한 것뿐인지.
 * 시험용 activity(ui-test-manifest)가 debug에만 들어가는지, 인터넷·저장소 권한이 끼지 않았는지를 빌드 때 막는다.
 */
abstract class ManifestCheckTask : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
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
        val exported = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { t ->
            names(t) { it.getAttributeNS(ns, "exported") == "true" }
        }.toSet()
        val okExported = setOf(
            "com.bubigter.hanjangpdf.MainActivity",
            "androidx.profileinstaller.ProfileInstallReceiver", // android.permission.DUMP로만 부름(AndroidX 기본)
        )
        val perms = names("uses-permission") + names("uses-permission-sdk-23")
        val okPerms = setOf("com.bubigter.hanjangpdf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val errs = (exported - okExported).map { "열린 구성 요소: $it" } + (perms - okPerms).map { "권한: $it" }
        if (errs.isNotEmpty()) throw GradleException("출시 매니페스트에 정하지 않은 것이 있습니다:\n" + errs.joinToString("\n"))
        report.get().asFile.writeText((exported.sorted() + perms.sorted()).joinToString("\n", postfix = "\n"))
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
            storeFile = File(uploadKey.getValue("HANJANG_UPLOAD_STORE_FILE"))
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
                report.set(layout.buildDirectory.file("manifestCheck/${v.name}.txt"))
            }
            tasks.matching { it.name == "assemble$name" || it.name == "bundle$name" }.configureEach { dependsOn(check) }
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
