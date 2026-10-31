pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF(fitz)는 Artifex 저장소에만 있다. 이 저장소에서는 com.artifex.mupdf만 받는다.
        exclusiveContent {
            forRepository { maven("https://maven.ghostscript.com") }
            filter { includeGroup("com.artifex.mupdf") }
        }
    }
}
rootProject.name = "hanjangpdf"
include(":app")
