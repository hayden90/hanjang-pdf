package com.bubigter.hanjangpdf.core

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/*
 * 8-3 저장(설계 4항). 원본이 깨지지 않게 하는 순서:
 * ① 작업 문서를 앱 폴더의 새 파일로 저장 → ② 다시 열어 쪽 수·주석 수 검증([Saver.saveVerified])
 * ③ 기본은 '다른 이름으로 저장'([Saver.copyTo]) ④ '원본에 덮어쓰기'([Overwrite.run])는 고를 때만:
 *    덮기 직전 원본 바이트를 before.pdf로 저장·fsync → 해시를 saving.json에 적고 fsync → "wt"로 쓰기
 *    → 다시 읽어 SHA-256 비교 → saving.json 지우고 그다음 before.pdf 지움. 실패·불일치면 before.pdf를 되써 넣는다.
 * 프로세스가 도중에 죽으면 다음 실행 때 [Recovery]가 saving.json을 찾아 묻는다.
 * 바이트 배열에 파일 전체를 담지 않고 64KB씩 흘려 보낸다(200MB급 문서 메모리).
 */

/** 저장할 곳(SAF URI 등). 시험은 파일·고장 흉내로 바꿔 끼운다. */
interface SaveTarget {
    fun openRead(): InputStream
    /** 처음부터 다시 쓰기(SAF "wt"). */
    fun openWrite(): OutputStream
    /** saving.json에 적을 이름(URI 문자열). */
    val id: String
}

object Saver {
    /** 문서의 주석 수(쪽마다 /Annots 배열 길이의 합: 링크·입력 칸 포함). */
    fun countAnnots(doc: PDFDocument): Int {
        var n = 0
        for (i in 0 until doc.countPages()) {
            val a = doc.findPage(i).get("Annots")
            if (a.isArray) n += a.size()
        }
        return n
    }

    /**
     * ①② 작업 문서를 [out]에 저장하고 다시 열어 쪽 수·주석 수를 확인한다. 작업 문서는 바꾸지 않는다
     * ([DocOps.atomicSave]가 사본에서 정리 저장하므로 저장 뒤 이어서 편집·재저장해도 된다).
     * 실패하면 [out]은 생기지 않고 DocOpError.
     */
    fun saveVerified(doc: PDFDocument, out: File) {
        DocOps.atomicSave(doc, out.path)
        try {
            val check = Document.openDocument(out.path).asPDF() ?: throw DocOpError("저장한 파일이 PDF가 아닙니다.")
            try {
                if (check.needsPassword()) throw DocOpError("저장한 파일에 암호가 남아 있습니다.")
                if (check.countPages() != doc.countPages()) throw DocOpError("저장한 파일의 쪽 수가 다릅니다.")
                if (countAnnots(check) != countAnnots(doc)) throw DocOpError("저장한 파일의 주석 수가 다릅니다.")
            } finally {
                check.destroy()
            }
        } catch (e: Exception) {
            out.delete()
            throw e as? DocOpError ?: DocOpError("저장한 파일을 확인하지 못했습니다: ${e.message}")
        }
    }

    /**
     * ③ 검증된 파일을 [target]에 쓰고 다시 읽어 SHA-256을 비교한다('다른 이름으로 저장'·내보내기·복구본 저장).
     * 다르면 DocOpError(새 문서라 원본은 건드리지 않음).
     */
    fun copyTo(src: File, target: SaveTarget) {
        val want = sha256(src)
        try {
            target.openWrite().use { o -> src.inputStream().use { i -> copy(i, o) } }
        } catch (e: Exception) {
            throw DocOpError("파일에 쓰지 못했습니다: ${e.message}")
        }
        val got = try { target.openRead().use { sha256(it) } } catch (e: Exception) { null }
        if (got != want) throw DocOpError("쓴 파일을 다시 읽어 보니 내용이 다릅니다.")
    }

    /** 64KB씩 복사하고, 파일이면 fsync한다. */
    fun copy(i: InputStream, o: OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val r = i.read(buf)
            if (r < 0) break
            o.write(buf, 0, r)
            n += r
        }
        o.flush()
        if (o is FileOutputStream) try { o.fd.sync() } catch (e: Exception) { /* 동기화를 못 하는 곳(파이프 등) */ }
        return n
    }

    fun sha256(f: File): String = f.inputStream().use { sha256(it) }

    fun sha256(s: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = s.read(buf)
            if (r < 0) break
            md.update(buf, 0, r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * 같은 원본(URI 문자열)의 덮어쓰기·복구·정리를 프로세스 안에서 한 줄로 세운다(화면이 둘이어도).
 * 같은 스레드는 다시 들어갈 수 있다(덮기 실패 → 되써 넣기). 잠금은 원본 하나에 하나씩만 잡으므로 서로 막힐 일이 없다.
 * 다른 앱의 동시 쓰기는 막지 못한다.
 */
object UriLock {
    private val locks = HashMap<String, Any>()
    fun of(id: String): Any = synchronized(locks) { locks.getOrPut(id) { Any() } }
    inline fun <T> with(id: String, body: () -> T): T = synchronized(of(id)) { body() }
}

/** 시험에서 '덮는 도중 프로세스가 죽음'을 흉내 낸다. Exception이 아니라 복구 경로가 돌지 않는다. */
class SimulatedDeath(step: String) : Error("시험용 강제 중단: $step")

object Overwrite {
    /** 시험 전용: 단계 이름을 받아 예외(실패)나 [SimulatedDeath](죽음)를 던질 수 있다. 앱은 늘 null. */
    @Volatile var hook: ((String) -> Unit)? = null
    private fun step(name: String) { hook?.invoke(name) }

    sealed class Result {
        object Ok : Result()
        /** 덮기에 실패했지만 원본을 덮기 전 판으로 되돌렸다(확인함). */
        class Restored(val reason: String) : Result()
        /** 원본에 쓰기 전에 실패했다(원본은 그대로). */
        class Untouched(val reason: String) : Result()
        /** 되써 넣기도 실패했다. [pending]의 before.pdf를 '다른 이름으로 저장'하도록 안내한다. */
        class RestoreFailed(val reason: String, val pending: Recovery.Pending) : Result()
        /**
         * 같은 원본에 끝나지 않은 덮어쓰기 기록이 있어 덮지 않았다(원본은 그대로). 그 기록을 먼저 처리하게 묻는다.
         * 그냥 덮으면 나중에 그 옛 기록으로 복구할 때 이번 저장이 옛 판으로 덮인다.
         */
        class Unresolved(val pending: Recovery.Pending, val readable: Boolean) : Result()
    }

    /**
     * ④ 원본 덮어쓰기. [verified]는 ①②를 거친 파일, [recRoot]는 filesDir/recovery(작업 폴더 정리와 따로).
     * 어떤 실패든 작업 문서·이력·'저장 안 함'은 부르는 쪽이 그대로 둔다(성공 때만 저장 표시).
     * 같은 원본의 덮기·복구와는 [UriLock]으로 한 줄로 선다(겹치면 먼저 시작한 쪽이 뒤에 성공한 저장을 되돌리므로).
     */
    fun run(target: SaveTarget, verified: File, recRoot: File, title: String): Result = UriLock.with(target.id) {
        // 같은 원본의 끝나지 않은 기록: 원본이 그 덮기 전 판이나 새 판이면(손대기 전·다 쓴 뒤) 정리하고, 아니면 먼저 처리하게 한다.
        for (p in Recovery.list(recRoot)) {
            if (p.uri != target.id) continue
            when (Recovery.settle(p, target)) {
                null, Recovery.State.BEFORE, Recovery.State.NEW -> {}
                Recovery.State.OTHER -> return@with Result.Unresolved(p, true)
                Recovery.State.UNREADABLE -> return@with Result.Unresolved(p, false)
            }
        }
        val newSha = Saver.sha256(verified)
        val dir = File(recRoot, "s" + System.currentTimeMillis() + "_" + (0..9999).random())
        // 도는 동안 다른 화면의 복구 확인([Recovery.list])이 이 폴더를 지우거나 묻지 않게 표시한다.
        Recovery.begin(dir)
        try {
            runActive(target, verified, dir, title, newSha)
        } finally {
            Recovery.end(dir)
        }
    }

    private fun runActive(target: SaveTarget, verified: File, dir: File, title: String, newSha: String): Result {
        val before = File(dir, Recovery.BEFORE)
        val json = File(dir, Recovery.JSON)
        val beforeSha: String
        try {
            dir.mkdirs()
            dir.parentFile?.let { Recovery.syncDir(it) }
            // 이번 덮기 직전 원본(열 때 사본이 아니라 지금 바이트)
            beforeSha = Recovery.writeFile(before) { o -> target.openRead().use { Saver.copy(it, o) } }
            step("before")
            Recovery.writeJson(json, JSONObject().put("uri", target.id).put("title", title)
                .put("before", beforeSha).put("new", newSha).put("time", System.currentTimeMillis()))
            step("json")
        } catch (e: Exception) {
            dir.deleteRecursively()
            return Result.Untouched(e.message ?: "")
        }
        // "wt" 열기 자체가 실패하면 원본이 그대로인지 보고, 그대로면 되써 넣지 않는다(원본이 깨졌다고 잘못 알리지 않게).
        val out = try {
            target.openWrite()
        } catch (e: Exception) {
            val now = try { target.openRead().use { Saver.sha256(it) } } catch (x: Exception) { null }
            if (now == beforeSha) {
                Recovery.finish(Recovery.Pending(dir, target.id, title, beforeSha, newSha))
                return Result.Untouched(e.message ?: e.toString())
            }
            null
        }
        val reason = if (out == null) "원본을 쓰려고 열지 못했습니다." else try {
            out.use { o ->
                step("write")
                verified.inputStream().use { i -> Saver.copy(i, o) }
            }
            step("written")
            val got = target.openRead().use { Saver.sha256(it) }
            step("verify")
            if (got == newSha) null else "다시 읽은 내용이 다릅니다."
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (reason == null) {
            step("cleanup")
            json.delete() // 이 순서(json 먼저)
            before.delete()
            dir.delete()
            return Result.Ok
        }
        val p = Recovery.Pending(dir, target.id, title, beforeSha, newSha)
        return if (Recovery.restore(p, target)) Result.Restored(reason) else Result.RestoreFailed(reason, p)
    }
}

object Recovery {
    const val JSON = "saving.json"
    const val BEFORE = "before.pdf"

    /** 끝나지 않은 덮어쓰기 하나(saving.json과 그 before.pdf). */
    class Pending(val dir: File, val uri: String, val title: String, val beforeSha: String, val newSha: String) {
        val before get() = File(dir, BEFORE)
    }

    /** 이 프로세스에서 지금 도는 덮어쓰기 폴더(화면이 둘일 때 다른 화면의 [list]가 건너뛴다). */
    private val active = HashSet<String>()

    fun begin(dir: File) { synchronized(active) { active.add(dir.absolutePath) } }
    fun end(dir: File) { synchronized(active) { active.remove(dir.absolutePath) } }
    private fun isActive(dir: File) = synchronized(active) { dir.absolutePath in active }

    /**
     * 다음 실행 때 볼 것. 지금 이 프로세스에서 도는 덮어쓰기 폴더는 건너뛴다(지우지도 돌려주지도 않음).
     * saving.json이 없으면 바꿔 끼우기 전 임시 파일(saving.json.tmp, fsync한 뒤 바꿔 끼우므로 읽히면 온전함)을 본다
     * (바꿔 끼우기가 디스크에 닿기 전 전원이 꺼진 경우). 둘 다 없거나 tmp가 쓰다 만 것이면 json을 쓰기 전(원본에 손대기 전)이나
     * 다 지우는 도중에 멈춘 것이라 지운다. json이 있어도 before.pdf가 없거나 해시가 다르면 쓸 수 없어 그 폴더를 남겨 두고 건너뛴다.
     */
    fun list(recRoot: File): List<Pending> {
        val out = ArrayList<Pending>()
        for (d in recRoot.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!d.isDirectory || isActive(d)) continue
            val j = File(d, JSON)
            val tmp = File(d, JSON + ".tmp")
            val text = when {
                j.exists() -> try { j.readText() } catch (e: Exception) { continue }
                tmp.exists() -> try { tmp.readText().also { JSONObject(it) } } catch (e: Exception) { null }
                else -> null
            }
            if (text == null) { d.deleteRecursively(); continue }
            try {
                val o = JSONObject(text)
                val p = Pending(d, o.getString("uri"), o.optString("title"), o.getString("before"), o.getString("new"))
                if (p.before.exists() && Saver.sha256(p.before) == p.beforeSha) out.add(p)
            } catch (e: Exception) {
                // 읽을 수 없는 표시 파일은 건드리지 않는다
            }
        }
        return out
    }

    /** 표시 파일(saving.json 또는 그 tmp)이 아직 있는지. 다른 화면이 이미 복구·정리했으면 거짓. */
    fun isOpen(p: Pending) = File(p.dir, JSON).exists() || File(p.dir, JSON + ".tmp").exists()

    /**
     * 같은 원본 잠금 안에서 기록이 아직 남았는지 다시 보고, 원본이 덮기 전 판·새 판이면 정리한다.
     * 이미 다른 쪽이 처리했으면 null(물을 것 없음).
     */
    fun settle(p: Pending, target: SaveTarget): State? = UriLock.with(p.uri) {
        if (!isOpen(p)) return@with null
        state(p, target).also { if (it == State.BEFORE || it == State.NEW) finish(p) }
    }

    /** 원본이 지금 어느 판인지: 덮기 전 판·새 판(다 쓴 뒤)·그 밖·읽을 수 없음. */
    enum class State { BEFORE, NEW, OTHER, UNREADABLE }

    fun state(p: Pending, target: SaveTarget): State {
        val sha = try { target.openRead().use { Saver.sha256(it) } } catch (e: Exception) { return State.UNREADABLE }
        return when (sha) {
            p.beforeSha -> State.BEFORE
            p.newSha -> State.NEW
            else -> State.OTHER
        }
    }

    /** 원본이 지금 덮기 전 판과 같은지(같으면 물을 필요 없음). 읽을 수 없으면 null. */
    fun originalIsBefore(p: Pending, target: SaveTarget): Boolean? = when (state(p, target)) {
        State.UNREADABLE -> null
        State.BEFORE -> true
        else -> false
    }

    /**
     * before.pdf를 원본에 되써 넣고 해시로 확인한다. 성공하면 json 다음 before 순서로 지운다.
     * before.pdf를 먼저 열고 해시를 확인한 뒤에야 원본을 연다("wt"가 원본을 비우므로, before가 없거나 다르면 원본에 손대지 않는다).
     * 같은 원본 잠금 안에서 하고, 그사이 다른 화면이 이미 처리한 기록이면 원본에 손대지 않고 거짓([isOpen]으로 가린다).
     */
    fun restore(p: Pending, target: SaveTarget): Boolean = UriLock.with(p.uri) {
        if (!isOpen(p)) return@with false
        val ok = try {
            p.before.inputStream().use { i ->
                if (Saver.sha256(p.before) != p.beforeSha) false
                else {
                    target.openWrite().use { o -> Saver.copy(i, o) }
                    target.openRead().use { Saver.sha256(it) } == p.beforeSha
                }
            }
        } catch (e: Exception) {
            false
        }
        if (ok) finish(p)
        ok
    }

    /** before.pdf를 다른 곳에 저장(확인까지). 성공하면 표시를 지운다. */
    fun saveCopy(p: Pending, target: SaveTarget) = UriLock.with(p.uri) {
        Saver.copyTo(p.before, target)
        finish(p)
    }

    /** 원본이 멀쩡해 물을 필요가 없을 때 정리. */
    fun finish(p: Pending) = UriLock.with(p.uri) {
        File(p.dir, JSON).delete()
        p.before.delete()
        p.dir.deleteRecursively()
    }

    /** [f]에 쓰고 fsync한 뒤(폴더도) SHA-256을 돌려준다. */
    fun writeFile(f: File, body: (OutputStream) -> Unit): String {
        FileOutputStream(f).use { body(it); it.fd.sync() }
        f.parentFile?.let { syncDir(it) }
        return Saver.sha256(f)
    }

    /** 표시 파일: 임시 파일에 쓰고 fsync한 뒤 바꿔 끼우고, 바꿔 끼운 것이 전원이 꺼져도 남게 폴더도 fsync한다. */
    fun writeJson(f: File, o: JSONObject) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(o.toString().toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) throw DocOpError("표시 파일을 쓰지 못했습니다.")
        f.parentFile?.let { syncDir(it) }
    }

    /** 폴더 fsync(새 파일·이름 바꾸기를 디스크에 남김). 못 하는 파일 시스템이면 false(그때는 [list]의 tmp 보기가 받친다). */
    fun syncDir(d: File): Boolean = try {
        val fd = android.system.Os.open(d.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd); true } finally { android.system.Os.close(fd) }
    } catch (e: Exception) {
        false
    }
}
