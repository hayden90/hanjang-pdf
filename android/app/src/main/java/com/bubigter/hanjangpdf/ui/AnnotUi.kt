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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.bubigter.hanjangpdf.R
import com.bubigter.hanjangpdf.core.Annots

/** '#rrggbb' → 화면 색. */
internal fun hexColor(h: String): Color = Color(0xFF000000 or h.substring(1).toLong(16))

/** 색 단추가 읽힐 이름(TalkBack, 색만으로 고르지 않게. 8-5 접근성). */
private val COLOR_NAMES = mapOf(
    "#000000" to R.string.color_black, "#d00000" to R.string.color_red, "#0050c8" to R.string.color_blue,
    "#0000ff" to R.string.color_blue, "#00a000" to R.string.color_green, "#ffff00" to R.string.color_yellow,
    "#ffcccc" to R.string.color_pink, "#cccccc" to R.string.color_gray,
)

@Composable
internal fun colorName(h: String?): String = when (h) {
    null -> stringResource(R.string.none)
    else -> COLOR_NAMES[h]?.let { stringResource(it) } ?: stringResource(R.string.color_other, h)
}

/** 글자 대신 기호만 보이는 단추([sym])에 읽힐 이름([desc])을 붙인다. */
@Composable
private fun Sym(sym: String, desc: String) {
    Text(sym, Modifier.semantics { contentDescription = desc })
}

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
        TextButton(onClick = { model.undo() }, enabled = canDoc && model.canUndo, modifier = Modifier.testTag("toolUndo")) { Sym("↶", stringResource(R.string.undo)) }
        TextButton(onClick = { model.redo() }, enabled = canDoc && model.canRedo, modifier = Modifier.testTag("toolRedo")) { Sym("↷", stringResource(R.string.redo)) }
    }
    ToolOptions(model)
}

@Composable
private fun Swatch(color: String?, selected: Boolean, tag: String, enabled: Boolean = true, kind: Int, onClick: () -> Unit) {
    // 색 동그라미만 보이므로 '선 색 빨강'처럼 읽히게 한다([kind]는 선·채우기·글자 색 글)
    val desc = stringResource(kind, colorName(color))
    FilterChip(selected = selected, onClick = onClick, enabled = enabled,
        modifier = Modifier.padding(end = 4.dp).testTag(tag).semantics { contentDescription = desc }, label = {
        if (color == null) Text(stringResource(R.string.none), Modifier.clearAndSetSemantics {}) // 이름에 이미 '없음'이 있음
        else Box(Modifier.size(18.dp).background(hexColor(color), CircleShape).border(1.dp, Color.Gray, CircleShape))
    })
}

/** 고른 도구의 색·모양 줄. */
@Composable
private fun ToolOptions(model: ViewerModel) {
    val t = model.tools
    val ctx = LocalContext.current
    // 고를 것이 많은 줄(형광펜 색·상자 모양)만 가로로 밀어 본다. 안내 글은 큰 글꼴에서도 화면 안에서 줄바꿈되게 보통 줄에 둔다(8-5).
    val scroll = t.tool == Tool.HIGHLIGHT || t.tool == Tool.BOX
    Row(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 8.dp),
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
                AnnotTools.LINE_COLORS.forEach { c -> Swatch(c, st.line == c, "line_${c ?: "none"}", kind = R.string.line_color) { t.boxStyle = st.copy(line = c) } }
                Text(" " + stringResource(R.string.fill) + " ")
                AnnotTools.FILL_COLORS.forEach { c -> Swatch(c, st.fill == c, "fill_${c ?: "none"}", kind = R.string.fill_color) { t.boxStyle = st.copy(fill = c) } }
                Text(" " + stringResource(R.string.width) + " ")
                Annots.SHAPE_WIDTHS.forEach { w ->
                    val wt = if (w % 1.0 == 0.0) w.toInt().toString() else w.toString()
                    val wd = stringResource(R.string.width_n, wt)
                    FilterChip(selected = st.width == w, onClick = { t.boxStyle = st.copy(width = w) },
                        modifier = Modifier.padding(end = 4.dp).semantics { contentDescription = wd }, label = { Text(wt) })
                }
                Text(" " + stringResource(R.string.opacity) + " ")
                AnnotTools.OPACITIES.forEach { o ->
                    val od = stringResource(R.string.opacity_n, (o * 100).toInt())
                    FilterChip(selected = st.opacity == o, onClick = { t.boxStyle = st.copy(opacity = o) },
                        modifier = Modifier.padding(end = 4.dp).semantics { contentDescription = od },
                        label = { Text("${(o * 100).toInt()}%") })
                }
            }
            Tool.SELECT -> {
                val sel = t.selection?.takeIf { it.gen == model.gen }
                Text(if (sel == null) stringResource(R.string.hint_select) else stringResource(R.string.selected_words, sel.rects.size),
                    Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
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
                // 칸 바탕은 종이처럼 늘 흰색(글자 색이 검정이어도 어두운 화면에서 보이게, 8-5). 안쪽 여백은 decorationBox에
                // 두어 누르는 영역이 칸 전체가 되게 하고, TalkBack이 무슨 칸인지 읽게 이름을 붙인다.
                val inputDesc = stringResource(R.string.text_input_label)
                BasicTextField(state, Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outline).testTag("textInput").semantics { contentDescription = inputDesc },
                    decorator = { inner -> Box(Modifier.padding(8.dp)) { inner() } },
                    textStyle = TextStyle(color = hexColor(e.color), fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    lineLimits = TextFieldLineLimits.MultiLine(1, 6), readOnly = locked,
                    inputTransformation = InputTransformation { if (t.inputLocked) revertAllChanges() })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (!t.inputLocked) state.undoState.undo() }, enabled = !locked && state.undoState.canUndo) { Sym("↶", stringResource(R.string.text_undo)) }
                    TextButton(onClick = { if (!t.inputLocked) state.undoState.redo() }, enabled = !locked && state.undoState.canRedo) { Sym("↷", stringResource(R.string.text_redo)) }
                    Text(stringResource(R.string.text_size) + " ")
                    TextButton(onClick = { setStyle(t, e.copy(size = maxOf(Annots.TEXT_MIN, e.size - 1))) }, enabled = !locked) { Sym("−", stringResource(R.string.text_smaller)) }
                    val sd = stringResource(R.string.text_size_n, e.size)
                    Text("${e.size}", Modifier.testTag("textSize").semantics { contentDescription = sd })
                    TextButton(onClick = { setStyle(t, e.copy(size = minOf(Annots.TEXT_MAX, e.size + 1))) }, enabled = !locked) { Sym("+", stringResource(R.string.text_bigger)) }
                    for ((a, label) in listOf("left" to R.string.align_left, "center" to R.string.align_center, "right" to R.string.align_right)) {
                        FilterChip(selected = e.align == a, onClick = { setStyle(t, e.copy(align = a)) }, modifier = Modifier.padding(end = 4.dp),
                            label = { Text(stringResource(label)) }, enabled = !locked)
                    }
                    AnnotTools.TEXT_COLORS.forEach { c -> Swatch(c, e.color == c, "tc_$c", enabled = !locked, kind = R.string.text_color) { setStyle(t, e.copy(color = c)) } }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hint_grip), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { t.cancelEditor() }, enabled = !locked, modifier = Modifier.testTag("textCancel")) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { t.commitEditor(state.text.toString()) }, enabled = !model.busy, modifier = Modifier.testTag("textOk")) { Text(stringResource(R.string.ok)) }
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
        onDismissRequest = { if (!t.unsaved && !t.inputLocked) t.cancelNote() },
        title = { Text(stringResource(if (n.xref == null) R.string.note_add else R.string.note_edit)) },
        text = {
            OutlinedTextField(n.text, { t.updateNote(it) }, Modifier.fillMaxWidth().testTag("noteInput"), readOnly = t.inputLocked,
                placeholder = { Text(stringResource(R.string.note_hint)) }, minLines = 3)
        },
        confirmButton = { TextButton(onClick = { t.commitNote(n.text) }, enabled = !model.busy, modifier = Modifier.testTag("noteOk")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { t.cancelNote() }, enabled = !t.inputLocked) { Text(stringResource(R.string.cancel)) } },
    )
}
