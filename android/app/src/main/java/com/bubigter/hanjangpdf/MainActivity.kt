package com.bubigter.hanjangpdf

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bubigter.hanjangpdf.ui.PageList
import com.bubigter.hanjangpdf.ui.PdfView
import com.bubigter.hanjangpdf.ui.ViewerModel

class MainActivity : ComponentActivity() {
    internal val model: ViewerModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면 돌리기 등으로 다시 만들어질 때는 이미 연 문서를 그대로 둔다.
        if (savedInstanceState == null) model.openIntent(intent)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(model) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openIntent(intent)
    }
}

@Composable
private fun App(model: ViewerModel) {
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

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (model.dirty) "• " else "") + model.title.ifEmpty { stringResource(R.string.app_name) },
                Modifier.weight(1f).padding(8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (model.doc != null) {
                TextButton(onClick = {
                    searching = !searching
                    if (!searching) model.clearSearch()
                }, Modifier.testTag("searchToggle")) { Text(stringResource(R.string.find)) }
                TextButton(onClick = { showPages = !showPages }, Modifier.testTag("pagesToggle")) { Text(stringResource(R.string.pages)) }
            }
            Box {
                TextButton(onClick = { menu = true }, Modifier.testTag("menu")) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val has = model.doc != null && !model.busy
                    DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; openPdf() },
                        modifier = Modifier.testTag("open"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, enabled = has,
                        onClick = { menu = false; saveAs.launch(pdfName("")) }, modifier = Modifier.testTag("saveAs"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_over)) }, enabled = has,
                        onClick = {
                            menu = false
                            if (model.canOverwrite) askOverwrite = true else model.notify(R.string.no_overwrite)
                        }, modifier = Modifier.testTag("saveOver"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, enabled = has,
                        onClick = { menu = false; model.share() }, modifier = Modifier.testTag("share"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.undo)) }, enabled = has && model.canUndo,
                        onClick = { menu = false; model.undo() }, modifier = Modifier.testTag("undo"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.redo)) }, enabled = has && model.canRedo,
                        onClick = { menu = false; model.redo() }, modifier = Modifier.testTag("redo"))
                }
            }
        }
        if (searching && model.doc != null && !showPages) SearchBar(model)
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
    }
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
        text = { Text(stringResource(R.string.dirty_body)) },
        confirmButton = { TextButton(onClick = { model.confirmOpen() }, Modifier.testTag("discardOpen")) { Text(stringResource(R.string.discard_open)) } },
        dismissButton = { TextButton(onClick = { model.cancelOpen() }) { Text(stringResource(R.string.cancel)) } },
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
                }) + if (p.title.isNotEmpty()) "\n\n" + p.title else "")
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
