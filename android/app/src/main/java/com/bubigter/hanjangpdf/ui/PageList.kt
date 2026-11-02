package com.bubigter.hanjangpdf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.bubigter.hanjangpdf.R
import com.bubigter.hanjangpdf.core.ViewDoc

/**
 * 쪽 목록(썸네일). 눌러 그 쪽으로 이동, 길게 눌러 고르기, 고른 쪽을 길게 눌러 끌면 그 자리로 옮기기.
 * 고른 쪽으로 돌리기·지우기·내보내기, 다른 PDF 끼워 넣기(고른 마지막 쪽 뒤, 없으면 맨 끝).
 */
@Composable
fun PageList(
    model: ViewerModel,
    doc: ViewDoc,
    selected: Set<Int>,
    onSelect: (Set<Int>) -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onInsert: (Int) -> Unit,
) {
    val count = doc.pageCount
    val grid = rememberLazyGridState()
    // 끌기: 끌고 있는 손가락 자리와 놓일 자리(원래 번호 기준 '그 쪽 앞', count면 맨 끝)
    var dropAt by remember { mutableStateOf<Int?>(null) }
    // 끄는 도중 고르기가 바뀌어도 손짓이 끊기지 않게 최신 값을 따로 읽는다.
    val selNow by rememberUpdatedState(selected)

    Column(Modifier.fillMaxSize().testTag("pageList")) {
        // 안내 글은 단추 줄(가로로 밀어 봄) 위에 따로 두어 큰 글꼴에서도 화면 안에서 줄바꿈된다(8-5)
        Text(if (selected.isEmpty()) stringResource(R.string.pages_hint) else stringResource(R.string.selected, selected.size),
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val has = selected.isNotEmpty()
            TextButton(onClick = { model.rotatePages(selected, -90) }, enabled = has, modifier = Modifier.testTag("rotL")) { Text(stringResource(R.string.rotate_left)) }
            TextButton(onClick = { model.rotatePages(selected, 90) }, enabled = has, modifier = Modifier.testTag("rotR")) { Text(stringResource(R.string.rotate_right)) }
            TextButton(onClick = {
                if (selected.size >= count) model.notify(R.string.last_page_warn)
                else model.deletePages(selected) { onSelect(emptySet()) }
            }, enabled = has, modifier = Modifier.testTag("delete")) { Text(stringResource(R.string.delete)) }
            TextButton(onClick = onExport, enabled = has, modifier = Modifier.testTag("export")) { Text(stringResource(R.string.export)) }
            TextButton(onClick = { onInsert(if (has) selected.max() + 1 else count) }, modifier = Modifier.testTag("insert")) { Text(stringResource(R.string.insert)) }
            TextButton(onClick = { onSelect(if (selected.size == count) emptySet() else (0 until count).toSet()) }) {
                Text(stringResource(if (selected.size == count) R.string.select_none else R.string.select_all))
            }
            TextButton(onClick = onClose, modifier = Modifier.testTag("closePages")) { Text(stringResource(R.string.close)) }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp), state = grid,
            modifier = Modifier.fillMaxSize().pointerInput(count) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val i = itemAt(grid, pos) ?: return@detectDragGesturesAfterLongPress
                        // 고르지 않은 쪽을 길게 누르면 고르기에 넣는다.
                        if (i !in selNow) onSelect(selNow + i)
                        dropAt = null
                    },
                    onDrag = { change, _ -> dropAt = dropIndex(grid, change.position, count) },
                    onDragEnd = {
                        val to = dropAt
                        dropAt = null
                        val moving = selNow
                        if (to != null && moving.isNotEmpty()) model.movePages(moving, to) { pos ->
                            // 옮긴 묶음은 새 자리에서 계속 골라 둔다
                            onSelect((pos until pos + moving.size).toSet())
                        }
                    },
                    onDragCancel = { dropAt = null },
                )
            },
        ) {
            items(count, key = { it }) { i ->
                LaunchedEffect(model.gen, i) { model.loadThumb(i) }
                val sel = i in selected
                val size = doc.sizes.getOrNull(i)
                val tap = {
                    if (selected.isNotEmpty()) onSelect(if (sel) selected - i else selected + i)
                    else { model.jumpPage = i; onClose() }
                }
                val toggle = { onSelect(if (sel) selected - i else selected + i) }
                // 손가락 없이(TalkBack·스위치 접근·키보드) 같은 일: 누르기 = 보기(고르는 중이면 고르기), 길게 = 고르기,
                // 고른 쪽 한 칸 앞·뒤로 옮기기(끌어 옮기기 대신, 8-5 접근성). Enter=누르기, Space=고르기.
                val desc = stringResource(R.string.thumb_desc, i + 1)
                val tapLabel = stringResource(if (selected.isNotEmpty()) (if (sel) R.string.thumb_unselect else R.string.thumb_select) else R.string.thumb_open)
                val toggleLabel = stringResource(if (sel) R.string.thumb_unselect else R.string.thumb_select)
                val earlier = stringResource(R.string.move_earlier)
                val later = stringResource(R.string.move_later)
                Column(Modifier.padding(6.dp).testTag("thumb$i")
                    .semantics(mergeDescendants = true) {
                        contentDescription = desc
                        this.selected = sel
                        onClick(tapLabel) { tap(); true }
                        onLongClick(toggleLabel) { toggle(); true }
                        if (sel) customActions = listOf(
                            CustomAccessibilityAction(earlier) { moveBy(model, selected, -1, count, onSelect) },
                            CustomAccessibilityAction(later) { moveBy(model, selected, 1, count, onSelect) },
                        )
                    }
                    .onKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) false
                        else when (ev.key) {
                            Key.Enter, Key.NumPadEnter -> { tap(); true }
                            Key.Spacebar -> { toggle(); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .pointerInput(selected) { detectTapGestures(onTap = { tap() }) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(if (size != null && size.h > 0) maxOf(0.3f, size.w / size.h) else 0.7f)
                        .background(Color.White)
                        .border(if (sel) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, Color.LightGray))
                        .then(if (dropAt == i) Modifier.border(BorderStroke(4.dp, Color(0xFFFF8F00))) else Modifier)) {
                        // 칸마다 thumbTick을 읽어야 썸네일이 들어올 때 그 칸이 다시 그려진다(바깥에서 읽으면 칸은 건너뜀)
                        val tick = model.thumbTick
                        remember(tick, i) { model.thumb(i) }?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                    }
                    Text("${i + 1}" + if (dropAt == i) " ◀" else "", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 고른 쪽 묶음을 한 칸 앞([dir] -1)·뒤(+1)로(끌어 옮기기와 같은 [ViewerModel.movePages], 원래 번호 기준 '그 쪽 앞').
 * 더 갈 곳이 없으면 거짓.
 */
private fun moveBy(model: ViewerModel, sel: Set<Int>, dir: Int, count: Int, onSelect: (Set<Int>) -> Unit): Boolean {
    if (sel.isEmpty()) return false
    val to = if (dir < 0) sel.min() - 1 else sel.max() + 2
    if (to < 0 || to > count) return false
    model.movePages(sel, to) { pos -> onSelect((pos until pos + sel.size).toSet()) }
    return true
}

/** 손가락 자리의 쪽 번호. */
private fun itemAt(grid: LazyGridState, pos: Offset): Int? =
    grid.layoutInfo.visibleItemsInfo.firstOrNull {
        pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width &&
            pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
    }?.index

/** 놓일 자리: 손가락이 쪽 왼쪽 절반이면 그 쪽 앞, 오른쪽 절반이면 다음 쪽 앞(원래 번호, count면 맨 끝). */
private fun dropIndex(grid: LazyGridState, pos: Offset, count: Int): Int? {
    val it = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height &&
            pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width
    } ?: return null
    val right = pos.x > it.offset.x + it.size.width / 2f
    return minOf(count, it.index + if (right) 1 else 0)
}
