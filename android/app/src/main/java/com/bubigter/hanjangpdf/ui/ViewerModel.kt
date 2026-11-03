package com.bubigter.hanjangpdf.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bubigter.hanjangpdf.R
import com.bubigter.hanjangpdf.core.DocOpError
import com.bubigter.hanjangpdf.core.EditSession
import com.bubigter.hanjangpdf.core.Hit
import com.bubigter.hanjangpdf.core.NoSpaceError
import com.bubigter.hanjangpdf.core.Overwrite
import com.bubigter.hanjangpdf.core.PageOps
import com.bubigter.hanjangpdf.core.Recovery
import com.bubigter.hanjangpdf.core.Saver
import com.bubigter.hanjangpdf.core.ShareStore
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
    /** 열기·찾기가 따로 켜고 끈다(한쪽이 끝나며 다른 쪽 표시를 지우지 않게). 꺼질 때 미뤄 둔 열기를 연다([afterBusy]). */
    private var openingState by mutableStateOf(false)
    private var searchingState by mutableStateOf(false)
    private var workingState by mutableStateOf(false)
    private var opening: Boolean
        get() = openingState
        set(v) { openingState = v; if (!v) afterBusy() }
    private var searching: Boolean
        get() = searchingState
        set(v) { searchingState = v; if (!v) afterBusy() }
    /** 쪽 작업·저장·공유가 도는 중. */
    private var working: Boolean
        get() = workingState
        set(v) { workingState = v; if (!v) afterBusy() }
    /** 도는 중에 다른 앱의 '열기·공유'로 들어온 문서(끝나면 연다, 마지막 것 하나만). */
    internal var queuedOpen: Pair<Uri, Int>? = null
        private set
    /** 열기·찾기·편집·저장이 도는 중(화면이 도는 표시를 띄우고, 그동안 새 열기·편집은 받지 않는다). */
    val busy get() = opening || searching || working
    /** 문서 편집·저장·공유가 도는 중(찾기·열기는 아님). 쓰는 칸·메모 입력 잠금은 이때만([AnnotTools.inputLocked]). */
    internal val editing get() = working
    /** 암호를 묻는 중이면 그 문서, [pwWrong]은 방금 틀렸는지. */
    var pwDoc by mutableStateOf<ViewDoc?>(null)
        private set
    var pwWrong by mutableStateOf(false)
        private set
    /** 한 번 띄울 알림 글(띄운 뒤 [consumeMessage]). */
    var message by mutableStateOf<String?>(null)
        private set
    /** 원본 URI와 영구 권한 유지 여부. */
    var sourceUri: Uri? = null
        private set
    var persisted = false
        private set
    /** 연 문서가 암호 PDF였는지(저장 파일에는 암호가 없다고 알린다). */
    var wasEncrypted = false
        private set

    // ---- 8-3 편집·저장 ----
    /** 지금 문서의 되돌리기·실패 복구. MuPDF 실행기에서만 다룬다. */
    internal var session: EditSession? = null
        private set
    /** 문서 판이 바뀔 때마다 오른다(화면·썸네일을 새로 그림). */
    var gen by mutableIntStateOf(0)
        private set
    var dirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** 되돌리기가 한 단계뿐인 큰 문서. */
    var singleStep by mutableStateOf(false)
        private set
    /** 쪽 목록에서 고른 쪽·편집 뒤 보던 쪽(화면이 옮긴 뒤 null로). */
    var jumpPage by mutableStateOf<Int?>(null)
    /** 스크롤이 멈춰 제목 줄을 접었는지(가로로 읽을 때 보이는 양을 늘림, 화면을 한 번 누르면 다시 펼침). */
    var barHidden by mutableStateOf(false)
    /** 저장 안 한 편집이 있는데 다른 문서를 열려고 할 때 묻는다. */
    var pendingOpen by mutableStateOf<Pair<Uri, Int>?>(null)
        private set
    /** 넣을 PDF에 암호가 필요할 때(그 파일, 넣을 자리). */
    var insertPw by mutableStateOf<Pair<File, Int>?>(null)
        private set
    /** 다음 실행 복구 물음 또는 되써 넣기 실패 안내. [recoveryReadable]이 거짓이면 '원본 복구'를 못 한다. */
    var recovery by mutableStateOf<Recovery.Pending?>(null)
        private set
    var recoveryReadable by mutableStateOf(true)
        private set
    /** 되써 넣기도 실패해 before.pdf를 다른 이름으로 저장하라고 안내하는 중. */
    var restoreFailed by mutableStateOf(false)
        private set
    /** 화면이 띄울 공유 Intent(띄운 뒤 null로). */
    var shareIntent by mutableStateOf<Intent?>(null)
    /** 화면이 마지막으로 만든 공유 사본(시험이 확인). */
    internal var lastShared: File? = null
        private set
    private var verified: File? = null
    private var verifiedVersion = -1L

    // 썸네일(LRU 32MB). 열쇠는 (판, 쪽)이라 쪽 작업 뒤 옛 그림이 섞이지 않는다.
    private val thumbs = object : LruCache<Long, Bitmap>(THUMB_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    /** 썸네일이 새로 들어오면 오른다(화면이 다시 읽음). */
    var thumbTick by mutableIntStateOf(0)
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
    /** 덮어쓰기 복구 표시(saving.json·before.pdf). 작업 폴더 정리와 따로 두어 문서를 바꿔도 지우지 않는다. */
    internal val recRoot get() = File(getApplication<Application>().filesDir, "recovery")
    private val undoRoot get() = File(getApplication<Application>().cacheDir, "undo")
    internal var workDir: File? = null
        private set
    private val cr get() = getApplication<Application>().contentResolver

    /**
     * '원본 되돌리기' 직전에 따로 보관한 그때의 원본(물을 것 하나).
     * init보다 위에 둔다: 복구 폴더가 비면 checkRecovery가 멈추지 않고 생성자 안에서 끝까지 돌아 이 값을 쓴다.
     */
    var kept by mutableStateOf<Recovery.Kept?>(null)
        private set

    init {
        // 끝나지 않은 덮어쓰기가 있으면 묻는다(공유 사본·지난 이력 정리는 앱 시작 HanjangApp에서).
        viewModelScope.launch { checkRecovery() }
    }

    private fun str(id: Int) = getApplication<Application>().getString(id)

    fun consumeMessage() { message = null }

    /** 알림 글 하나(주석 도구가 씀). */
    internal fun say(text: String) { message = text }

    /** 주석 도구 5종·글자 고르기 상태. */
    val tools = AnnotTools(this)

    /** 쪽 보기 배율·스크롤(편집 뒤 화면을 다시 만들어도 보던 자리 그대로). 문서를 바꾸면 처음으로. */
    internal val viewPos = floatArrayOf(1f, 0f, 0f)

    fun notify(id: Int) { message = str(id) }

    /** 다른 앱의 '열기'(ACTION_VIEW)·'공유'(ACTION_SEND)로 들어온 PDF. */
    fun openIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) openUri(uri, intent?.flags ?: 0)
    }

    fun openUri(uri: Uri, grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                force: Boolean = false) {
        if (busy) {
            // 도는 일이 끝나면 연다(조용히 버리지 않음). 저장 안 한 편집이 있으면 그때 묻는다.
            queuedOpen = uri to grantFlags
            message = str(R.string.open_queued)
            return
        }
        // 저장 안 한 편집이나 확인하지 않은 쓰는 칸·메모 글이 있으면 먼저 묻는다(버리고 열기 / 취소).
        // 메뉴 열기·다른 앱 열기(onCreate·onNewIntent)·미뤄 둔 열기([afterBusy]) 모두 여기를 지난다.
        if (!force && (dirty || tools.unsaved)) { pendingOpen = uri to grantFlags; return }
        pendingOpen = null
        opening = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            // 화면이 둘이어도 같은 밀리초에 같은 이름이 나오지 않게 프로세스 안 번호를 붙인다
            val dir = File(workRoot, "d" + System.currentTimeMillis() + "_" + dirSeq.incrementAndGet())
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
                    wasEncrypted = true
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

    /** 도는 일이 다 끝났으면 미뤄 둔 열기를 시작한다. */
    private fun afterBusy() {
        if (busy) return
        val q = queuedOpen ?: return
        queuedOpen = null
        openUri(q.first, q.second)
    }

    /** 저장 안 한 편집을 버리고 미뤄 둔 문서를 연다. */
    fun confirmOpen() {
        val (uri, flags) = pendingOpen ?: return
        pendingOpen = null
        openUri(uri, flags, force = true)
    }

    fun cancelOpen() { pendingOpen = null }

    /**
     * 새 문서로 바꾸기 전에 지난 문서를 닫고 이 화면의 지난 작업 폴더·되돌리기 이력만 지운다.
     * work/·undo/는 앱 전체가 같이 쓰므로(화면이 둘일 수 있음) 다른 화면의 폴더는 건드리지 않는다.
     * 지난 실행이 남긴 폴더는 앱 시작 때 [com.bubigter.hanjangpdf.HanjangApp.startupCleaner]가 지운다.
     * 덮어쓰기 복구 표시는 work/ 밖(recovery/)에 있어 여기서 지우지 않는다.
     */
    private suspend fun swapIn(dir: File, uri: Uri, name: String, grantFlags: Int) {
        val old = doc ?: pwDoc
        val oldSession = session
        val oldDir = workDir
        doc = null
        pwDoc = null
        session = null
        clearSearch()
        tools.reset()
        // 끼워 넣을 PDF 암호를 묻던 중이면 닫는다(그 파일은 지난 작업 폴더에 있고, 새 문서에 넣지 않게. 검토 8-4 판 3 MEDIUM과 같은 꼴)
        insertPw = null
        insertPwWrong = false
        viewPos[0] = 1f; viewPos[1] = 0f; viewPos[2] = 0f
        withContext(Dispatchers.Main) { clearTiles(); thumbs.evictAll() }
        withContext(mupdf) { oldSession?.dropHistory(); old?.close() }
        if (oldDir != null && oldDir != dir) withContext(Dispatchers.IO) { dropDirs(oldDir) }
        verified = null
        verifiedVersion = -1L
        wasEncrypted = false
        syncState()
        workDir = dir
        sourceUri = uri
        title = name
        persisted = takePersist(uri, grantFlags)
    }

    /** 이 화면의 작업 폴더와 그 되돌리기 이력 폴더. */
    private fun dropDirs(dir: File) {
        dir.deleteRecursively()
        File(undoRoot, dir.name).deleteRecursively()
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
        val dir = workDir
        if (dir != null) {
            val orig = File(dir, "orig.pdf")
            session = EditSession(d, dir, File(undoRoot, dir.name), orig.length())
        }
        doc = d
        gen++
        syncState()
        if (d.pageCount == 0) message = str(R.string.err_broken)
    }

    /** 편집 상태를 화면 상태로 옮긴다(메인 스레드). */
    private fun syncState() {
        val s = session
        dirty = s?.dirty ?: false
        canUndo = s?.canUndo ?: false
        canRedo = s?.canRedo ?: false
        singleStep = s?.singleStep ?: false
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
        val g = gen
        val bmp = withContext(mupdf) { if (d.closed) null else draw(d) } ?: return
        // 그리는 사이 쪽 작업으로 판이 바뀌었으면 버린다(같은 열쇠에 옛 쪽 그림이 남지 않게).
        if (doc === d && !d.closed && gen == g) put(k, bmp)
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
        val s = session
        val dir = workDir
        doc = null
        pwDoc = null
        session = null
        workDir = null
        clearTiles()
        thumbs.evictAll()
        // viewModelScope는 이미 취소됐으므로 실행기에 바로 넣고 닫는다. 이 화면의 작업 폴더도 닫은 뒤 지운다.
        exec.execute { s?.dropHistory(); d?.close(); dir?.let { dropDirs(it) } }
        exec.shutdown()
    }

    // ---- 썸네일 ----

    /** 그려 둔 썸네일(없으면 null, [loadThumb]로 그린다). */
    fun thumb(page: Int): Bitmap? = thumbs.get(thumbKey(gen, page))

    private fun thumbKey(g: Int, page: Int) = (g.toLong() shl 32) or page.toLong()

    suspend fun loadThumb(page: Int) {
        val d = doc ?: return
        val g = gen
        if (thumbs.get(thumbKey(g, page)) != null) return
        val bmp = withContext(mupdf) {
            if (d.closed || gen != g || page >= d.pageCount) null else {
                val s = d.sizes[page]
                d.renderPage(page, THUMB_PX / maxOf(s.w, s.h, 1f), (THUMB_PX * THUMB_PX).toLong())
            }
        } ?: return
        if (doc === d && gen == g) { thumbs.put(thumbKey(g, page), bmp); thumbTick++ }
    }

    // ---- 쪽 작업 ----

    /** 편집 하나를 MuPDF 실행기에서 돌린다. 실패하면 [EditSession]이 편집 전 판으로 돌려놓고 알린다. */
    private fun runEdit(focus: (Int) -> Int?, op: (com.artifex.mupdf.fitz.PDFDocument) -> Int, measure: Boolean = false,
                        annot: Boolean = false, done: (Int) -> Unit = {}): Boolean {
        val s = session ?: return false
        if (busy) return false
        // 쪽 작업은 쓰는 칸·메모 창이 떠 있으면 하지 않는다(주석 편집은 그 칸을 확인하는 일이라 [runAnnotEdit]만 지남)
        if (!annot && inputOpen()) return false
        working = true
        viewModelScope.launch {
            val r = withContext(mupdf) {
                try { Result.success(s.edit(measure, op)) } catch (e: Exception) { Result.failure(e) }
            }
            if (session === s) {
                clearTiles()
                gen++
                syncState()
                r.onSuccess { v -> jumpPage = focus(v); done(v) }
                r.onFailure { e -> message = editError(e) }
            }
            working = false
        }
        return true
    }

    /**
     * 주석 편집 하나(8-3 EditSession으로: 실패하면 편집 전 판, 되돌리기 이력 하나). 보던 자리는 그대로 둔다.
     * 성공하면 [onOk] 뒤에 [okFn] 또는 [okMsg]를 알린다.
     */
    internal fun runAnnotEdit(okMsg: String?, okFn: ((Int) -> String)? = null, onOk: (Int) -> Unit = {},
                              op: (com.artifex.mupdf.fitz.PDFDocument) -> Int) {
        runEdit({ null }, op, annot = true) { v ->
            onOk(v)
            (okFn?.invoke(v) ?: okMsg)?.let { message = it }
        }
    }

    /**
     * 글자 상자 쓰는 칸이나 메모 창이 떠 있으면 알리고 참(검토 8-4 판 2 HIGH와 같은 꼴).
     * 그동안 문서 되돌리기·쪽 작업·저장·공유를 하지 않는다: 칸이 가리키는 상자가 바뀌거나, 확인 안 한 글이 빠진 판이 저장된다.
     */
    private fun inputOpen(): Boolean {
        if (tools.editor == null && tools.note == null) return false
        message = str(R.string.err_editor_open)
        return true
    }

    /** core의 한국어 알림 글을 화면 언어로([CoreText]). */
    internal fun core(msg: String?): String = CoreText.localize(getApplication(), msg ?: "")

    private fun editError(e: Throwable): String = when (e) {
        is NoSpaceError -> str(R.string.err_space)
        is DocOpError -> if (e.message == com.bubigter.hanjangpdf.core.Annots.TOO_LONG) str(R.string.err_too_long)
            else getApplication<Application>().getString(R.string.err_edit, core(e.message))
        else -> getApplication<Application>().getString(R.string.err_edit, e.toString())
    }

    fun rotatePages(pages: Collection<Int>, degrees: Int) {
        val first = pages.minOrNull() ?: return
        runEdit({ first }, { d -> PageOps.rotate(d, pages, degrees); 0 })
    }

    fun deletePages(pages: Collection<Int>, done: () -> Unit = {}) {
        val first = pages.minOrNull() ?: return
        runEdit({ maxOf(0, first - 1) }, { d -> PageOps.delete(d, pages) }) { done() }
    }

    /**
     * 고른 쪽을 원래 번호 기준 [to] 쪽 앞으로. 아무것도 안 바뀌면 하지 않는다.
     * 옮기기를 시작했으면 참(처리 중·입력 창·제자리라 아무것도 안 했으면 거짓, 8-5 판 1 LOW).
     */
    fun movePages(pages: Collection<Int>, to: Int, done: (Int) -> Unit = {}): Boolean {
        val d = doc ?: return false
        if (pages.isEmpty() || PageOps.isNoopMove(d.pageCount, pages, to)) return false
        return runEdit({ it }, { pdf -> PageOps.move(pdf, pages, to) }, done = done)
    }

    fun undo() = history(true)
    fun redo() = history(false)

    private fun history(back: Boolean) {
        val s = session ?: return
        if (busy || inputOpen()) return
        working = true
        viewModelScope.launch {
            val err = withContext(mupdf) {
                try { if (back) s.undo() else s.redo(); null } catch (e: Exception) { e }
            }
            if (session === s) {
                clearTiles()
                gen++
                syncState()
                if (err != null) message = editError(err)
            }
            working = false
        }
    }

    /** 다른 PDF를 [at](그 쪽 앞)에 끼워 넣는다. 먼저 앱 폴더로 복사하고, 암호가 있으면 묻는다. */
    fun insertFrom(uri: Uri, at: Int) {
        val dir = workDir ?: return
        val s = session ?: return
        if (busy || inputOpen()) return
        working = true
        viewModelScope.launch {
            val f = File(dir, "insert.pdf")
            val ok = withContext(Dispatchers.IO) {
                try { cr.openInputStream(uri)?.use { Intake.copy(it, f) } != null } catch (e: Exception) { false }
            }
            working = false
            if (!ok) { message = str(R.string.err_read); return@launch }
            // 열어 보기: 암호가 걸렸으면 묻고, 깨졌으면 알린다(쪽 작업은 시작하지 않음).
            val probe = withContext(mupdf) {
                when (val r = Intake.open(f.path)) {
                    is OpenResult.Ok -> { r.doc.close(); "ok" }
                    is OpenResult.NeedsPassword -> { r.doc.close(); "locked" }
                    is OpenResult.Broken -> "broken"
                }
            }
            // 열어 보는 사이(도는 표시가 꺼진 동안) 다른 문서로 바뀌었으면 넣지 않는다(검토 8-4 판 3 MEDIUM과 같은 꼴)
            if (session !== s) { f.delete(); return@launch }
            when (probe) {
                "ok" -> doInsert(f, at, null)
                "locked" -> { insertPwWrong = false; insertPw = f to at }
                else -> { f.delete(); message = str(R.string.err_broken) }
            }
        }
    }

    var insertPwWrong by mutableStateOf(false)
        private set

    /** 넣을 PDF의 암호. 틀리면 다시 묻는다(편집은 맞을 때만 시작). */
    fun submitInsertPassword(pw: String) {
        val (f, at) = insertPw ?: return
        viewModelScope.launch {
            val ok = withContext(mupdf) {
                try { PageOps.openSource(f.path, pw).destroy(); true } catch (e: DocOpError) { false }
            }
            if (insertPw?.first !== f) return@launch
            if (ok) { insertPw = null; insertPwWrong = false; doInsert(f, at, pw) } else insertPwWrong = true
        }
    }

    fun cancelInsertPassword() {
        insertPw?.first?.delete()
        insertPw = null
        insertPwWrong = false
    }

    private fun doInsert(f: File, at: Int, pw: String?) {
        runEdit({ at }, { d ->
            val src = PageOps.openSource(f.path, pw)
            try { PageOps.insertPdf(d, src, at) } finally { src.destroy() }
        }, measure = true) { f.delete() }
    }

    // ---- 저장·공유 ----

    /** ①② 지금 판을 검증된 파일로(같은 판이면 다시 만들지 않음). MuPDF 실행기에서 부른다. */
    private fun verifiedFile(s: EditSession): File {
        val dir = workDir ?: throw DocOpError("작업 폴더가 없습니다.")
        s.checkUsable() // 되돌리지 못해 반쯤 고친 문서는 저장·공유하지 않는다
        val v = verified
        if (v != null && v.exists() && verifiedVersion == s.version) return v
        val out = File(dir, "saved.pdf")
        Saver.saveVerified(s.view.pdf, out)
        verified = out
        verifiedVersion = s.version
        return out
    }

    /** 원본에 덮어쓸 수 있는지(쓰기 권한이 있을 때만). */
    val canOverwrite: Boolean
        get() {
            val u = sourceUri ?: return false
            val app = getApplication<Application>()
            return app.checkUriPermission(u, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 저장 성공 뒤 공통 알림(암호 PDF였으면 암호 없음 안내). */
    private fun savedMessage(id: Int) = getApplication<Application>().getString(id) +
        (if (wasEncrypted) "\n" + str(R.string.saved_no_password) else "")

    /**
     * ③ 다른 이름으로 저장(SAF CreateDocument가 준 [uri]). 성공하면 그 파일이 지금 문서의 원본이 된다.
     * 고른 파일에 이미 내용이 있거나(고르기 창에서 있는 파일을 고름) 지금 원본이면 그냥 "wt"로 쓰지 않고
     * 원본 덮어쓰기와 같은 경로([Overwrite.run]: before.pdf·saving.json·되써 넣기)로 쓴다([Saver.saveTo]).
     * 고른 파일을 읽을 수 없으면 쓰지 않는다.
     */
    fun saveAs(uri: Uri, done: (Boolean) -> Unit = {}) {
        val s = session ?: return
        if (busy || inputOpen()) return
        working = true
        viewModelScope.launch {
            val r = runCatching {
                val (file, ver) = withContext(mupdf) { verifiedFile(s) to s.version }
                val t = UriTarget(cr, uri)
                val res = withContext(Dispatchers.IO) { Saver.saveTo(file, t, recRoot, displayName(uri), uri == sourceUri) }
                res to ver
            }
            var ok = false
            if (session === s) r.onSuccess { (x, ver) ->
                ok = x is Overwrite.Result.Ok
                if (ok) {
                    withContext(mupdf) { if (s.version == ver) s.markSaved() }
                    sourceUri = uri
                    persisted = takePersist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    title = withContext(Dispatchers.IO) { displayName(uri) }
                    message = savedMessage(R.string.saved)
                } else overwriteFailed(x, uri != sourceUri)
            }.onFailure { e -> message = editError(e); checkRecovery() }
            syncState()
            working = false
            done(ok)
        }
    }

    /**
     * 덮어쓰기가 Ok가 아닐 때 알림·복구 물음(원본 덮어쓰기·있는 파일에 다른 이름으로 저장 공통).
     * [picked]면 사용자가 고른 파일(원본이 아님)이라 알림 글을 '고른 파일'로 쓴다.
     */
    private fun overwriteFailed(x: Overwrite.Result, picked: Boolean = false) {
        val app = getApplication<Application>()
        when (x) {
            is Overwrite.Result.Ok -> {}
            is Overwrite.Result.Restored ->
                message = app.getString(if (picked) R.string.err_pick_restored else R.string.err_over_restored, core(x.reason))
            is Overwrite.Result.Untouched ->
                message = app.getString(if (picked) R.string.err_pick_untouched else R.string.err_over_untouched, core(x.reason))
            is Overwrite.Result.RestoreFailed -> {
                recovery = x.pending
                recoveryReadable = false
                restoreFailed = true
            }
            is Overwrite.Result.Unresolved -> {
                // 같은 원본의 끝나지 않은 덮기 기록을 먼저 처리하게 묻는다(원본은 그대로)
                message = str(R.string.err_over_pending)
                recovery = x.pending
                recoveryReadable = x.readable
                restoreFailed = false
            }
        }
    }

    /** ④ 원본에 덮어쓰기. 실패하면 원본을 덮기 전 판으로 되돌리고, 그것도 안 되면 복구본 저장을 안내한다. */
    fun overwrite(done: (Overwrite.Result?) -> Unit = {}) {
        val s = session ?: return
        val u = sourceUri ?: return
        if (busy || !canOverwrite || inputOpen()) return
        working = true
        viewModelScope.launch {
            val r = runCatching {
                val (file, ver) = withContext(mupdf) { verifiedFile(s) to s.version }
                val res = withContext(Dispatchers.IO) { Overwrite.run(UriTarget(cr, u), file, recRoot, title) }
                res to ver
            }
            var res: Overwrite.Result? = null
            r.onSuccess { (x, ver) ->
                res = x
                if (x is Overwrite.Result.Ok) {
                    withContext(mupdf) { if (s.version == ver) s.markSaved() }
                    message = savedMessage(R.string.saved_over)
                } else overwriteFailed(x)
            }.onFailure { e ->
                message = editError(e)
                // Error(메모리 부족 등)로 덮기가 도중에 멈췄으면 표시가 남아 있을 수 있어 바로 묻는다
                checkRecovery()
            }
            syncState()
            working = false
            done(res)
        }
    }

    /** 공유: 검증된 저장 파일의 사본을 share/에 두고 FileProvider 주소를 읽기 권한 한 번으로 넘긴다. */
    fun share(done: (Intent?) -> Unit = {}) {
        val s = session ?: return
        if (busy || inputOpen()) return
        working = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val r = runCatching {
                val file = withContext(mupdf) { verifiedFile(s) }
                withContext(Dispatchers.IO) { ShareStore.put(app.cacheDir, file, title) }
            }
            var intent: Intent? = null
            r.onSuccess { f ->
                lastShared = f
                val uri = FileProvider.getUriForFile(app, app.packageName + ".share", f)
                intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri(f.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                shareIntent = intent
            }.onFailure { e -> message = editError(e) }
            working = false
            done(intent)
        }
    }

    /** 고른 쪽을 새 PDF로 내보내기(SAF CreateDocument가 준 [uri]). 지금 문서는 바뀌지 않는다. 내용 있는 파일이면 덮기 보호 경로. */
    fun exportPages(pages: Collection<Int>, uri: Uri, done: (Boolean) -> Unit = {}) {
        val d = doc ?: return
        val dir = workDir ?: return
        if (busy || inputOpen()) return
        working = true
        viewModelScope.launch {
            val out = File(dir, "export.pdf")
            val r = runCatching {
                withContext(mupdf) { PageOps.exportPages(d.pdf, pages, out.path) }
                withContext(Dispatchers.IO) { Saver.saveTo(out, UriTarget(cr, uri), recRoot, displayName(uri)) }
            }
            withContext(Dispatchers.IO) { out.delete() }
            r.onSuccess { x -> if (x is Overwrite.Result.Ok) message = str(R.string.exported) else overwriteFailed(x, true) }
                .onFailure { e -> message = editError(e); checkRecovery() }
            working = false
            done(r.getOrNull() is Overwrite.Result.Ok)
        }
    }

    // ---- 덮어쓰기 복구(다음 실행) ----

    /**
     * 끝나지 않은 덮어쓰기를 찾아, 원본이 덮기 전 판(손대기 전)이나 새 판(다 쓰고 정리 전에 멈춤)이면 조용히 정리하고
     * 다르면 묻는다. 이 프로세스에서 지금 도는 덮어쓰기는 [Recovery.list]가 건너뛴다(화면이 둘일 때).
     */
    internal suspend fun checkRecovery() {
        val found = withContext(Dispatchers.IO) {
            var ask: Pair<Recovery.Pending, Boolean>? = null
            for (p in Recovery.list(recRoot)) {
                // 같은 원본 잠금 안에서 다시 보고 정리(그사이 다른 화면이 처리했으면 null)
                when (Recovery.settle(p, UriTarget(cr, Uri.parse(p.uri)))) {
                    null, Recovery.State.BEFORE, Recovery.State.NEW -> {}
                    Recovery.State.OTHER -> if (ask == null) ask = p to true
                    Recovery.State.UNREADABLE -> if (ask == null) ask = p to false // 원본을 읽을 수 없음: 복구본 저장만
                }
            }
            ask
        }
        if (found != null) {
            recovery = found.first
            recoveryReadable = found.second
            restoreFailed = false
        }
        kept = withContext(Dispatchers.IO) { Recovery.kept(keptRoot).firstOrNull() }
    }

    /** 보관한 원본(current.pdf). recovery/ 밖에 두어 기록 정리([Recovery.list])가 지우지 않는다. */
    internal val keptRoot get() = File(getApplication<Application>().filesDir, "recovery_kept")

    /** 보관한 원본을 다른 이름으로 저장(SAF CreateDocument가 준 [uri]). */
    fun keptSaveAs(uri: Uri) {
        val k = kept ?: return
        if (busy) return // 다른 저장·복구와 겹치지 않게(검토 8-4 판 2 LOW)
        working = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Recovery.saveKept(k, UriTarget(cr, uri), recRoot) } }
            r.onSuccess { x -> if (x is Overwrite.Result.Ok) message = str(R.string.kept_saved) else overwriteFailed(x, true) }
                .onFailure { e -> message = editError(e); checkRecovery() }
            kept = withContext(Dispatchers.IO) { Recovery.kept(keptRoot).firstOrNull() }
            working = false
        }
    }

    /** 보관한 원본을 지운다. */
    fun keptDrop() {
        val k = kept ?: return
        viewModelScope.launch {
            kept = withContext(Dispatchers.IO) { Recovery.dropKept(k); Recovery.kept(keptRoot).firstOrNull() }
        }
    }

    /** 나중에 묻기(보관본은 남긴다). */
    fun keptLater() { kept = null }

    /** '원본 복구': before.pdf를 원본에 되써 넣는다. */
    fun recoverRestore() {
        val p = recovery ?: return
        if (busy) return
        working = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Recovery.restore(p, UriTarget(cr, Uri.parse(p.uri)), keptRoot) }.map { it to Recovery.isOpen(p) }
            }
            working = false
            // 지금 원본을 읽거나 보관하지 못함: 원본·기록 그대로, 물음도 그대로 두고 알린다(검토 8-4 판 3 HIGH)
            val e = r.exceptionOrNull()
            if (e is Recovery.KeepFailed) {
                message = getApplication<Application>().getString(R.string.err_keep_failed, core(e.message))
                return@launch
            }
            val (ok, open) = r.getOrDefault(false to true)
            recovery = null
            when {
                ok -> {
                    val keptNow = withContext(Dispatchers.IO) { Recovery.kept(keptRoot).isNotEmpty() }
                    message = str(if (keptNow) R.string.recovered_kept else R.string.recovered)
                    checkRecovery()
                }
                // 다른 화면이 이미 복구·정리한 기록: 원본에 손대지 않았고 더 할 일이 없다
                !open -> { restoreFailed = false; checkRecovery() }
                else -> { message = str(R.string.err_recover); recovery = p; recoveryReadable = false; restoreFailed = true }
            }
        }
    }

    /** '복구본 다른 이름으로 저장'(SAF CreateDocument가 준 [uri]). */
    fun recoverSaveAs(uri: Uri) {
        val p = recovery ?: return
        if (busy) return
        working = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Recovery.saveCopy(p, UriTarget(cr, uri), recRoot) } }
            working = false
            r.onSuccess { x ->
                if (x is Overwrite.Result.Ok) {
                    recovery = null
                    restoreFailed = false
                    message = str(R.string.recovery_saved)
                    checkRecovery()
                } else overwriteFailed(x, true) // 복구 기록은 그대로(고른 파일의 원래 내용도 지킴)
            }.onFailure { e ->
                when {
                    // 그 원본 자리를 고름: 손대지 않았고 물음도 그대로 둔다(검토 8-4 판 4 MEDIUM)
                    e is Recovery.SameSource -> message = str(R.string.err_recover_same_source)
                    withContext(Dispatchers.IO) { Recovery.isOpen(p) } -> message = editError(e)
                    // 다른 화면이 이미 처리한 기록이면(before.pdf가 없어 실패) 물음을 닫되 조용히 닫지 않는다
                    else -> { recovery = null; restoreFailed = false; message = str(R.string.recovery_gone); checkRecovery() }
                }
            }
        }
    }

    /** 나중에 묻기(표시 파일은 남긴다). */
    fun recoverLater() { recovery = null; restoreFailed = false }

    companion object {
        /** 확대 타일 합계 상한(512px ARGB 타일 48장). 지금 화면에 필요한 타일은 넘어도 남긴다. */
        const val ZOOM_TILE_BYTES = 48L shl 20
        /** 썸네일 캐시 상한(설계 7항 LRU 32MB)과 썸네일 긴 변 픽셀. */
        const val THUMB_BYTES = 32 shl 20
        const val THUMB_PX = 240f
        /** 작업 폴더 이름 번호(프로세스 안 모든 화면이 같이 씀). */
        private val dirSeq = java.util.concurrent.atomic.AtomicInteger()
    }
}
