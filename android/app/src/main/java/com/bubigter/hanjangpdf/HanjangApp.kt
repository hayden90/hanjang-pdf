package com.bubigter.hanjangpdf

import android.app.Application
import com.bubigter.hanjangpdf.core.CjkFont
import com.bubigter.hanjangpdf.core.DocOps

/** 앱 시작 때 PC판과 같은 CJK 대체 글꼴을 fitz에 한 번 설치한다. */
class HanjangApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CjkFont.install(this)
        DocOps.workDir = cacheDir // 저장 임시 사본은 앱 폴더에
    }
}
