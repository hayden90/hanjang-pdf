package com.bubigter.hanjangpdf.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.bubigter.hanjangpdf.R
import com.bubigter.hanjangpdf.core.Annots

/** '#rrggbb' → 화면 색. */
internal fun hexColor(h: String): Color = Color(0xFF000000 or h.substring(1).toLong(16))

private val TOOL_LABELS = listOf(
    Tool.VIEW to R.string.tool_view, Tool.SELECT to R.string.tool_select, Tool.HIGHLIGHT to R.string.tool_highlight,
    Tool.NOTE to R.string.tool_note, Tool.TEXT to R.string.tool_text, Tool.BOX to R.string.tool_box,
    Tool.ERASE to R.string.tool_erase,
)

/** 도구 막대: 도구 고르기·되돌리기·다시 하기(쓰는 칸이 떠 있으면 문서 되돌리기는 막는다: 칸 안 글은 칸에서만). */
@Composable
fun ToolBar(model: ViewerModel) {
    val t = model.tools
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for ((tool, label) in TOOL_LABELS) {
            FilterChip(selected = t.tool == tool, onClick = { t.tool = tool; if (tool != Tool.SELECT) t.selection = null },
                label = { Text(stringResource(label)) }, enabled = t.editor == null,
                modifier = Modifier.padding(end = 4.dp).testTag("tool_${tool.name}"))
        }
        val canDoc = t.editor == null && !model.busy
        TextButton(onClick = { model.undo() }, enabled = canDoc && model.canUndo, modifier = Modifier.testTag("toolUndo")) { Text("↶") }
        TextButton(onClick = { model.redo() }, enabled = canDoc && model.canRedo, modifier = Modifier.testTag("toolRedo")) { Text("↷") }
    }
    ToolOptions(model)
}

@Composable
private fun Swatch(color: String?, selected: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, modifier = Modifier.padding(end = 4.dp).testTag(tag), label = {
        if (color == null) Text(stringResource(R.string.none))
        else Box(Modifier.size(18.dp).background(hexColor(color), CircleShape).border(1.dp, Color.Gray, CircleShape))
    })
}

/** 고른 도구의 색·모양 줄. */
@Composable
private fun ToolOptions(model: ViewerModel) {
    val t = model.tools
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        when (t.tool) {
            Tool.HIGHLIGHT -> for ((name, label) in listOf("yellow" to R.string.hl_yellow, "green" to R.string.hl_green, "pink" to R.string.hl_pink)) {
                val c = Annots.HL_COLORS.getValue(name)
                FilterChip(selected = t.hlColor == name, onClick = { t.hlColor = name }, modifier = Modifier.padding(end = 4.dp).testTag("hl_$name"),
                    label = { Text(stringResource(label)) }, leadingIcon = {
                        Box(Modifier.size(14.dp).background(Color(c[0], c[1], c[2]), CircleShape))
                    })
            }
            Tool.TEXT -> {
                Text(stringResource(R.string.hint_text), style = MaterialTheme.typography.bodySmall)
            }
            Tool.BOX -> {
                val st = t.boxStyle
                Text(stringResource(R.string.line) + " ")
                AnnotTools.LINE_COLORS.forEach { c -> Swatch(c, st.line == c, "line_${c ?: "none"}") { t.boxStyle = st.copy(line = c) } }
                Text(" " + stringResource(R.string.fill) + " ")
                AnnotTools.FILL_COLORS.forEach { c -> Swatch(c, st.fill == c, "fill_${c ?: "none"}") { t.boxStyle = st.copy(fill = c) } }
                Text(" " + stringResource(R.string.width) + " ")
                Annots.SHAPE_WIDTHS.forEach { w ->
                    FilterChip(selected = st.width == w, onClick = { t.boxStyle = st.copy(width = w) }, modifier = Modifier.padding(end = 4.dp),
                        label = { Text(if (w % 1.0 == 0.0) w.toInt().toString() else w.toString()) })
                }
                Text(" " + stringResource(R.string.opacity) + " ")
                AnnotTools.OPACITIES.forEach { o ->
                    FilterChip(selected = st.opacity == o, onClick = { t.boxStyle = st.copy(opacity = o) }, modifier = Modifier.padding(end = 4.dp),
                        label = { Text("${(o * 100).toInt()}%") })
                }
            }
            Tool.SELECT -> {
                val sel = t.selection?.takeIf { it.gen == model.gen }
                Text(if (sel == null) stringResource(R.string.hint_select) else stringResource(R.string.selected_words, sel.rects.size),
                    Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val s = sel ?: return@TextButton
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", s.text))
                    model.notify(R.string.copied)
                }, enabled = sel != null, modifier = Modifier.testTag("copy")) { Text(stringResource(R.string.copy)) }
            }
            Tool.NOTE -> Text(stringResource(R.string.hint_note), style = MaterialTheme.typography.bodySmall)
            Tool.ERASE -> Text(stringResource(R.string.hint_erase_tool), style = MaterialTheme.typography.bodySmall)
            Tool.VIEW -> Text(stringResource(R.string.hint_view), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 글자 상자 쓰는 칸(화면 아래). 크기·정렬·색, 칸 안 글 되돌리기·다시 하기(문서 되돌리기와 따로), 확인·취소.
 * 상자 자리는 쪽 위 손잡이(✥)로 옮긴다([PdfView]). 확인한 칸을 넣는 중([AnnotTools.inputLocked])에는 입력·단추를 잠근다
 * (그때 친 글은 넣은 뒤 칸이 닫히며 사라지므로. 잠기기 직전 같은 틀에 들어온 입력도 [InputTransformation]이 되돌린다).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditorPanel(model: ViewerModel) {
    val t = model.tools
    val e = t.editor ?: return
    key(e.seq) {
        val state = rememberTextFieldState(e.text)
        val locked = t.inputLocked
        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() }.collect { s -> t.editor?.let { cur -> t.updateEditor(cur.copy(text = s)) } }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                BasicTextField(state, Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline).padding(8.dp).testTag("textInput"),
                    textStyle = TextStyle(color = hexColor(e.color), fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    lineLimits = TextFieldLineLimits.MultiLine(1, 6), readOnly = locked,
                    inputTransformation = InputTransformation { if (t.inputLocked) revertAllChanges() })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (!t.inputLocked) state.undoState.undo() }, enabled = !locked && state.undoState.canUndo) { Text("↶") }
                    TextButton(onClick = { if (!t.inputLocked) state.undoState.redo() }, enabled = !locked && state.undoState.canRedo) { Text("↷") }
                    Text(stringResource(R.string.text_size) + " ")
                    TextButton(onClick = { setStyle(t, e.copy(size = maxOf(Annots.TEXT_MIN, e.size - 1))) }, enabled = !locked) { Text("−") }
                    Text("${e.size}", Modifier.testTag("textSize"))
                    TextButton(onClick = { setStyle(t, e.copy(size = minOf(Annots.TEXT_MAX, e.size + 1))) }, enabled = !locked) { Text("+") }
                    for ((a, label) in listOf("left" to R.string.align_left, "center" to R.string.align_center, "right" to R.string.align_right)) {
                        FilterChip(selected = e.align == a, onClick = { setStyle(t, e.copy(align = a)) }, modifier = Modifier.padding(end = 4.dp),
                            label = { Text(stringResource(label)) }, enabled = !locked)
                    }
                    AnnotTools.TEXT_COLORS.forEach { c -> Swatch(c, e.color == c, "tc_$c", enabled = !locked) { setStyle(t, e.copy(color = c)) } }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hint_grip), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { t.cancelEditor() }, modifier = Modifier.testTag("textCancel")) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { t.commitEditor() }, enabled = !model.busy, modifier = Modifier.testTag("textOk")) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

/** 크기·정렬·색을 바꾸면 다음에 여는 칸도 그 모양으로(PC판 last_style). 칸이 받지 않으면(넣는 중 등) 아무것도 안 바꾼다. */
private fun setStyle(t: AnnotTools, e: TextEdit) {
    if (!t.updateEditor(e)) return
    t.textSize = e.size; t.textAlign = e.align; t.textColor = e.color
}

/**
 * 메모 붙이기·고치기 창. 쓴 글은 모델([AnnotTools.note])에 두어 화면을 돌려도 남는다.
 * 글을 쓰거나 고쳤으면 창 밖 누르기·뒤로 가기로는 닫지 않는다(버리려면 '취소'). 붙이는 중에는 입력 칸을 잠근다
 * ([AnnotTools.updateNote]도 그동안 받지 않으므로 칸 글은 모델 글 그대로).
 */
@Composable
fun NoteDialog(model: ViewerModel) {
    val t = model.tools
    val n = t.note ?: return
    AlertDialog(
        onDismissRequest = { if (!t.unsaved) t.cancelNote() },
        title = { Text(stringResource(if (n.xref == null) R.string.note_add else R.string.note_edit)) },
        text = {
            OutlinedTextField(n.text, { t.updateNote(it) }, Modifier.fillMaxWidth().testTag("noteInput"), readOnly = t.inputLocked,
                placeholder = { Text(stringResource(R.string.note_hint)) }, minLines = 3)
        },
        confirmButton = { TextButton(onClick = { t.commitNote(n.text) }, enabled = !model.busy, modifier = Modifier.testTag("noteOk")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { t.cancelNote() }) { Text(stringResource(R.string.cancel)) } },
    )
}
