package com.bubigter.hanjangpdf.ui

import android.content.ContentResolver
import android.net.Uri
import com.bubigter.hanjangpdf.core.SaveTarget
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** SAF·다른 앱이 준 content:// 주소를 저장 대상으로. 쓰기는 "wt"(처음부터 다시 쓰기). */
class UriTarget(private val cr: ContentResolver, val uri: Uri) : SaveTarget {
    override val id: String get() = uri.toString()
    override fun openRead(): InputStream = cr.openInputStream(uri) ?: throw FileNotFoundException(id)
    override fun openWrite(): OutputStream = cr.openOutputStream(uri, "wt") ?: throw FileNotFoundException(id)
}
