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
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun run() {
        // 그룹 앞부분 → 라이선스(가장 긴 것부터 맞춘다)
        val known = listOf(
            "com.artifex.mupdf" to "GNU AGPL v3 (Artifex Software)",
            "androidx." to "Apache License 2.0",
            "org.jetbrains.kotlinx" to "Apache License 2.0",
            "org.jetbrains.kotlin" to "Apache License 2.0",
            "org.jetbrains:annotations" to "Apache License 2.0",
            "com.google.guava:listenablefuture" to "Apache License 2.0",
            "org.jspecify" to "Apache License 2.0",
        ).sortedByDescending { it.first.length }
        val lines = coords.get().map { c ->
            val lic = known.firstOrNull { c.startsWith(it.first) }?.second
                ?: throw GradleException("정보 화면 부품 고지: 라이선스를 모르는 부품 $c (app/build.gradle.kts AboutAssetsTask에 더하세요)")
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

    buildTypes {
        release {
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
                    .distinct().sorted()
            })
            license.set(rootProject.layout.projectDirectory.file("../LICENSE"))
            apache.set(rootProject.layout.projectDirectory.file("licenses/APACHE-2.0.txt"))
        }
        v.sources.assets?.addGeneratedSourceDirectory(t, AboutAssetsTask::outputDir)
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
