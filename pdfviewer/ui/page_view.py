"""오른쪽 본문: 쪽을 위아래로 이어 보여 주고, 보이는 쪽만 그린다.

도구 모드(보기·형광펜·메모·글자·상자·지우개)에 따라 쪽 위 마우스 동작을 신호로 내보낸다.
보기 모드에서 끌면 글자를 고르고(text_select, 표는 칸별로), 창의 '글자 복사'(Ctrl+C)가 그 글을 복사한다.
글자·상자·지우개는 끌면 그 사각형으로, 그냥 누르면 그 자리로 동작한다. 글자 상자는 쪽 위에서
바로 쓰는 칸(TextBoxEditor)을 띄우고, 다 쓰면 textCommitted로 알린다.
좌표는 annots.to_page로 돌리기 전 쪽 좌표로 바꿔서 보낸다.
"""
from __future__ import annotations

import itertools

import pymupdf
from PySide6.QtCore import QPoint, QPointF, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QCursor, QPainter, QPen
from PySide6.QtWidgets import QApplication, QScrollArea, QSizePolicy, QToolTip, QVBoxLayout, QWidget

from ..core import annots, text_select

from .render import page_pixmap
from .text_box_editor import TextBoxEditor
from .thumbs import pdf_paths_from_mime

GAP = 12
DROP_EDGE = 40  # 다른 PDF를 끌고 위·아래 이 픽셀 안으로 가면 본문을 굴린다
DROP_SCROLL_STEP = 30
MIN_ZOOM, MAX_ZOOM = 0.25, 6.0
KEEP_AROUND = 2  # 보이는 쪽 앞뒤로 그림을 남겨 둘 쪽 수
MODES = ("view", "highlight", "note", "text", "box", "erase")
DRAG_MODES = ("highlight", "text", "box", "erase")  # 끌어서 사각형을 정하는 모드
CLICK_PX = 4  # 이보다 덜 끌면 그냥 누른 것
MODE_CURSORS = {
    "view": Qt.CursorShape.ArrowCursor,
    "highlight": Qt.CursorShape.IBeamCursor,
    "note": Qt.CursorShape.PointingHandCursor,
    "text": Qt.CursorShape.IBeamCursor,
    "box": Qt.CursorShape.CrossCursor,
    "erase": Qt.CursorShape.CrossCursor,
}
SELECT_COLOR = QColor(40, 110, 230, 80)
_select_seq = itertools.count(1)  # 비교 탭처럼 화면이 둘이면 마지막에 고른 쪽을 복사
PREVIEW = {"yellow": QColor(255, 225, 0, 90), "green": QColor(120, 220, 100, 90), "pink": QColor(255, 140, 200, 90)}


def select_hint(n: int) -> str:
    """글자를 고르기 시작했을 때 상태 줄 안내(n = 고른 낱말 수)."""
    return ("글자를 골랐습니다. 복사: Ctrl+C" if n else
            "이 쪽에는 글자 정보가 없어(스캔·그림으로 된 쪽) 고를 수 없습니다. 도구 > 글자 인식(Ctrl+Shift+O)을 눌러 보세요.")


class PageWidget(QWidget):
    def __init__(self, view: "PageView", index: int, parent=None):
        super().__init__(parent)
        self.view = view
        self.index = index
        self.pixmap = None
        self.hits: list[QRectF] = []
        self.current_hit: QRectF | None = None
        self._press: QPointF | None = None
        self.drag_rect: QRectF | None = None  # 형광펜으로 끄는 동안 미리 보기
        self.sel_rects: list[QRectF] = []  # 고른 글자(보기 모드에서 끌기)
        self.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        self.setMouseTracking(True)  # 메모 위에 올리면 내용 보여 주기

    def mousePressEvent(self, e):
        if e.button() != Qt.MouseButton.LeftButton:
            return super().mousePressEvent(e)
        if self.view.finish_editor():  # 쓰던 글자 상자 밖을 누르면 그 글을 넣고 끝(이 누름은 거기까지)
            self._press = None
            return
        self._press = e.position()
        if self.view.mode == "view":
            self.view.select_start(self.index)
        elif self.view.mode == "note":
            self.view.page_click(self.index, e.position(), "note")

    def mouseMoveEvent(self, e):
        if self.view.mode in DRAG_MODES and self._press is not None:
            self.drag_rect = QRectF(self._press, e.position()).normalized()
            self.update()
        elif self.view.mode == "view" and self._press is not None:
            self.view.select_drag(self.index, self._press, e.position())
        elif self.view.mode == "view" and not e.buttons():
            self.view.page_hover(self.index, e.position(), e.globalPosition().toPoint())

    def mouseReleaseEvent(self, e):
        mode = self.view.mode
        if e.button() == Qt.MouseButton.LeftButton and mode in DRAG_MODES and self._press is not None:
            rect = QRectF(self._press, e.position()).normalized()
            self.drag_rect = None
            self.update()
            if mode == "highlight":
                self.view.page_highlight(self.index, rect)
            elif rect.width() < CLICK_PX and rect.height() < CLICK_PX:  # 끌지 않고 누름
                self.view.page_click(self.index, self._press, mode)
            else:
                self.view.page_area(self.index, rect, mode)
        self._press = None

    def mouseDoubleClickEvent(self, e):
        if self.view.mode == "view" and e.button() == Qt.MouseButton.LeftButton:
            self.view.page_click(self.index, e.position(), "open")

    def paintEvent(self, _):
        p = QPainter(self)
        if self.pixmap is not None:
            p.drawPixmap(self.rect(), self.pixmap)
        else:
            p.fillRect(self.rect(), QColor("white"))
        for r in self.hits:
            p.fillRect(r, QColor(255, 210, 0, 90))
        if self.current_hit is not None:
            p.fillRect(self.current_hit, QColor(255, 120, 0, 140))
        for r in self.sel_rects:
            p.fillRect(r, SELECT_COLOR)
        if self.drag_rect is not None:
            self._paint_drag(p)

    def _paint_drag(self, p: QPainter) -> None:
        """끄는 동안 미리 보기: 형광펜은 그 색, 상자는 고른 선·채우기, 글자는 점선 칸, 지우개는 빨간 칸."""
        mode, r = self.view.mode, self.drag_rect
        if mode == "box":
            st = self.view.box_style
            if st["fill"]:
                c = QColor(st["fill"])
                c.setAlphaF(st["opacity"])
                p.fillRect(r, c)
            if st["line"]:
                c = QColor(st["line"])
                c.setAlphaF(st["opacity"])
                p.setPen(QPen(c, max(1.0, st["width"] * self.view.zoom)))
                p.drawRect(r)
            return
        if mode == "highlight":
            p.fillRect(r, PREVIEW.get(self.view.hl_color, PREVIEW["yellow"]))
            p.setPen(QPen(QColor(80, 80, 80, 160), 1, Qt.PenStyle.DashLine))
        elif mode == "erase":
            p.fillRect(r, QColor(230, 40, 40, 50))
            p.setPen(QPen(QColor(200, 30, 30, 200), 1, Qt.PenStyle.DashLine))
        else:
            p.setPen(QPen(QColor(42, 111, 219), 1, Qt.PenStyle.DashLine))
        p.drawRect(r)


class PageView(QScrollArea):
    currentPageChanged = Signal(int)
    zoomChanged = Signal(float)
    # 쪽 번호, 돌리기 전 쪽 좌표(Rect 또는 Point)
    highlightRequested = Signal(int, object)
    noteRequested = Signal(int, object)
    textRequested = Signal(int, object)  # 글자 도구로 누른 곳(새로 넣거나, 이미 있는 글자 상자면 고치기)
    textBoxRequested = Signal(int, object)  # 글자 도구로 끈 사각형(돌리기 전 쪽 좌표)
    editorChanged = Signal()  # 쓰는 칸이 열리고 닫히거나 칸 안 되돌리기 가능 여부가 바뀜(도구 막대 되돌리기 단추용)
    textCommitted = Signal(int, object)  # 쪽 위에서 다 쓴 글자 상자: dict(box, xref, text, size, align, color)
    boxRequested = Signal(int, object)  # 상자 도구로 끈 사각형
    boxClickRequested = Signal(int, object)  # 상자 도구로 누른 곳(이미 그린 상자면 지금 색으로 바꿈)
    eraseRequested = Signal(int, object)
    eraseAreaRequested = Signal(int, object)  # 지우개로 끈 사각형
    noteOpenRequested = Signal(int, object)
    insertFiles = Signal(list, int)  # 끌어다 놓은 PDF 경로들, 넣을 위치(그 번호 쪽 앞)
    textSelected = Signal(int)  # 고른 글자 수(0이면 그 쪽에 글자 정보가 없음)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.doc: pymupdf.Document | None = None
        self.zoom = 1.0
        self.fit_mode = "width"  # "width" | "page" | None(직접 배율)
        self.pages: list[PageWidget] = []
        self._current = 0
        self._hits: list[tuple[int, pymupdf.Rect]] = []
        self._hit_index = -1
        self.mode = "view"
        self.hl_color = "yellow"
        self.box_style = {"line": "#d00000", "fill": None, "width": 2, "opacity": 1.0}
        self._editor: TextBoxEditor | None = None
        self._editor_at: tuple | None = None  # (쪽 번호, 돌리기 전 사각형, 고치는 상자 xref 또는 None)
        self._cells: dict[int, list] = {}  # 쪽별 표 칸(한 번 찾으면 문서를 다시 열 때까지 씀)
        self._drag_cells: list = []
        self._sel_page = -1
        self._sel_words: list = []
        self._sel_seq = 0
        self._drag_words: list = []  # 끄는 동안 그 쪽 낱말(한 번만 읽음)
        self._sel_told = False
        self.setWidgetResizable(False)
        self.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        self.setStyleSheet("QScrollArea { background: #5a5a5a; }")
        self._render_timer = QTimer(self, singleShot=True, interval=30)
        self._render_timer.timeout.connect(self._render_visible)
        self.verticalScrollBar().valueChanged.connect(self._on_scroll)
        # 편집 탭에서만 켠다(비교 탭은 읽기 전용이라 끌어다 놓기를 받지 않고 창이 새 탭으로 연다)
        self.accept_files = False
        self._drop_line = QWidget(self.viewport())
        self._drop_line.setStyleSheet("background: #e8590c;")
        self._drop_line.hide()
        # 끄는 동안 가장자리에 커서를 멈춰 둬도 계속 굴리도록(쪽 목록과 같은 방식)
        self._drop_scroll = 0
        self._drop_timer = QTimer(self, interval=40)
        self._drop_timer.timeout.connect(self._drop_autoscroll)

    def set_accept_files(self, on: bool) -> None:
        self.accept_files = on
        self.setAcceptDrops(on)
        self.viewport().setAcceptDrops(on)

    # ── 다른 PDF를 끌어다 놓기: 놓은 쪽의 위 절반이면 그 앞, 아래 절반이면 그 뒤에 넣는다 ──
    def drop_index(self, pos: QPoint) -> int:
        """viewport 좌표 pos에 놓으면 들어갈 자리(그 번호 쪽 앞, 쪽 수면 맨 끝)."""
        if self.widget() is None or not self.pages:
            return 0
        y = self.widget().mapFrom(self.viewport(), pos).y()
        for i, w in enumerate(self.pages):
            if y < w.y() + w.height() / 2:
                return i
        return len(self.pages)

    def _show_drop_line(self, at: int) -> None:
        if at < len(self.pages):
            y = self.pages[at].y() - GAP // 2
        else:
            last = self.pages[-1]
            y = last.y() + last.height() + GAP // 2
        vy = self.widget().mapTo(self.viewport(), QPoint(0, y)).y()
        self._drop_line.setGeometry(0, vy - 2, self.viewport().width(), 4)
        self._drop_line.show()
        self._drop_line.raise_()

    def _accepts_drop(self, e) -> bool:
        return self.accept_files and bool(self.pages) and bool(pdf_paths_from_mime(e.mimeData()))

    def dragEnterEvent(self, e):
        if not self._accepts_drop(e):
            e.ignore()
            return
        e.setDropAction(Qt.DropAction.CopyAction)
        e.accept()

    def dragMoveEvent(self, e):
        if not self._accepts_drop(e):
            e.ignore()
            return
        pos = e.position().toPoint()
        h = self.viewport().height()
        # 가장자리로 가면 굴려서 멀리 있는 쪽 사이에도 놓을 수 있게
        self._drop_scroll = -1 if pos.y() < DROP_EDGE else (1 if pos.y() > h - DROP_EDGE else 0)
        if self._drop_scroll and not self._drop_timer.isActive():
            self._drop_timer.start()
        elif not self._drop_scroll:
            self._drop_timer.stop()
        self._show_drop_line(self.drop_index(pos))
        e.setDropAction(Qt.DropAction.CopyAction)
        e.accept()

    def _drop_autoscroll(self) -> None:
        bar = self.verticalScrollBar()
        bar.setValue(bar.value() + self._drop_scroll * DROP_SCROLL_STEP)
        # 커서가 멈춰 있어도 내용이 굴렀으니 넣을 자리 줄을 커서 아래로 다시 맞춘다
        if self.pages:
            self._show_drop_line(self.drop_index(self.viewport().mapFromGlobal(QCursor.pos())))
        if bar.value() in (bar.minimum(), bar.maximum()):
            self._drop_timer.stop()

    def _end_drop(self) -> None:
        self._drop_line.hide()
        self._drop_scroll = 0
        self._drop_timer.stop()

    def dragLeaveEvent(self, e):
        self._end_drop()
        super().dragLeaveEvent(e)

    def dropEvent(self, e):
        self._end_drop()
        if not self._accepts_drop(e):
            e.ignore()
            return
        e.setDropAction(Qt.DropAction.CopyAction)
        e.accept()
        self.insertFiles.emit(pdf_paths_from_mime(e.mimeData()), self.drop_index(e.position().toPoint()))

    # ── 문서 ──
    def set_document(self, doc: pymupdf.Document, keep_page: int = 0, keep_scroll: int | None = None) -> None:
        self.doc = doc
        # 쪽 위젯을 새로 만들면 쓰던 칸도 사라지므로 쓰던 글을 챙겨 두었다가 새 쪽에 다시 연다
        reopen = self._take_editor()
        self._cells = {}
        container = QWidget()
        container.setStyleSheet("background: #5a5a5a;")
        lay = QVBoxLayout(container)
        lay.setSpacing(GAP)
        lay.setContentsMargins(GAP, GAP, GAP, GAP)
        lay.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        self.pages = []
        self._sel_page, self._sel_words, self._drag_words = -1, [], []
        for i in range(doc.page_count):
            w = PageWidget(self, i)
            lay.addWidget(w, 0, Qt.AlignmentFlag.AlignHCenter)
            self.pages.append(w)
        self.setWidget(container)
        self._hits, self._hit_index = [], -1
        self.set_mode(self.mode)
        self._apply_zoom()
        self.go_to_page(min(keep_page, doc.page_count - 1))
        if keep_scroll is not None:  # 형광펜·되돌리기 뒤에도 보던 자리 그대로
            self.verticalScrollBar().setValue(keep_scroll)
        if reopen is not None and self.pages:
            index, box, xref, values = reopen
            if index >= len(self.pages):  # 쓰던 쪽이 없어졌으면(쪽 지우기 등) 쓰던 글을 마지막 쪽에 새 상자로
                index, xref = len(self.pages) - 1, None
            if xref is not None and annots.annot_at_xref_text(doc, index, xref) is None:
                xref = None  # 고치던 상자가 되돌리기로 사라졌으면 새 상자로
            self.open_text_editor(index, box, xref, **values)

    # ── 도구 모드 ──
    def set_mode(self, mode: str) -> None:
        if mode not in MODES:
            raise ValueError(mode)
        if mode != self.mode:  # 창이 화면을 맞출 때마다 부르므로, 모드가 바뀔 때만 고른 글자를 푼다
            self.select_clear()
            self.finish_editor()  # 쓰던 글자 상자는 넣고 모드를 바꿈
        self.mode = mode
        for w in self.pages:
            w.setCursor(QCursor(MODE_CURSORS[mode]))
            w.drag_rect = None

    def _to_page(self, index: int, pt: QPointF) -> pymupdf.Point:
        return annots.to_page(self.doc[index], pt.x(), pt.y(), self.zoom)

    def page_highlight(self, index: int, rect: QRectF) -> None:
        if self.doc is None:
            return
        p0 = self._to_page(index, rect.topLeft())
        p1 = self._to_page(index, rect.bottomRight())
        self.highlightRequested.emit(index, pymupdf.Rect(p0, p1).normalize())

    def page_area(self, index: int, rect: QRectF, mode: str) -> None:
        """글자·상자·지우개로 끈 사각형을 돌리기 전 쪽 좌표로 바꿔 알린다."""
        if self.doc is None:
            return
        r = pymupdf.Rect(self._to_page(index, rect.topLeft()), self._to_page(index, rect.bottomRight())).normalize()
        {"text": self.textBoxRequested, "box": self.boxRequested, "erase": self.eraseAreaRequested}[mode].emit(index, r)

    # ── 쪽 위에서 바로 쓰는 글자 상자 ──
    def open_text_editor(self, index: int, box, xref: int | None = None, **values) -> None:
        """box(돌리기 전 쪽 좌표) 자리에 글 칸을 띄운다. values: text·size·align·color(없으면 마지막 값)."""
        if self.doc is None or not 0 <= index < len(self.pages):
            return
        self.finish_editor()
        ed = TextBoxEditor(self.pages[index], **values)
        self._editor, self._editor_at = ed, (index, pymupdf.Rect(box), xref)
        ed.committed.connect(lambda *v, e=ed: self._on_editor_done(e, v))
        ed.cancelled.connect(lambda e=ed: self._on_editor_done(e, None))
        ed.top_hint = lambda text, size, rows, i=index, b=pymupdf.Rect(box): self._editor_top(i, b, text, size, rows)
        ed.edit.undoAvailable.connect(lambda _: self.editorChanged.emit())
        ed.edit.redoAvailable.connect(lambda _: self.editorChanged.emit())
        self._place_editor()
        ed.show()
        self.editorChanged.emit()

    def _editor_top(self, index: int, box: pymupdf.Rect, text: str, size: int, rows=None) -> float | None:
        """그 글을 넣으면 글이 보이기 시작할 높이(화면 좌표). 못 넣는 글이면 None."""
        if not text.strip() or self.doc is None or self.doc.is_closed or index >= self.doc.page_count:
            return None
        page = self.doc[index]
        try:
            _, _, shown = annots._fixed_rect(page, box, text, size, rows)
        except Exception:
            return None
        return (shown * page.rotation_matrix).normalize().y0 * self.zoom

    def _place_editor(self) -> None:
        if self._editor is None:
            return
        index, box, _ = self._editor_at
        r, z = (box * self.doc[index].rotation_matrix).normalize(), self.zoom
        self._editor.place(QRectF(r.x0 * z, r.y0 * z, r.width * z, r.height * z), z)

    def _on_editor_done(self, ed, values) -> None:
        if ed is not self._editor:
            return
        index, box, xref = self._editor_at
        self._editor, self._editor_at = None, None
        self.editorChanged.emit()
        self.setFocus()
        if values is not None:
            text, size, align, color = values
            self.textCommitted.emit(index, {"box": box, "xref": xref, "text": text, "size": size,
                                            "align": align, "color": color, "rows": ed.rows})

    def finish_editor(self) -> bool:
        """쓰던 글자 상자가 있으면 넣고 True."""
        if self._editor is None:
            return False
        self._editor.commit()
        return True

    @property
    def editing_text(self) -> bool:
        return self._editor is not None

    def editor_can_undo(self) -> tuple[bool, bool]:
        """쓰는 칸 안에서 (되돌리기, 다시 하기)를 할 수 있나."""
        doc = self._editor.edit.document()
        return doc.isUndoAvailable(), doc.isRedoAvailable()

    def editor_undo(self, redo: bool) -> None:
        """쓰는 칸 안의 글을 되돌리거나 다시 한다(도구 막대·메뉴의 되돌리기를 쓰는 중에 눌렀을 때)."""
        if self._editor is not None:
            (self._editor.edit.redo if redo else self._editor.edit.undo)()
            self._editor.edit.setFocus()

    def _take_editor(self):
        """쓰던 칸을 조용히 닫고 (쪽, 자리, xref, 값)을 돌려준다. 없으면 None."""
        if self._editor is None:
            return None
        ed, (index, box, xref) = self._editor, self._editor_at
        self._editor, self._editor_at = None, None
        text, size, align, color = ed.values()
        ed.cancel()
        return index, box, xref, {"text": text, "size": size, "align": align, "color": color}

    # ── 글자 고르기·복사 ──
    def select_clear(self) -> None:
        if 0 <= self._sel_page < len(self.pages):
            self.pages[self._sel_page].sel_rects = []
            self.pages[self._sel_page].update()
        self._sel_page, self._sel_words = -1, []

    def select_start(self, index: int) -> None:
        self.select_clear()
        self._drag_words = text_select.page_words(self.doc[index]) if self.doc is not None else []
        self._drag_cells = None  # 표 칸은 실제로 끌기 시작할 때 찾는다(그냥 누를 때 느려지지 않게)
        self._sel_told = False

    def select_drag(self, index: int, press: QPointF, pos: QPointF) -> None:
        if self.doc is None or not 0 <= index < len(self.pages):
            return
        if (pos - press).manhattanLength() < 4:  # 그냥 누른 것은 고르기 아님
            return
        if self._drag_cells is None:
            if index not in self._cells:
                page = self.doc[index]
                self._cells[index] = ((text_select.page_cells(page), text_select.page_frame(page))
                                      if self._drag_words else ([], None))
            self._drag_cells = self._cells[index]
        cells, frame = self._drag_cells
        words = text_select.words_between(self._drag_words, self._to_page(index, press), self._to_page(index, pos),
                                          cells, frame)
        self._sel_page, self._sel_words = index, words
        self._sel_seq = next(_select_seq)
        self._update_select_overlay()
        if not self._sel_told:  # 끄는 동안 매번 알리지 않고 처음 한 번만
            self._sel_told = True
            self.textSelected.emit(len(words))

    def _update_select_overlay(self) -> None:
        """고른 낱말을 지금 배율·회전의 화면 사각형으로 다시 그린다(배율을 바꿔도 글자 위에 남게)."""
        if self.doc is None or not 0 <= self._sel_page < len(self.pages):
            return
        mat, z = self.doc[self._sel_page].rotation_matrix, self.zoom
        rects = []
        for w in self._sel_words:
            r = pymupdf.Rect(w[:4]) * mat
            rects.append(QRectF(r.x0 * z, r.y0 * z, r.width * z, r.height * z))
        self.pages[self._sel_page].sel_rects = rects
        self.pages[self._sel_page].update()

    @property
    def selection_seq(self) -> int:
        return self._sel_seq if self._sel_words else 0

    def selected_text(self) -> str:
        return text_select.words_text(self._sel_words)

    def copy_selection(self) -> bool:
        text = self.selected_text()
        if not text:
            return False
        QApplication.clipboard().setText(text)
        return True

    def page_click(self, index: int, pt: QPointF, kind: str) -> None:
        if self.doc is None:
            return
        sig = {"note": self.noteRequested, "text": self.textRequested, "box": self.boxClickRequested,
               "erase": self.eraseRequested, "open": self.noteOpenRequested}[kind]
        sig.emit(index, self._to_page(index, pt))

    def page_hover(self, index: int, pt: QPointF, global_pos) -> None:
        if self.doc is None:
            return
        info = annots.annot_at(self.doc, index, self._to_page(index, pt))
        if info and info["text"] and info["type"] != pymupdf.PDF_ANNOT_FREE_TEXT:  # 글자 상자는 이미 보임
            QToolTip.showText(global_pos, f"메모: {info['text']}\n(두 번 누르면 고치기)", self.pages[index])
        else:
            QToolTip.hideText()

    def rerender_page(self, index: int) -> None:
        """주석을 바꾼 쪽만 다시 그린다(문서 전체를 다시 만들지 않아 보던 자리가 그대로)."""
        if 0 <= index < len(self.pages):
            self.pages[index].pixmap = None
            self._render_timer.start()

    def _dpr(self) -> float:
        return self.devicePixelRatioF() or 1.0

    def _fit_zoom(self) -> float:
        if self.doc is None or self.doc.page_count == 0:
            return self.zoom
        r = self.doc[self._current].rect
        vw = self.viewport().width() - 2 * GAP - 4
        vh = self.viewport().height() - 2 * GAP - 4
        if self.fit_mode == "width":
            return max(MIN_ZOOM, min(MAX_ZOOM, vw / r.width))
        return max(MIN_ZOOM, min(MAX_ZOOM, min(vw / r.width, vh / r.height)))

    def _apply_zoom(self) -> None:
        if self.doc is None:
            return
        if self.fit_mode:
            self.zoom = self._fit_zoom()
        for page, w in zip(self.doc, self.pages):
            r = page.rect
            w.setFixedSize(int(r.width * self.zoom), int(r.height * self.zoom))
            w.pixmap = None
        self.widget().layout().activate()  # 쪽 위치를 바로 갱신해야 go_to_page가 맞는 곳으로 감
        self.widget().adjustSize()
        self._update_hit_overlays()
        self._update_select_overlay()
        self._place_editor()
        self.zoomChanged.emit(self.zoom)
        self._render_timer.start()

    def set_zoom(self, zoom: float) -> None:
        page = self._current
        self.fit_mode = None
        self.zoom = max(MIN_ZOOM, min(MAX_ZOOM, zoom))
        self._apply_zoom()
        self.go_to_page(page)

    def zoom_in(self):
        self.set_zoom(self.zoom * 1.25)

    def zoom_out(self):
        self.set_zoom(self.zoom / 1.25)

    def fit(self, mode: str) -> None:
        page = self._current
        self.fit_mode = mode
        self._apply_zoom()
        self.go_to_page(page)

    # ── 이동 ──
    @property
    def current_page(self) -> int:
        return self._current

    def go_to_page(self, n: int) -> None:
        if not self.pages:
            return
        n = max(0, min(n, len(self.pages) - 1))
        self.verticalScrollBar().setValue(self.pages[n].y() - GAP)
        self._set_current(n)
        self._render_timer.start()

    def next_page(self):
        self.go_to_page(self._current + 1)

    def prev_page(self):
        self.go_to_page(self._current - 1)

    def _set_current(self, n: int) -> None:
        if n != self._current:
            self._current = n
            self.currentPageChanged.emit(n)

    def _on_scroll(self, _):
        if not self.pages:
            return
        mid = self.verticalScrollBar().value() + self.viewport().height() // 3
        for i, w in enumerate(self.pages):
            if w.y() + w.height() + GAP > mid:
                self._set_current(i)
                break
        self._render_timer.start()

    # ── 그리기 ──
    def _visible_range(self) -> tuple[int, int]:
        top = self.verticalScrollBar().value()
        bottom = top + self.viewport().height()
        idx = [i for i, w in enumerate(self.pages) if w.y() < bottom and w.y() + w.height() > top]
        if not idx:
            return self._current, self._current
        return idx[0], idx[-1]

    def _render_visible(self) -> None:
        if self.doc is None:
            return
        first, last = self._visible_range()
        lo, hi = first - KEEP_AROUND, last + KEEP_AROUND
        scale = self.zoom * self._dpr()
        for i, w in enumerate(self.pages):
            if lo <= i <= hi:
                if w.pixmap is None:
                    w.pixmap = page_pixmap(self.doc[i], scale)
                    w.update()
            elif w.pixmap is not None:
                w.pixmap = None  # 멀리 있는 쪽 그림은 버려 메모리를 아낌

    def resizeEvent(self, e):
        super().resizeEvent(e)
        if self.fit_mode and self.doc is not None:
            page = self._current
            self._apply_zoom()
            self.go_to_page(page)

    def wheelEvent(self, e):
        if e.modifiers() & Qt.KeyboardModifier.ControlModifier:
            self.zoom_in() if e.angleDelta().y() > 0 else self.zoom_out()
            e.accept()
            return
        super().wheelEvent(e)

    # ── 찾기 표시 ──
    def set_hits(self, hits) -> None:
        self._hits = list(hits)
        self._hit_index = -1
        self._update_hit_overlays()

    def show_hit(self, index: int) -> None:
        if not self._hits:
            return
        self._hit_index = index % len(self._hits)
        pno, r = self._hits[self._hit_index]
        self._update_hit_overlays()
        w = self.pages[pno]
        self.verticalScrollBar().setValue(int(w.y() + r.y0 * self.zoom - self.viewport().height() / 3))
        self._set_current(pno)

    def _update_hit_overlays(self) -> None:
        z = self.zoom
        for w in self.pages:
            w.hits, w.current_hit = [], None
        for i, (pno, r) in enumerate(self._hits):
            if pno >= len(self.pages):
                continue
            q = QRectF(r.x0 * z, r.y0 * z, r.width * z, r.height * z)
            self.pages[pno].hits.append(q)
            if i == self._hit_index:
                self.pages[pno].current_hit = q
        for w in self.pages:
            w.update()
