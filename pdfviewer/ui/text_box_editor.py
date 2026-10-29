"""쪽 위에서 바로 쓰는 글자 상자: 끌어 정한 상자 자리에 글 칸을 띄우고, 위에 크기·정렬·색 막대를 붙인다.

넣기(Ctrl+Enter 또는 '넣기' 단추, 상자 밖을 누름)하면 committed, Esc·'취소'면 cancelled를 낸다.
상자 자리(rect)는 화면(쪽 위젯) 좌표이고, 배율이 바뀌면 PageView가 place()로 다시 놓는다.
"""
from __future__ import annotations

from PySide6.QtCore import QEvent, QObject, QRectF, Qt, Signal
import pymupdf
from PySide6.QtGui import QColor, QFont, QFontDatabase, QFontMetricsF, QTextBlockFormat, QTextCursor, QTextOption
from PySide6.QtWidgets import (
    QColorDialog, QComboBox, QHBoxLayout, QMenu, QPushButton, QSpinBox, QTextEdit, QToolButton, QWidget,
)

from ..core.annots import BOX_LEAD, TEXT_MAX, TEXT_MIN, box_min_width, box_need_height
from .text_dialog import ALIGN_LABELS, PRESET_COLORS, last_style
from ..i18n import tr

_QT_ALIGN = {"left": Qt.AlignmentFlag.AlignLeft, "center": Qt.AlignmentFlag.AlignHCenter,
             "right": Qt.AlignmentFlag.AlignRight}
BAR_GAP = 4
_families: list[str] | None = None


def _pdf_fonts() -> list[str]:
    """쓰는 칸 글꼴: MuPDF가 글자 상자를 그릴 때와 같은 글자 폭이 되게 고른다.

    한글은 MuPDF의 cjk 글꼴(Droid Sans Fallback)을 그대로 Qt에 올려 쓴다(Qt 기본 한글 글꼴은
    약 13% 좁아 줄 바뀌는 곳이 달라짐). 라틴 글자는 MuPDF의 Nimbus Sans와 폭이 같은 Arial·Helvetica를
    쓴다(Nimbus 파일은 Qt가 그리지 못하는 형식).
    """
    global _families
    if _families is None:
        fid = QFontDatabase.addApplicationFontFromData(pymupdf.Font("cjk").buffer)
        cjk = QFontDatabase.applicationFontFamilies(fid) if fid >= 0 else []
        _families = ["Arial", "Helvetica"] + cjk
    return _families


_BAR_STYLE = (
    "QWidget { background: #f4f4f4; color: #202020; }"
    " QSpinBox, QComboBox { background: #ffffff; color: #202020; border: 1px solid #a0a0a0; padding: 1px 4px; }"
    " QComboBox QAbstractItemView { background: #ffffff; color: #202020;"
    " selection-background-color: #cfe0fc; selection-color: #000000; }"
    " QPushButton, QToolButton { background: #ffffff; color: #202020; border: 1px solid #a0a0a0;"
    " border-radius: 3px; padding: 2px 6px; }"
    " QPushButton:hover, QToolButton:hover { background: #e6efff; }"
    " QMenu { background: #ffffff; color: #202020; border: 1px solid #a0a0a0; }"
    " QMenu::item { padding: 3px 16px; } QMenu::item:selected { background: #cfe0fc; color: #000000; }"
    " QMenu::separator { height: 1px; background: #d0d0d0; margin: 3px 6px; }"
)


class _Edit(QTextEdit):
    submit = Signal()
    cancel = Signal()

    def set_side_margins(self, left: float, right: float) -> None:
        """글 칸 안 좌우 여백(문서 서식이 아니라 칸 자체에 두어 되돌리기 기록에 남지 않게)."""
        self.setViewportMargins(int(round(left)), 0, int(round(right)), 0)

    def event(self, e):
        # 창의 Esc(보기 모드로) 단축키보다 먼저 받아 '취소'로 쓴다
        if e.type() == QEvent.Type.ShortcutOverride and e.key() == Qt.Key.Key_Escape:
            e.accept()
            return True
        return super().event(e)

    def keyPressEvent(self, e):
        if e.key() == Qt.Key.Key_Escape:
            self.cancel.emit()
            return
        if e.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter) and e.modifiers() & Qt.KeyboardModifier.ControlModifier:
            self.submit.emit()
            return
        super().keyPressEvent(e)


class TextBoxEditor(QObject):
    committed = Signal(str, int, str, str)  # 글자, 크기, 정렬, 색
    cancelled = Signal()

    def __init__(self, page_widget: QWidget, text: str = "", size=None, align=None, color=None):
        super().__init__(page_widget)
        self.host = page_widget
        self.zoom = 1.0
        self.box = QRectF()
        self.color = color or last_style["color"]
        self._done = False
        # (글, 크기) → 넣었을 때 글이 보이기 시작할 높이(쪽 위젯 좌표). PageView가 실제 배치로 알려 준다
        self.top_hint = None
        self._top_key, self._top = None, None
        self.rows: list[str] | None = None  # 넣을 때 화면에 보이던 줄들(넣는 쪽이 같은 줄로 넣게)

        self.edit = _Edit(page_widget)
        self.edit.setAcceptRichText(False)
        self._styled = None  # 줄 높이를 마지막으로 맞춘 (크기, 배율)
        self.edit.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.edit.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.edit.setPlaceholderText(tr("여기에 글자를 쓰세요 (Ctrl+Enter: 넣기, Esc: 취소)"))
        self.edit.setPlainText(text)
        self.edit.submit.connect(self.commit)
        self.edit.cancel.connect(self.cancel)
        self.edit.textChanged.connect(self._fit_height)
        self.edit.document().documentLayout().documentSizeChanged.connect(lambda _: self._fit_height())

        self.bar = QWidget(page_widget)
        self.bar.setAutoFillBackground(True)
        # 윈도우 어두운 테마면 글자가 흰색이 되어 밝은 막대 위에서 안 보이므로 글자·바탕색을 모두 정해 둔다
        self.bar.setStyleSheet(_BAR_STYLE)
        lay = QHBoxLayout(self.bar)
        lay.setContentsMargins(4, 2, 4, 2)
        lay.setSpacing(4)
        self.size = QSpinBox(minimum=TEXT_MIN, maximum=TEXT_MAX, suffix=" pt")
        self.size.setValue(size or last_style["size"])
        self.size.valueChanged.connect(self._restyle)
        self.align = QComboBox()
        for key, label in ALIGN_LABELS:
            self.align.addItem(label, key)
        self.align.setCurrentIndex([k for k, _ in ALIGN_LABELS].index(align or last_style["align"]))
        self.align.currentIndexChanged.connect(self._restyle)
        self.color_btn = QToolButton(text=tr("색"))
        self.color_btn.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        menu = QMenu(self.color_btn)
        menu.setStyleSheet(_BAR_STYLE)
        for hexv, label in PRESET_COLORS:
            menu.addAction(label).triggered.connect(lambda _=False, h=hexv: self.set_color(h))
        menu.addSeparator()
        menu.addAction(tr("다른 색…")).triggered.connect(self._pick_color)
        self.color_btn.setMenu(menu)
        self.ok = QPushButton(tr("넣기"))
        self.ok.clicked.connect(self.commit)
        self.no = QPushButton(tr("취소"))
        self.no.clicked.connect(self.cancel)
        for w in (self.size, self.align, self.color_btn, self.ok, self.no):
            lay.addWidget(w)
        self.bar.adjustSize()
        self._restyle()

    # ── 모양 ──
    def set_color(self, hexv: str) -> None:
        self.color = hexv.lower()
        self._restyle()

    def _pick_color(self) -> None:
        c = QColorDialog.getColor(QColor(self.color), self.host, tr("글자 색"))
        if c.isValid():
            self.set_color(c.name())

    def _restyle(self) -> None:
        size = self.size.value()
        # 스타일시트를 먼저 두고 글꼴을 나중에 정한다(반대로 하면 스타일시트가 글꼴을 기본값으로 되돌림)
        self.edit.setStyleSheet(
            f"QTextEdit {{ color: {self.color}; background: rgba(255,255,255,235);"
            " border: 1px dashed #2a6fdb; }")
        # '색' 단추 글자는 고른 색으로 보여 주되, 흰색처럼 밝은 색이면 바탕을 어둡게
        c = QColor(self.color)
        light = 0.299 * c.red() + 0.587 * c.green() + 0.114 * c.blue() > 150
        self.color_btn.setStyleSheet(
            f"QToolButton {{ color: {self.color}; font-weight: bold; padding: 2px 6px;"
            f" background: {'#404040' if light else '#ffffff'}; border: 1px solid #a0a0a0; border-radius: 3px; }}"
            " QToolButton::menu-indicator { width: 0; }")
        f = QFont()
        if _pdf_fonts():
            f.setFamilies(_pdf_fonts())
        # Qt는 글꼴을 정수 픽셀로 반올림하므로(2.5px→3px면 20% 넓음) 남는 비율은 글자 간격(%)으로 맞춘다
        want = size * self.zoom
        px = max(1, round(want))
        f.setPixelSize(px)
        if px != want:
            f.setLetterSpacing(QFont.SpacingType.PercentageSpacing, want / px * 100)
        f.setHintingPreference(QFont.HintingPreference.PreferNoHinting)  # 글자 폭이 크기에 곧게 비례하게
        f.setKerning(False)  # MuPDF 결과는 글자 쌍 간격(To·AV 등)을 줄이지 않으므로 미리보기도 줄이지 않음
        self.edit.setFont(f)
        doc = self.edit.document()
        doc.setDefaultFont(f)
        doc.setUseDesignMetrics(True)  # 화면 픽셀에 맞춘 폭이 아니라 글꼴 본래 폭으로 줄을 배치
        # 정렬·줄바꿈은 문서의 기본 글 옵션으로(되돌리기 기록에 남지 않음)
        opt = QTextOption(_QT_ALIGN[self.align.currentData()])
        opt.setWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)  # 띄어쓰기 없는 긴 글도
        doc.setDefaultTextOption(opt)
        if self._styled != (size, self.zoom):
            self._styled = (size, self.zoom)
            self._set_line_height(size)
        self.place(self.box, self.zoom)

    def _set_line_height(self, size: int) -> None:
        """줄 간격을 넣은 결과(MuPDF: 크기의 1.2배)와 같게. 줄 서식은 되돌리기 기록에 남으므로
        칸을 열 때와 크기·배율이 바뀔 때만 맞추고, 그때 칸 안 되돌리기 기록은 비운다
        (서식 단계가 섞이면 되돌리기 단추가 헛눌림)."""
        doc = self.edit.document()
        doc.setDocumentMargin(max(1.0, size * 0.08 * self.zoom))
        cur = QTextCursor(doc)
        cur.select(QTextCursor.SelectionType.Document)
        fmt = QTextBlockFormat()
        fmt.setLineHeight(size * 1.2 * self.zoom, QTextBlockFormat.LineHeightTypes.FixedHeight.value)
        cur.mergeBlockFormat(fmt)
        doc.setUndoRedoEnabled(False)
        doc.setUndoRedoEnabled(True)

    def place(self, box: QRectF, zoom: float) -> None:
        """상자 자리(쪽 위젯 좌표)와 배율에 맞춰 글 칸·막대를 놓는다."""
        changed = zoom != self.zoom
        self.box, self.zoom = QRectF(box), zoom
        if changed:
            self._restyle()
            return
        # 너무 좁게 끈 상자는 실제로도 최소 폭으로 넓어지므로 미리 그 폭으로 보여 준다
        w = max(self.box.width(), box_min_width(self.size.value()) * zoom)
        x = min(max(self.box.x(), 0), max(self.host.width() - w, 0))
        self.edit.setGeometry(int(x), int(self.box.y()), int(w), int(max(self.box.height(), 20)))
        self._set_wrap(w)
        self._fit_height()
        self._place_bar()

    def _place_bar(self) -> None:
        """막대는 글 칸 바로 위에(자리가 없으면 아래에)."""
        g = self.edit.geometry()
        bh, bw = self.bar.sizeHint().height(), self.bar.sizeHint().width()
        by = g.y() - bh - BAR_GAP
        if by < 0:  # 위에 자리가 없으면 상자 아래에
            by = g.bottom() + BAR_GAP
        bx = min(max(g.x(), 0), max(self.host.width() - bw, 0))
        self.bar.setGeometry(int(bx), int(by), bw, bh)

    def _set_wrap(self, w: float) -> None:
        """실제 상자와 같은 폭에서 줄을 바꾸고(annots._layout: 안쪽 폭 = 폭 - (크기+8), 글자 폭 1.15배로 잼),
        남는 폭은 정렬 쪽 반대편에 둔다(가운데면 양쪽 반씩) — MuPDF가 상자 전체 폭에서 정렬하므로."""
        size, z = self.size.value(), self.zoom
        # annots._layout과 같은 식(안쪽 폭 = 폭 - (크기+8), 적어도 크기의 2배). 화면 픽셀 하한을 두면
        # 낮은 배율의 좁은 상자에서 줄이 넣는 쪽보다 길어져 다시 나뉘므로 쪽 단위로만 계산한다
        wrap = max(w / z - size - 8, size * 2) / 1.15 * z
        spare = max(0.0, w - wrap - 2 * self.edit.document().documentMargin() - 2)
        left = {"left": 0.0, "center": spare / 2, "right": spare}[self.align.currentData()]
        self.edit.set_side_margins(left, spare - left)

    def _fit_height(self) -> None:
        """글이 늘면 칸도 아래로 늘린다(실제 상자도 아래로 늘어남). 칸 안에서 위로 밀려 가려지지 않게."""
        need = self.edit.document().size().height() + 4
        g = self.edit.geometry()
        h = int(max(self.box.height(), need, 20))
        # 쪽 아래 끝이라 실제 상자가 위로 밀려 들어가면(annots._fixed_rect) 칸도 같은 높이로 올려 보여 준다
        y = self.box.y()
        # 실제 배치 계산은 무거우므로(긴 글이면 키마다 수십 ms) 넣을 상자가 쪽 아래 끝에 닿을 때만 묻는다.
        # 넣을 상자는 줄마다 미리보기 칸보다 조금 더 높으므로 칸이 아니라 넣을 상자 높이로 판단한다
        doc = self.edit.document()
        lines = sum(doc.findBlockByNumber(i).layout().lineCount() for i in range(doc.blockCount()))
        result_h = (box_need_height(self.size.value(), lines) - BOX_LEAD) * self.zoom
        if self.top_hint is not None and self.box.y() + max(h, result_h) > self.host.height() - 1:
            key = (self.edit.toPlainText(), self.size.value(), h)
            if key != self._top_key:
                self._top_key = key
                self._top = self.top_hint(key[0], key[1], self.visual_rows())
            if self._top is not None:
                y = min(y, self._top)
        y = int(round(max(0, y) + self._baseline_shift()))
        if (g.y(), g.height()) != (y, h):
            self.edit.setGeometry(g.x(), y, g.width(), h)
            self._place_bar()
        self.edit.verticalScrollBar().setValue(0)

    def _baseline_shift(self) -> float:
        """첫 줄 기준선을 넣은 결과와 같은 높이에 두려고 칸을 옮길 양(화면 픽셀).

        결과(MuPDF)의 첫 기준선은 글이 보이는 자리 위에서 2.8 + 0.9×크기 pt 아래다
        (숨은 12pt 줄의 아래 몫 + 새 줄의 위 몫). 칸 안 첫 기준선은 테두리·여백·줄 위치로 잰다.
        칸 자체를 옮기므로 글 서식·되돌리기 기록은 건드리지 않는다.
        """
        doc = self.edit.document()
        lay = doc.firstBlock().layout()
        if lay is not None and lay.lineCount():
            line = lay.lineAt(0)
            inner = line.y() + line.ascent()
        else:
            inner = QFontMetricsF(self.edit.font()).ascent()
        have = self.edit.viewport().geometry().y() + doc.documentMargin() + inner
        want = (2.8 + 0.9 * self.size.value()) * self.zoom
        return want - have

    def show(self) -> None:
        self.edit.show()
        self.bar.show()
        self.bar.raise_()
        self.edit.raise_()
        self.edit.setFocus()
        self.edit.moveCursor(QTextCursor.MoveOperation.End)

    # ── 끝내기 ──
    def visual_rows(self) -> list[str]:
        """글 칸이 지금 화면에 그린 줄들(자동 줄바꿈 포함). 줄 끝에서 꺾인 띄어쓰기는 뺀다."""
        rows = []
        block = self.edit.document().firstBlock()
        while block.isValid():
            text, lay = block.text(), block.layout()
            n = lay.lineCount() if lay is not None else 0
            if n == 0:
                rows.append(text)
            for i in range(n):
                line = lay.lineAt(i)
                part = text[line.textStart():line.textStart() + line.textLength()]
                rows.append(part.rstrip(" ") if i < n - 1 else part)
            block = block.next()
        return rows

    def values(self) -> tuple[str, int, str, str]:
        return self.edit.toPlainText(), self.size.value(), self.align.currentData(), self.color

    def commit(self) -> None:
        if self._done:
            return
        self._done = True
        text, size, align, color = self.values()
        self.rows = self.visual_rows()
        last_style.update(size=size, align=align, color=color)
        self._close()
        if text.strip():
            self.committed.emit(text, size, align, color)
        else:
            self.cancelled.emit()

    def cancel(self) -> None:
        if self._done:
            return
        self._done = True
        self._close()
        self.cancelled.emit()

    def _close(self) -> None:
        for w in (self.edit, self.bar):
            w.hide()
            w.deleteLater()
