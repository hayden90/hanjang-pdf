"""왼쪽 쪽 그림 목록: 여러 쪽 고르기, 끌어서 순서 바꾸기, PDF 파일 끌어다 넣기.

끌기는 Qt 기본 항목 끌기 대신 직접 만든다(쪽 번호를 담은 전용 형식). 기본 방식은 목록 항목을
따로 지우거나 옮기려 해서 문서와 어긋날 수 있고, 놓기는 반드시 viewport가 받아야 한다.
"""
from __future__ import annotations

import pymupdf
from PySide6.QtCore import QMimeData, QPoint, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QCursor, QDrag, QFont, QIcon, QPainter, QPen, QPixmap
from PySide6.QtWidgets import QAbstractItemView, QListView, QListWidget, QListWidgetItem, QMenu

from .render import page_pixmap

THUMB_W = 130
BATCH = 6  # 한 번에 그릴 쪽 그림 수(큰 문서에서 화면이 멈추지 않게)
EDGE = 36  # 끄는 동안 이 픽셀 안으로 가면 목록을 굴린다
SCROLL_STEP = 24
EDGE_BAND = 14  # 쪽 그림 위·아래 가장자리 이만큼은 "쪽 사이에 넣기", 가운데는 "그 쪽 자리 차지하기"
MIME_PAGES = "application/x-pdfviewer-pages"
DRAG_ICON_W = 90
THUMB_H = int(THUMB_W * 1.42)
STYLE = """
QListWidget { background: #f1f3f5; }
QListWidget::item { border: 2px solid transparent; border-radius: 4px; padding: 2px; color: #333; }
QListWidget::item:hover { background: #e7f0fb; }
QListWidget::item:selected { background: #d0e4ff; border: 2px solid #1c7ed6; color: #1c3d6e; }
"""


def thumb_icon(pm: QPixmap) -> QIcon:
    """고른 쪽도 그림이 그대로 보이게(윈도우는 고른 그림을 회색으로 덮어 버림) 모든 모드에 같은 그림을 넣는다."""
    icon = QIcon()
    for mode in (QIcon.Mode.Normal, QIcon.Mode.Selected, QIcon.Mode.Active):
        icon.addPixmap(pm, mode)
    return icon


def pdf_paths_from_mime(mime) -> list[str]:
    if not mime.hasUrls():
        return []
    paths = [u.toLocalFile() for u in mime.urls() if u.isLocalFile()]
    return [p for p in paths if p.lower().endswith(".pdf")]


class ThumbList(QListWidget):
    pageClicked = Signal(int)
    insertFiles = Signal(list, int)  # 파일 경로들, 넣을 위치
    movePages = Signal(list, int)  # 고른 쪽들, 옮길 위치
    contextAction = Signal(str, list)  # 동작 이름, 고른 쪽들

    def __init__(self, parent=None):
        super().__init__(parent)
        self.doc: pymupdf.Document | None = None
        self._drop_row: int | None = None
        self._drag_pages: list[int] | None = None
        self._pending: list[int] = []
        self.setViewMode(QListView.ViewMode.IconMode)
        self.setFlow(QListView.Flow.TopToBottom)
        self.setWrapping(False)
        self.setResizeMode(QListView.ResizeMode.Adjust)
        self.setMovement(QListView.Movement.Static)
        self.setIconSize(QSize(THUMB_W, THUMB_H))
        # 가로로 돌린 쪽이 섞여도 칸 크기가 같아야 목록이 흐트러지지 않는다
        self.setUniformItemSizes(True)
        self.setGridSize(QSize(THUMB_W + 24, THUMB_H + 34))
        self.setStyleSheet(STYLE)
        self.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.setDragEnabled(True)
        self.setDropIndicatorShown(False)  # 넣을 자리 줄은 직접 그린다
        self.setDragDropMode(QAbstractItemView.DragDropMode.DragDrop)
        self.setDefaultDropAction(Qt.DropAction.CopyAction)
        # setMovement(Static)이 viewport의 놓기 받기를 꺼 버리므로 반드시 다시 켠다(실제 놓기는 viewport가 받음)
        self.setAcceptDrops(True)
        self.viewport().setAcceptDrops(True)
        self.setToolTip("쪽을 끌어서 원하는 자리에 놓으면 순서가 바뀝니다.\n"
                        "다른 PDF 파일을 끌어다 놓으면 그 자리에 들어갑니다.\n"
                        "오른쪽 클릭: 회전·내보내기·지우기")
        self.setMinimumWidth(THUMB_W + 48)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(self._menu)
        self.itemClicked.connect(lambda it: self.pageClicked.emit(self.row(it)))
        self._timer = QTimer(self, interval=0)
        self._timer.timeout.connect(self._render_batch)
        self._scroll_dir = 0
        self._scroll_timer = QTimer(self, interval=40)
        self._scroll_timer.timeout.connect(self._drag_scroll)

    # ── 목록 채우기 ──
    def set_document(self, doc: pymupdf.Document, select: list[int] | None = None) -> None:
        self.doc = doc
        self.clear()
        blank = QPixmap(self.iconSize())
        blank.fill(QColor("white"))
        blank_icon = thumb_icon(blank)
        for i in range(doc.page_count):
            it = QListWidgetItem(blank_icon, str(i + 1))
            it.setTextAlignment(Qt.AlignmentFlag.AlignHCenter)
            self.addItem(it)
        self._pending = list(range(doc.page_count))
        self._timer.start()
        for i in select or []:
            if 0 <= i < self.count():
                self.item(i).setSelected(True)

    def _render_batch(self) -> None:
        if self.doc is None or not self._pending:
            self._timer.stop()
            return
        for _ in range(min(BATCH, len(self._pending))):
            i = self._pending.pop(0)
            if i >= self.count() or i >= self.doc.page_count:
                continue
            r = self.doc[i].rect
            scale = min(THUMB_W / r.width, self.iconSize().height() / r.height)
            self.item(i).setIcon(thumb_icon(page_pixmap(self.doc[i], scale * self.devicePixelRatioF())))

    def rerender(self, i: int) -> None:
        if 0 <= i < self.count() and i not in self._pending:
            self._pending.append(i)
            self._timer.start()

    def selected_pages(self) -> list[int]:
        return sorted(self.row(it) for it in self.selectedItems())

    def mark_current(self, n: int) -> None:
        if 0 <= n < self.count():
            self.scrollToItem(self.item(n))
            self.setCurrentRow(n, self.selectionModel().SelectionFlag.NoUpdate)

    # ── 끌기 시작(쪽 번호를 담아 보냄) ──
    def pages_mime(self, pages: list[int]) -> QMimeData:
        mime = QMimeData()
        mime.setData(MIME_PAGES, f"{id(self)}:{','.join(map(str, pages))}".encode())
        return mime

    def _internal_pages(self, mime) -> list[int] | None:
        """이 목록에서 끌어 온 쪽 번호. 다른 탭·프로그램에서 온 것이면 None."""
        if not mime.hasFormat(MIME_PAGES):
            return None
        try:
            owner, _, nums = bytes(mime.data(MIME_PAGES)).decode().partition(":")
            if owner != str(id(self)) or not nums:
                return None
            pages = [int(n) for n in nums.split(",")]
        except (UnicodeDecodeError, ValueError):
            return None  # 다른 프로그램이 만든 깨진 데이터는 무시
        if self.doc is None or any(not 0 <= n < self.doc.page_count for n in pages):
            return None
        return pages

    def _drag_pixmap(self, pages: list[int]) -> QPixmap:
        icon = self.item(pages[0]).icon().pixmap(self.iconSize())
        pm = icon.scaledToWidth(DRAG_ICON_W, Qt.TransformationMode.SmoothTransformation)
        if len(pages) > 1:  # 여러 쪽이면 장 수를 붙여 보여 준다
            p = QPainter(pm)
            p.fillRect(0, 0, 34, 22, QColor("#e8590c"))
            p.setPen(QColor("white"))
            f = QFont(); f.setBold(True); p.setFont(f)
            p.drawText(0, 0, 34, 22, Qt.AlignmentFlag.AlignCenter, f"{len(pages)}장")
            p.end()
        return pm

    def startDrag(self, _actions):
        pages = self.selected_pages()
        if not pages:
            return
        drag = QDrag(self)
        drag.setMimeData(self.pages_mime(pages))
        pm = self._drag_pixmap(pages)
        drag.setPixmap(pm)
        drag.setHotSpot(QPoint(pm.width() // 2, pm.height() // 2))
        drag.exec(Qt.DropAction.CopyAction)
        self._stop_drag()

    # ── 끌어다 놓기 ──
    def _row_at(self, pos: QPoint, pages: list[int] | None = None) -> int:
        """놓을 자리(그 번호 쪽 앞, 쪽 수면 맨 끝)를 높이(y)로 정한다(틈·옆 빈 곳도 맞게).

        목록 안 쪽을 옮길 때(pages) 다른 쪽 그림 한가운데에 놓으면 그 쪽 자리를 차지한다(대표님 요청 2026-10-26):
        아래로 옮기면 그 쪽 뒤, 위로 옮기면 그 쪽 앞. 그림 위·아래 가장자리(EDGE_BAND)와 틈은
        가까운 쪽 사이에 넣는다. 다른 PDF 파일을 넣을 때는 그림 가운데보다 위·아래로 정한다.
        """
        y = pos.y()
        for i in range(self.count()):
            r = self.visualItemRect(self.item(i))
            if pages and r.top() + EDGE_BAND <= y <= r.bottom() - EDGE_BAND:
                if i > max(pages):
                    return i + 1
                if i < min(pages):
                    return i
            if y < r.center().y():
                return i
        return self.count()

    def _accepts(self, e) -> bool:
        return self._internal_pages(e.mimeData()) is not None or bool(pdf_paths_from_mime(e.mimeData()))

    def dragEnterEvent(self, e):
        self._drag_pages = self._internal_pages(e.mimeData())
        if self._accepts(e):
            e.setDropAction(Qt.DropAction.CopyAction)
            e.accept()
        else:
            e.ignore()

    def dragMoveEvent(self, e):
        if not self._accepts(e):
            e.ignore()
            return
        pos = e.position().toPoint()
        self._drop_row = self._row_at(pos, self._internal_pages(e.mimeData()))
        self._update_drag_scroll(pos.y())
        self.viewport().update()
        e.setDropAction(Qt.DropAction.CopyAction)
        e.accept()

    def _update_drag_scroll(self, y: int) -> None:
        h = self.viewport().height()
        self._scroll_dir = -1 if y < EDGE else (1 if y > h - EDGE else 0)
        if self._scroll_dir and not self._scroll_timer.isActive():
            self._scroll_timer.start()
        elif not self._scroll_dir:
            self._scroll_timer.stop()

    def _drag_scroll(self) -> None:
        bar = self.verticalScrollBar()
        bar.setValue(bar.value() + self._scroll_dir * SCROLL_STEP)
        # 커서가 멈춰 있어도 목록이 굴렀으니 넣을 자리 줄을 커서 아래로 다시 맞춘다
        self._drop_row = self._row_at(self.viewport().mapFromGlobal(QCursor.pos()), self._drag_pages)
        self.viewport().update()
        if bar.value() in (bar.minimum(), bar.maximum()):
            self._scroll_timer.stop()

    def _stop_drag(self) -> None:
        self._drop_row = None
        self._drag_pages = None
        self._scroll_dir = 0
        self._scroll_timer.stop()
        self.viewport().update()

    def dragLeaveEvent(self, e):
        self._stop_drag()
        super().dragLeaveEvent(e)

    def dropEvent(self, e):
        pages = self._internal_pages(e.mimeData())
        row = self._row_at(e.position().toPoint(), pages)
        self._stop_drag()
        self.viewport().update()
        # CopyAction으로 받아야 Qt가 원래 항목을 지우지 않는다(목록은 문서에서 다시 만든다)
        e.setDropAction(Qt.DropAction.CopyAction)
        e.accept()
        if pages is not None:
            self.movePages.emit(pages, row)
            return
        paths = pdf_paths_from_mime(e.mimeData())
        if paths:
            self.insertFiles.emit(paths, row)

    def paintEvent(self, e):
        super().paintEvent(e)
        if self._drop_row is None or self.count() == 0:
            return
        if self._drop_row < self.count():
            y = self.visualItemRect(self.item(self._drop_row)).top() - 3
        else:
            y = self.visualItemRect(self.item(self.count() - 1)).bottom() + 3
        p = QPainter(self.viewport())
        p.setPen(QPen(QColor("#e8590c"), 4))
        p.drawLine(4, y, self.viewport().width() - 4, y)

    def keyPressEvent(self, e):
        if e.key() == Qt.Key.Key_Delete and self.selected_pages():
            self.contextAction.emit("delete", self.selected_pages())
            return
        super().keyPressEvent(e)

    # ── 오른쪽 단추 메뉴 ──
    def _menu(self, pos: QPoint) -> None:
        it = self.itemAt(pos)
        if it is not None and not it.isSelected():
            self.clearSelection()
            it.setSelected(True)
        sel = self.selected_pages()
        m = QMenu(self)
        for key, label, need_sel in [
            ("export", "고른 쪽 내보내기(다른 이름으로 저장)…", True),
            ("insert_before", "이 쪽 앞에 PDF 넣기…", True),
            ("insert_after", "이 쪽 뒤에 PDF 넣기…", True),
            ("rotate_right", "↻ 시계 방향 90° 돌리기", True),
            ("rotate_left", "↺ 반시계 방향 90° 돌리기", True),
            ("delete", "쪽 지우기", True),
        ]:
            act = m.addAction(label)
            act.setEnabled(bool(sel) or not need_sel)
            act.triggered.connect(lambda _=False, k=key: self.contextAction.emit(k, self.selected_pages()))
        m.exec(self.viewport().mapToGlobal(pos))
