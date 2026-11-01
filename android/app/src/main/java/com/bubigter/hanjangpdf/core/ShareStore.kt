package com.bubigter.hanjangpdf.core

import java.io.File

/*
 * 8-3 공유 사본(설계 3항). 검증된 저장 파일의 사본을 앱 캐시 share/ 아래에 두고 FileProvider가 그 폴더만 연다.
 * 받는 앱이 늦게 읽어도 되게 24시간 남기고, 앱을 켤 때 24시간 지난 것만 지운다.
 * 합계가 500MB를 넘으면 가장 오래된 것부터 지우되 1시간 안에 만든 것은 남긴다.
 */
object ShareStore {
    const val DIR = "share"
    const val KEEP_MS = 24L * 3600 * 1000
    const val FRESH_MS = 3600L * 1000
    const val MAX_BYTES = 500L shl 20

    fun root(cacheDir: File) = File(cacheDir, DIR)

    /** 파일 이름에 쓸 수 없는 글자를 바꾸고 .pdf로 끝나게 한다. */
    fun safeName(name: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "document" }
        return if (base.lowercase().endsWith(".pdf")) base else "$base.pdf"
    }

    /** [src](검증된 파일)를 share/<시각>/<이름>으로 복사해 돌려준다(폴더마다 하나라 같은 이름도 겹치지 않음). */
    fun put(cacheDir: File, src: File, name: String, now: Long = System.currentTimeMillis()): File {
        root(cacheDir).mkdirs()
        var d = File(root(cacheDir), "s$now")
        var k = 0
        // mkdir이 참일 때만 내 폴더(두 화면이 같은 시각에 공유해도 한 폴더를 같이 쓰지 않게)
        while (!d.mkdir()) {
            if (k > 1000) throw DocOpError("공유 폴더를 만들지 못했습니다.")
            d = File(root(cacheDir), "s${now}_${++k}")
        }
        val out = File(d, safeName(name))
        try {
            src.inputStream().use { i -> out.outputStream().use { o -> Saver.copy(i, o) } }
            if (Saver.sha256(out) != Saver.sha256(src)) throw DocOpError("공유 사본이 저장 파일과 다릅니다.")
            out.setLastModified(now)
        } catch (e: Exception) {
            d.deleteRecursively()
            throw e as? DocOpError ?: DocOpError("공유 사본을 만들지 못했습니다: ${e.message}")
        }
        return out
    }

    /** 앱 켤 때 정리. 지운 파일 수를 돌려준다. */
    fun clean(cacheDir: File, now: Long = System.currentTimeMillis()): Int {
        val dirs = root(cacheDir).listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        val left = ArrayList<Pair<File, Long>>() // 폴더, 만든 시각
        for (d in dirs) {
            val t = d.listFiles()?.maxOfOrNull { it.lastModified() } ?: d.lastModified()
            if (now - t > KEEP_MS) { removed += count(d); d.deleteRecursively() } else left.add(d to t)
        }
        var total = left.sumOf { size(it.first) }
        for ((d, t) in left.sortedBy { it.second }) {
            if (total <= MAX_BYTES) break
            if (now - t < FRESH_MS) continue
            total -= size(d)
            removed += count(d)
            d.deleteRecursively()
        }
        return removed
    }

    private fun size(d: File) = d.listFiles()?.sumOf { it.length() } ?: 0L
    private fun count(d: File) = d.listFiles()?.size ?: 0
}
