package com.bubigter.hanjangpdf.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bubigter.hanjangpdf.R
import com.bubigter.hanjangpdf.core.Hit
import com.bubigter.hanjangpdf.core.Intake
import com.bubigter.hanjangpdf.core.OpenResult
import com.bubigter.hanjangpdf.core.SearchCursor
import com.bubigter.hanjangpdf.core.ViewDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 렌더 조각 열쇠. [zq]는 그린 배율(확대 단계), 0이면 폭 맞춤 기본 그림. */
data class TileKey(val page: Int, val zq: Float, val tx: Int, val ty: Int)

/** 화면 상태와 MuPDF 작업. MuPDF는 [mupdf] 한 줄 실행기에서만 부른다. */
class ViewerModel(app: Application) : AndroidViewModel(app) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "mupdf") }
    val mupdf = exec.asCoroutineDispatcher()

    var doc by mutableStateOf<ViewDoc?>(null)
        private set
    var title by mutableStateOf("")
        private set
    /** 열기·찾기가 따로 켜고 끈다(한쪽이 끝나며 다른 쪽 표시를 지우지 않게). */
    private var opening by mutableStateOf(false)
    private var searching by mutableStateOf(false)
    /** 열기나 찾기가 도는 중(화면이 도는 표시를 띄우고, 그동안 새 열기는 받지 않는다). */
    val busy get() = opening || searching
    /** 암호를 묻는 중이면 그 문서, [pwWrong]은 방금 틀렸는지. */
    var pwDoc by mutableStateOf<ViewDoc?>(null)
        private set
    var pwWrong by mutableStateOf(false)
        private set
    /** 한 번 띄울 알림 글(띄운 뒤 [consumeMessage]). */
    var message by mutableStateOf<String?>(null)
        private set
    /** 원본 URI(8-3 저장에서 씀)와 쓰기 권한 유지 여부. */
    var sourceUri: Uri? = null
        private set
    var persisted = false
        private set

    // 렌더 결과: 기본 그림(zq=0)과 확대 타일. 화면이 필요한 것만 남기고 지운다(설계 7항).
    // 넣고 빼기는 [render]·[dropTiles]·[clearTiles]로만 한다. 버린 비트맵은 recycle하지 않는다
    // (그리는 중일 수 있으므로 참조가 없어지면 GC가 거둔다).
    val tiles = mutableStateMapOf<TileKey, Bitmap>()
    /** [tiles]에 넣은 차례(상한을 넘으면 오래된 것부터 버린다). */
    private val order = LinkedHashSet<TileKey>()
    /** 화면이 지금 필요로 하는 열쇠(상한으로 버릴 때도 남긴다). */
    internal var wanted: Set<TileKey> = emptySet()

    var query by mutableStateOf("")
    var cursor by mutableStateOf(SearchCursor(emptyList()))
        private set
    var searchedFor by mutableStateOf<String?>(null)
        private set
    /** 찾기로 옮겨 갈 결과(화면이 스크롤한 뒤 null로). */
    var jumpTo by mutableStateOf<Hit?>(null)
    private var searchJob: Job? = null
    private var searchCancel = AtomicBoolean(false)

    private val workRoot get() = File(getApplication<Application>().filesDir, "work")
    private var workDir: File? = null

    private fun str(id: Int) = getApplication<Application>().getString(id)

    fun consumeMessage() { message = null }

    /** 다른 앱의 '열기'(ACTION_VIEW)·'공유'(ACTION_SEND)로 들어온 PDF. */
    fun openIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) openUri(uri, intent?.flags ?: 0)
    }

    fun openUri(uri: Uri, grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
        if (busy) return
        opening = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val dir = File(workRoot, "d" + System.currentTimeMillis())
            val orig = File(dir, "orig.pdf")
            val copied = withContext(Dispatchers.IO) {
                try {
                    val input = app.contentResolver.openInputStream(uri) ?: return@withContext false
                    input.use { Intake.copy(it, orig) }
                    true
                } catch (e: Exception) { // 권한 없음·읽기 실패 등
                    false
                }
            }
            if (!copied) {
                dir.deleteRecursively()
                message = str(R.string.err_read)
                opening = false
                return@launch
            }
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            val r = withContext(mupdf) { Intake.open(orig.path) }
            when (r) {
                is OpenResult.Broken -> {
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    message = str(R.string.err_broken)
                }
                is OpenResult.NeedsPassword -> {
                    swapIn(dir, uri, name, grantFlags)
                    pwWrong = false
                    pwDoc = r.doc
                }
                is OpenResult.Ok -> {
                    swapIn(dir, uri, name, grantFlags)
                    show(r.doc)
                }
            }
            opening = false
        }
    }

    /** 새 문서로 바꾸기 전에 지난 문서를 닫고 지난 작업 폴더를 지운다. */
    private suspend fun swapIn(dir: File, uri: Uri, name: String, grantFlags: Int) {
        val old = doc ?: pwDoc
        doc = null
        pwDoc = null
        clearSearch()
        withContext(Dispatchers.Main) { clearTiles() }
        withContext(mupdf) { old?.close() }
        val keep = dir.name
        withContext(Dispatchers.IO) {
            workRoot.listFiles()?.filter { it.name != keep }?.forEach { it.deleteRecursively() }
        }
        workDir = dir
        sourceUri = uri
        title = name
        persisted = takePersist(uri, grantFlags)
    }

    /** 가능할 때만 원본 URI 권한을 유지한다. 실패해도 열기는 된다(저장은 8-3에서 '다른 이름으로'). */
    private fun takePersist(uri: Uri, grantFlags: Int): Boolean {
        val cr = getApplication<Application>().contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (f in listOf(grantFlags and rw, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            if (f == 0) continue
            try {
                cr.takePersistableUriPermission(uri, f)
                return true
            } catch (e: SecurityException) {
                // 영구 권한을 주지 않는 앱(공유 등)
            }
        }
        return false
    }

    private fun displayName(uri: Uri): String {
        try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
        } catch (e: Exception) {
            // 이름을 못 얻으면 아래 기본값
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }

    private fun show(d: ViewDoc) {
        doc = d
        if (d.pageCount == 0) message = str(R.string.err_broken)
    }

    fun submitPassword(pw: String) {
        val d = pwDoc ?: return
        viewModelScope.launch {
            val r = withContext(mupdf) { if (d.closed) null else d.unlock(pw) }
            // 암호를 푸는 사이 창을 닫았거나 다른 문서를 열었으면 그 결과는 버린다(그 문서는 거기서 닫힌다).
            if (pwDoc !== d) return@launch
            when (r) {
                null -> pwWrong = true
                is OpenResult.Ok -> { pwDoc = null; show(r.doc) }
                else -> { pwDoc = null; message = str(R.string.err_broken) }
            }
        }
    }

    fun cancelPassword() {
        val d = pwDoc ?: return
        pwDoc = null
        viewModelScope.launch { withContext(mupdf) { d.close() } }
    }

    /**
     * 렌더 하나를 MuPDF 실행기에서 그려 [tiles]에 넣는다. 그리는 사이 문서가 닫히거나 바뀌었으면
     * 그 그림은 버린다(열쇠에 문서 구분이 없어 다른 문서의 같은 쪽 자리에 지난 그림이 보이지 않게).
     */
    suspend fun render(d: ViewDoc, k: TileKey, draw: (ViewDoc) -> Bitmap?) {
        val bmp = withContext(mupdf) { if (d.closed) null else draw(d) } ?: return
        if (doc === d && !d.closed) put(k, bmp)
    }

    private fun put(k: TileKey, bmp: Bitmap) {
        tiles[k] = bmp
        order.remove(k)
        order.add(k)
        if (k.zq == 0f) return
        // 확대 타일 합계가 상한을 넘으면 지금 화면에 필요 없는 것부터(오래된 차례로) 버린다.
        var bytes = tiles.entries.sumOf { if (it.key.zq != 0f) it.value.allocationByteCount.toLong() else 0L }
        val iter = order.iterator()
        while (bytes > ZOOM_TILE_BYTES && iter.hasNext()) {
            val o = iter.next()
            if (o.zq == 0f || o == k || o in wanted) continue
            iter.remove()
            tiles.remove(o)?.let { b -> bytes -= b.allocationByteCount }
        }
    }

    fun dropTiles(pred: (TileKey) -> Boolean) {
        for (k in tiles.keys.filter(pred)) {
            tiles.remove(k)
            order.remove(k)
        }
    }

    private fun clearTiles() {
        tiles.clear()
        order.clear()
        wanted = emptySet()
    }

    // ---- 찾기 ----

    fun runSearch() {
        val d = doc ?: return
        val q = query.trim()
        if (q.isEmpty()) { clearSearch(); return }
        if (q == searchedFor) { step(1); return }
        searchCancel.set(true)
        searchJob?.cancel()
        val cancel = AtomicBoolean(false)
        searchCancel = cancel
        searchJob = viewModelScope.launch {
            searching = true
            val hits = try {
                withContext(mupdf) { d.search(q) { cancel.get() } }
            } finally {
                // 찾기 막대를 닫아 취소돼도 표시를 끈다. 새 찾기가 이미 시작됐으면 그쪽이 끈다.
                if (searchCancel === cancel) searching = false
            }
            if (cancel.get() || d.closed) return@launch
            searchedFor = q
            cursor = SearchCursor(hits)
            step(1)
        }
    }

    fun step(dir: Int) {
        val c = cursor.step(dir)
        cursor = c
        jumpTo = c.current
    }

    fun clearSearch() {
        searchCancel.set(true)
        searchJob?.cancel()
        searchedFor = null
        cursor = SearchCursor(emptyList())
        jumpTo = null
    }

    override fun onCleared() {
        val d = doc ?: pwDoc
        doc = null
        pwDoc = null
        clearTiles()
        // viewModelScope는 이미 취소됐으므로 실행기에 바로 넣고 닫는다.
        exec.execute { d?.close() }
        exec.shutdown()
    }

    companion object {
        /** 확대 타일 합계 상한(512px ARGB 타일 48장). 지금 화면에 필요한 타일은 넘어도 남긴다. */
        const val ZOOM_TILE_BYTES = 48L shl 20
    }
}
