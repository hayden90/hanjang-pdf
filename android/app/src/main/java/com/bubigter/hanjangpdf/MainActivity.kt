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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.bubigter.hanjangpdf.ui.PdfView
import com.bubigter.hanjangpdf.ui.ViewerModel

class MainActivity : ComponentActivity() {
    private val model: ViewerModel by viewModels()

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

    LaunchedEffect(model.message) {
        model.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); model.consumeMessage() }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(model.title.ifEmpty { stringResource(R.string.app_name) }, Modifier.weight(1f).padding(8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (model.doc != null) TextButton(onClick = {
                searching = !searching
                if (!searching) model.clearSearch()
            }, Modifier.testTag("searchToggle")) { Text(stringResource(R.string.find)) }
            TextButton(onClick = openPdf, Modifier.testTag("open")) { Text(stringResource(R.string.open)) }
        }
        if (searching && model.doc != null) SearchBar(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = model.doc
            if (d != null) PdfView(model, d)
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.empty_hint), Modifier.padding(16.dp))
                Button(onClick = openPdf) { Text(stringResource(R.string.open_pdf)) }
            }
            if (model.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    if (model.pwDoc != null) PasswordDialog(model)
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
