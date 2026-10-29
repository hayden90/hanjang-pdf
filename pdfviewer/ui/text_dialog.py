"""글자 상자 넣기·고치기 창: 글자, 크기, 정렬, 색을 고른다."""
from __future__ import annotations

from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QButtonGroup, QColorDialog, QComboBox, QDialog, QDialogButtonBox, QHBoxLayout, QLabel,
    QPlainTextEdit, QPushButton, QSpinBox, QVBoxLayout,
)

from ..core.annots import TEXT_MAX, TEXT_MIN

ALIGN_LABELS = (("left", "왼쪽"), ("center", "가운데"), ("right", "오른쪽"))
PRESET_COLORS = (("#000000", "검정"), ("#d00000", "빨강"), ("#0050c8", "파랑"), ("#138a36", "초록"), ("#e8590c", "주황"))

# 마지막에 고른 크기·정렬·색(다음 글자 상자의 기본값)
last_style = {"size": 12, "align": "left", "color": "#000000"}


class TextDialog(QDialog):
    def __init__(self, parent=None, title="글자 넣기", text="", size=None, align=None, color=None):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.color = color or last_style["color"]
        self.edit = QPlainTextEdit(text)
        self.edit.setPlaceholderText("넣을 글자 (Enter로 줄 바꿈)")
        self.size = QSpinBox(minimum=TEXT_MIN, maximum=TEXT_MAX, suffix=" pt")
        self.size.setValue(size or last_style["size"])
        self.align = QComboBox()
        for key, label in ALIGN_LABELS:
            self.align.addItem(label, key)
        self.align.setCurrentIndex([k for k, _ in ALIGN_LABELS].index(align or last_style["align"]))
        self.align.setToolTip("왼쪽: 누른 곳에서 시작 / 가운데: 누른 곳이 가운데 / 오른쪽: 누른 곳에서 끝남")

        row = QHBoxLayout()
        row.addWidget(QLabel("크기"))
        row.addWidget(self.size)
        row.addSpacing(12)
        row.addWidget(QLabel("정렬"))
        row.addWidget(self.align)
        row.addStretch(1)

        crow = QHBoxLayout()
        crow.addWidget(QLabel("색"))
        self.color_group = QButtonGroup(self)
        self.color_buttons = {}
        for hexv, label in PRESET_COLORS:
            b = QPushButton(label)
            b.setCheckable(True)
            b.setStyleSheet(f"QPushButton {{ color: {hexv}; font-weight: bold; }}")
            b.clicked.connect(lambda _=False, h=hexv: self._set_color(h))
            self.color_group.addButton(b)
            self.color_buttons[hexv] = b
            crow.addWidget(b)
        self.more = QPushButton("다른 색…")
        self.more.clicked.connect(self._pick_color)
        crow.addWidget(self.more)
        self.swatch = QLabel()
        self.swatch.setFixedSize(22, 22)
        crow.addWidget(self.swatch)
        crow.addStretch(1)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("넣기")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("취소")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        lay = QVBoxLayout(self)
        lay.addWidget(self.edit, 1)
        lay.addLayout(row)
        lay.addLayout(crow)
        lay.addWidget(buttons)
        self.resize(460, 280)
        self._set_color(self.color)
        self.edit.setFocus()

    def _set_color(self, hexv: str) -> None:
        self.color = hexv.lower()
        for h, b in self.color_buttons.items():
            b.setChecked(h == self.color)
        self.swatch.setStyleSheet(f"background: {self.color}; border: 1px solid #888;")

    def _pick_color(self) -> None:
        c = QColorDialog.getColor(QColor(self.color), self, "글자 색")
        if c.isValid():
            self._set_color(c.name())

    def values(self) -> tuple[str, int, str, str]:
        return self.edit.toPlainText(), self.size.value(), self.align.currentData(), self.color


def ask_text(parent, title="글자 넣기", **current) -> tuple[str, int, str, str] | None:
    """창을 띄워 (글자, 크기, 정렬, 색)을 받는다. 취소하거나 글자가 비면 None."""
    dlg = TextDialog(parent, title, **current)
    if dlg.exec() != QDialog.DialogCode.Accepted:
        return None
    text, size, align, color = dlg.values()
    if not text.strip():
        return None
    last_style.update(size=size, align=align, color=color)
    return text, size, align, color
