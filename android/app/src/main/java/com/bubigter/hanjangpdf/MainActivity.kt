package com.bubigter.hanjangpdf

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bubigter.hanjangpdf.ui.AboutScreen
import com.bubigter.hanjangpdf.ui.EditorPanel
import com.bubigter.hanjangpdf.ui.NoteDialog
import com.bubigter.hanjangpdf.ui.PageList
import com.bubigter.hanjangpdf.ui.ToolBar
import com.bubigter.hanjangpdf.ui.PdfView
import com.bubigter.hanjangpdf.ui.ViewerModel

class MainActivity : ComponentActivity() {
    internal val model: ViewerModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 상태 막대 글자·아이콘 색을 밝은·어두운 화면에 맞춘다(8-5: 어두운 화면에서 검은 아이콘이 안 보이던 것)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 화면 돌리기 등으로 다시 만들어질 때는 이미 연 문서를 그대로 둔다.
        if (savedInstanceState == null) model.openIntent(intent)
        setContent { HanjangTheme { Surface(Modifier.fillMaxSize()) { App(model) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openIntent(intent)
    }
}

/** 기기의 어두운 화면 설정을 따르는 색(Material 기본 색은 WCAG AA 글자 대비를 맞춘다). */
@Composable
internal fun HanjangTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
internal fun App(model: ViewerModel) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.openUri(uri)
    }
    val openPdf = { pick.launch(arrayOf("application/pdf")) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showPages by rememberSaveable { mutableStateOf(false) }
    var selected by remember(model.doc) { mutableStateOf(emptySet<Int>()) }
    var menu by remember { mutableStateOf(false) }
    var askOverwrite by remember { mutableStateOf(false) }
    var askClose by remember { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    // 저장 안 한 편집·확인 안 한 쓰는 칸·메모 글이 있으면 뒤로 가기로 닫기 전에 묻는다(검토 8-4 판 2 HIGH와 같은 꼴)
    BackHandler(enabled = model.dirty || model.tools.unsaved) { askClose = true }
    var insertAt by remember { mutableStateOf(0) }
    val pdfName = { suffix: String ->
        model.title.removeSuffix(".pdf").removeSuffix(".PDF").ifEmpty { "document" } + suffix + ".pdf"
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.saveAs(uri)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.exportPages(selected, uri)
    }
    val recoverAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.recoverSaveAs(uri)
    }
    val keptAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.keptSaveAs(uri)
    }
    val insert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.insertFrom(uri, insertAt)
    }

    LaunchedEffect(model.message) {
        model.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); model.consumeMessage() }
    }
    LaunchedEffect(model.shareIntent) {
        model.shareIntent?.let { ctx.startActivity(Intent.createChooser(it, ctx.getString(R.string.share_title))); model.shareIntent = null }
    }
    LaunchedEffect(model.singleStep) { if (model.singleStep) model.notify(R.string.single_step) }

    if (showAbout) Column(Modifier.fillMaxSize().safeDrawingPadding()) { AboutScreen { showAbout = false } }
    else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val titleText = (if (model.dirty) "• " else "") + model.title.ifEmpty { stringResource(R.string.app_name) }
        // 글꼴이 크면 단추에 밀려 문서 이름이 '…'만 남으므로 이름을 단추 줄 위에 따로 둔다(8-5)
        val titleAbove = model.doc != null && LocalDensity.current.fontScale >= 1.3f
        if (titleAbove) Text(titleText, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() }.testTag("title"),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (titleAbove) Spacer(Modifier.weight(1f))
            else Text(titleText, Modifier.weight(1f).padding(8.dp).semantics { heading() }.testTag("title"),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (model.doc != null) {
                TextButton(onClick = {
                    searching = !searching
                    if (!searching) model.clearSearch()
                }, Modifier.testTag("searchToggle")) { Text(stringResource(R.string.find)) }
                TextButton(onClick = {
                    model.tools.visible = !model.tools.visible
                    if (!model.tools.visible) { model.tools.tool = com.bubigter.hanjangpdf.ui.Tool.VIEW; model.tools.selection = null }
                }, Modifier.testTag("toolsToggle"), enabled = model.tools.editor == null) { Text(stringResource(R.string.tools)) }
                TextButton(onClick = { showPages = !showPages }, Modifier.testTag("pagesToggle"),
                    enabled = model.tools.editor == null) { Text(stringResource(R.string.pages)) }
            }
            Box {
                val menuDesc = stringResource(R.string.menu)
                TextButton(onClick = { menu = true }, Modifier.testTag("menu")) {
                    Text("⋮", Modifier.semantics { contentDescription = menuDesc })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val has = model.doc != null && !model.busy
                    // 쓰는 칸이 떠 있으면 저장·공유도 막는다(확인 안 한 글이 빠진 판이 저장되지 않게)
                    val canSave = has && model.tools.editor == null
                    DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; openPdf() },
                        modifier = Modifier.testTag("open"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, enabled = canSave,
                        onClick = { menu = false; saveAs.launch(pdfName("")) }, modifier = Modifier.testTag("saveAs"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_over)) }, enabled = canSave,
                        onClick = {
                            menu = false
                            if (model.canOverwrite) askOverwrite = true else model.notify(R.string.no_overwrite)
                        }, modifier = Modifier.testTag("saveOver"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, enabled = canSave,
                        onClick = { menu = false; model.share() }, modifier = Modifier.testTag("share"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.undo)) }, enabled = has && model.canUndo && model.tools.editor == null,
                        onClick = { menu = false; model.undo() }, modifier = Modifier.testTag("undo"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.redo)) }, enabled = has && model.canRedo && model.tools.editor == null,
                        onClick = { menu = false; model.redo() }, modifier = Modifier.testTag("redo"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { menu = false; showAbout = true },
                        modifier = Modifier.testTag("aboutOpen"))
                }
            }
        }
        if (searching && model.doc != null && !showPages) SearchBar(model)
        if (model.tools.visible && model.doc != null && !showPages) ToolBar(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = model.doc
            if (d != null && showPages) key(d, model.gen) {
                PageList(model, d, selected.filter { it < d.pageCount }.toSet(), { selected = it }, { showPages = false },
                    onExport = { export.launch(pdfName("_pages")) },
                    onInsert = { at -> insertAt = at; insert.launch(arrayOf("application/pdf")) })
            }
            else if (d != null) key(d, model.gen) { PdfView(model, d) }
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.empty_hint), Modifier.padding(16.dp))
                Button(onClick = openPdf) { Text(stringResource(R.string.open_pdf)) }
            }
            if (model.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (model.doc != null && !showPages) EditorPanel(model)
    }
    NoteDialog(model)
    if (model.pwDoc != null) PasswordDialog(model)
    model.insertPw?.let { p ->
        var pw by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { model.cancelInsertPassword() },
            title = { Text(stringResource(R.string.insert_pw_title)) },
            text = {
                Column {
                    if (model.insertPwWrong) Text(stringResource(R.string.pw_wrong), color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(pw, { pw = it }, singleLine = true, modifier = Modifier.testTag("insertPassword"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { model.submitInsertPassword(pw) }))
                }
            },
            confirmButton = { TextButton(onClick = { model.submitInsertPassword(pw) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { model.cancelInsertPassword() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (model.pendingOpen != null) AlertDialog(
        onDismissRequest = { model.cancelOpen() },
        title = { Text(stringResource(R.string.dirty_title)) },
        text = { Text(stringResource(if (model.tools.unsaved) R.string.dirty_body_input else R.string.dirty_body)) },
        confirmButton = { TextButton(onClick = { model.confirmOpen() }, Modifier.testTag("discardOpen")) { Text(stringResource(R.string.discard_open)) } },
        dismissButton = { TextButton(onClick = { model.cancelOpen() }) { Text(stringResource(R.string.cancel)) } },
    )
    if (askClose) AlertDialog(
        onDismissRequest = { askClose = false },
        title = { Text(stringResource(R.string.dirty_title)) },
        text = { Text(stringResource(R.string.close_body)) },
        confirmButton = { TextButton(onClick = { askClose = false; (ctx as? android.app.Activity)?.finish() }) { Text(stringResource(R.string.discard_close)) } },
        dismissButton = { TextButton(onClick = { askClose = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (askOverwrite) AlertDialog(
        onDismissRequest = { askOverwrite = false },
        title = { Text(stringResource(R.string.over_confirm_title)) },
        text = { Text(stringResource(R.string.over_confirm)) },
        confirmButton = { TextButton(onClick = { askOverwrite = false; model.overwrite() }, Modifier.testTag("overOk")) { Text(stringResource(R.string.save_over)) } },
        dismissButton = { TextButton(onClick = { askOverwrite = false }) { Text(stringResource(R.string.cancel)) } },
    )
    model.recovery?.let { p ->
        val failed = model.restoreFailed
        AlertDialog(
            onDismissRequest = { if (!failed) model.recoverLater() },
            title = { Text(stringResource(if (failed) R.string.restore_failed_title else R.string.recovery_title)) },
            text = {
                Text(stringResource(when {
                    failed -> R.string.restore_failed_body
                    !model.recoveryReadable -> R.string.recovery_unreadable
                    else -> R.string.recovery_body
                }) + (if (p.title.isNotEmpty()) "\n\n" + p.title else "") +
                    (if (p.size >= 0) "\n" + stringResource(R.string.size_bytes, sizeText(p.size)) else ""))
            },
            confirmButton = {
                Row {
                    if (model.recoveryReadable && !failed)
                        TextButton(onClick = { model.recoverRestore() }, Modifier.testTag("recoverRestore")) { Text(stringResource(R.string.recover_restore)) }
                    TextButton(onClick = { recoverAs.launch(p.title.ifEmpty { "recovered.pdf" }.removeSuffix(".pdf") + "_before.pdf") },
                        Modifier.testTag("recoverSaveAs")) { Text(stringResource(R.string.recover_save_as)) }
                }
            },
            dismissButton = { TextButton(onClick = { model.recoverLater() }) { Text(stringResource(R.string.later)) } },
        )
    }
    model.kept?.let { k -> if (model.recovery == null) KeptDialog(model, k) { keptAs.launch(it) } }
}

/** 바이트 수를 KB·MB로(복구 물음에 보이는 크기). */
private fun sizeText(n: Long): String = when {
    n >= 1L shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

@Composable
private fun KeptDialog(model: ViewerModel, k: com.bubigter.hanjangpdf.core.Recovery.Kept, saveAs: (String) -> Unit) {
    // '지우기'는 되돌릴 수 없으므로 한 번 더 묻는다(검토 8-4 판 1 LOW)
    var confirmDrop by remember(k.dir) { mutableStateOf(false) }
    if (confirmDrop) {
        AlertDialog(
            onDismissRequest = { confirmDrop = false },
            title = { Text(stringResource(R.string.kept_title)) },
            text = { Text(stringResource(R.string.kept_drop_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDrop = false; model.keptDrop() }, Modifier.testTag("keptDropYes")) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDrop = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { model.keptLater() },
        title = { Text(stringResource(R.string.kept_title)) },
        text = {
            Text(stringResource(R.string.kept_body) + (if (k.title.isNotEmpty()) "\n\n" + k.title else "") +
                (if (k.size >= 0) "\n" + stringResource(R.string.size_bytes, sizeText(k.size)) else ""))
        },
        confirmButton = {
            Row {
                TextButton(onClick = { saveAs(k.title.ifEmpty { "kept.pdf" }.removeSuffix(".pdf") + "_kept.pdf") },
                    Modifier.testTag("keptSaveAs")) { Text(stringResource(R.string.kept_save_as)) }
                TextButton(onClick = { confirmDrop = true }, Modifier.testTag("keptDrop")) { Text(stringResource(R.string.delete)) }
            }
        },
        dismissButton = { TextButton(onClick = { model.keptLater() }) { Text(stringResource(R.string.later)) } },
    )
}

@Composable
private fun SearchBar(model: ViewerModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = model.query, onValueChange = { model.query = it },
            modifier = Modifier.weight(1f).testTag("query"), singleLine = true,
            placeholder = { Text(stringResource(R.string.find_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model.runSearch() }),
        )
        val label = when {
            model.searchedFor == null -> ""
            else -> model.cursor.label() ?: stringResource(R.string.find_none)
        }
        Text(label, Modifier.padding(horizontal = 8.dp).testTag("hitCount"))
        TextButton(onClick = { model.step(-1) }, enabled = model.cursor.hits.isNotEmpty()) { Text(stringResource(R.string.find_prev)) }
        TextButton(onClick = {
            if (model.searchedFor != model.query.trim()) model.runSearch() else model.step(1)
        }, Modifier.testTag("next")) { Text(stringResource(R.string.find_next)) }
    }
}

@Composable
private fun PasswordDialog(model: ViewerModel) {
    var pw by remember(model.pwDoc) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { model.cancelPassword() },
        title = { Text(stringResource(R.string.pw_title)) },
        text = {
            Column {
                if (model.pwWrong) Text(stringResource(R.string.pw_wrong), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(pw, { pw = it }, singleLine = true, modifier = Modifier.testTag("password"),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.submitPassword(pw) }))
            }
        },
        confirmButton = { TextButton(onClick = { model.submitPassword(pw) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { model.cancelPassword() }) { Text(stringResource(R.string.cancel)) } },
    )
}
