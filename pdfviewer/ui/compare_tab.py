"""두 판 나란히 보기: 읽기 전용, '같이 넘기기'를 켜면 같은 쪽 번호로 함께 움직인다."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QCheckBox, QHBoxLayout, QLabel, QSplitter, QVBoxLayout, QWidget

from ..core.document import Document
from .page_view import PageView


class CompareTab(QWidget):
    def __init__(self, left: Document, right: Document, parent=None):
        super().__init__(parent)
        self.docs = (left, right)
        self.views = (PageView(), PageView())
        self._syncing = False
        top = QHBoxLayout()
        top.setContentsMargins(6, 4, 6, 4)
        self.labels = []
        for d in self.docs:
            lab = QLabel(f"{d.name}  ({d.page_count}쪽)")
            lab.setToolTip(d.path)
            lab.setStyleSheet("font-weight: bold;")
            self.labels.append(lab)
        self.sync = QCheckBox("같이 넘기기")
        self.sync.setChecked(True)
        top.addWidget(self.labels[0], 1)
        top.addWidget(self.sync)
        top.addWidget(self.labels[1], 1)
        split = QSplitter(Qt.Orientation.Horizontal)
        for v, d in zip(self.views, self.docs):
            split.addWidget(v)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)
        lay.addLayout(top)
        lay.addWidget(split, 1)
        for v, d in zip(self.views, self.docs):
            v.fit_mode = "page"
            v.set_document(d.doc)
        self.views[0].currentPageChanged.connect(lambda n: self._follow(1, n))
        self.views[1].currentPageChanged.connect(lambda n: self._follow(0, n))
        self.sync.toggled.connect(self._on_sync_toggled)

    def _on_sync_toggled(self, on: bool) -> None:
        if on:  # 다시 켜면 오른쪽을 왼쪽과 같은 쪽으로 맞춘다
            self._follow(1, self.views[0].current_page)

    @property
    def title(self) -> str:
        return f"비교: {self.docs[0].name} ↔ {self.docs[1].name}"

    def _follow(self, other: int, n: int) -> None:
        if self._syncing or not self.sync.isChecked():
            return
        v = self.views[other]
        if n < len(v.pages) and v.current_page != n:
            self._syncing = True
            try:
                v.go_to_page(n)
            finally:
                self._syncing = False

    def close_docs(self) -> None:
        for d in self.docs:
            if not d.doc.is_closed:
                d.close()
