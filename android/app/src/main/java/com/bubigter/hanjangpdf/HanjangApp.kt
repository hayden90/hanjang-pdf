package com.bubigter.hanjangpdf

import android.app.Application
import com.bubigter.hanjangpdf.core.CjkFont
import com.bubigter.hanjangpdf.core.DocOps
import com.bubigter.hanjangpdf.core.ShareStore
import java.io.File

/** 앱 시작 때 PC판과 같은 CJK 대체 글꼴을 fitz에 한 번 설치한다. */
class HanjangApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CjkFont.install(this)
        DocOps.workDir = cacheDir // 저장 임시 사본은 앱 폴더에
        // 앱을 켤 때: 24시간 지난 공유 사본과 지난 실행의 작업 폴더·되돌리기 이력을 지운다(화면을 막지 않게 따로).
        // 지울 폴더는 여기(화면이 생기기 전)서 정해 두어, 정리 스레드가 늦게 돌아도 이번 실행에서 연 문서는 건드리지 않는다.
        val job = startupCleaner(filesDir, cacheDir)
        Thread({ job() }, "startup-clean").start()
    }

    companion object {
        /**
         * 앱 시작 정리 작업. 부르는 때 work/·undo/에 있던 폴더만 대상으로 고정하고, 돌려준 작업이 그것만 지운다
         * (화면은 자기 폴더만 지우므로 지난 실행이 남긴 폴더는 여기서 치운다). 따로 도는 스레드에서 부른다.
         */
        fun startupCleaner(filesDir: File, cacheDir: File): () -> Unit {
            val old = listOf(File(filesDir, "work"), File(cacheDir, "undo")).flatMap { it.listFiles()?.toList() ?: emptyList() }
            return {
                ShareStore.clean(cacheDir)
                old.forEach { it.deleteRecursively() }
            }
        }
    }
}
