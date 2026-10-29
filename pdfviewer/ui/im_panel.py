"""왼쪽 '자료 폴더' 창: 건물 폴더별 PDF 목록(최신 rev 굵게·맨 위), 두 번 눌러 열기, 두 판 비교."""
from __future__ import annotations

import os

from PySide6.QtCore import QSettings, Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QAbstractItemView, QDockWidget, QFileDialog, QHBoxLayout, QLabel, QMenu, QPushButton,
    QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget,
)

from ..core import im_index
from ..i18n import tr

ROLE_DOC = Qt.ItemDataRole.UserRole + 1
ROLE_GROUP = Qt.ItemDataRole.UserRole + 2


class ImPanel(QDockWidget):
    openRequested = Signal(str)
    compareRequested = Signal(str, str)  # 왼쪽(예전 판), 오른쪽(새 판)

    def __init__(self, settings: QSettings, parent=None):
        super().__init__(tr("자료 폴더"), parent)
        self.settings = settings
        self.setObjectName("im_panel")
        self.setFeatures(QDockWidget.DockWidgetFeature.DockWidgetClosable)
        body = QWidget()
        lay = QVBoxLayout(body)
        lay.setContentsMargins(4, 4, 4, 4)
        self.root_label = QLabel()
        self.root_label.setWordWrap(True)
        self.root_label.setStyleSheet("color: #555;")
        btns = QHBoxLayout()
        self.pick_btn = QPushButton(tr("폴더 바꾸기…"))
        self.reload_btn = QPushButton(tr("새로 고침"))
        self.compare_btn = QPushButton(tr("고른 두 파일 비교"))
        for b in (self.pick_btn, self.reload_btn):
            btns.addWidget(b)
        self.tree = QTreeWidget()
        self.tree.setHeaderHidden(True)
        self.tree.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.tree.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.tree.setToolTip(tr("두 번 누르면 열기 · Ctrl로 두 파일을 골라 '비교' · 오른쪽 클릭: 이전 rev와 비교"))
        lay.addWidget(self.root_label)
        lay.addLayout(btns)
        lay.addWidget(self.tree, 1)
        lay.addWidget(self.compare_btn)
        self.setWidget(body)
        self.setMinimumWidth(240)
        self.pick_btn.clicked.connect(self.pick_root)
        self.reload_btn.clicked.connect(self.reload)
        self.compare_btn.clicked.connect(self._compare_selected)
        self.tree.itemDoubleClicked.connect(self._open_item)
        self.tree.customContextMenuRequested.connect(self._menu)
        self.root = self.settings.value("im_root", "") or im_index.find_root() or ""
        self.reload()

    # ── 목록 ──
    def reload(self) -> None:
        self.tree.clear()
        if not self.root or not os.path.isdir(self.root):
            self.root_label.setText(tr("자료 폴더를 찾지 못했습니다.\n'폴더 바꾸기…'로 브리핑·매각자료 폴더를 골라 주세요."))
            return
        self.root_label.setText(self.root)
        bold = QFont()
        bold.setBold(True)
        for g in im_index.scan(self.root):
            top = QTreeWidgetItem([g["name"]])
            top.setData(0, ROLE_GROUP, g["docs"])
            top.setFlags(top.flags() & ~Qt.ItemFlag.ItemIsSelectable)
            for d in g["docs"]:
                label = (tr("★ 최신  ") if d["latest"] else "") + d["name"]
                it = QTreeWidgetItem([label])
                it.setData(0, ROLE_DOC, d)
                it.setToolTip(0, d["path"])
                if d["latest"]:
                    it.setFont(0, bold)
                top.addChild(it)
            self.tree.addTopLevelItem(top)
        self.tree.expandAll()

    def pick_root(self) -> None:
        start = self.root or os.path.expanduser("~")
        path = QFileDialog.getExistingDirectory(self, tr("브리핑·매각자료 폴더 고르기"), start)
        if path:
            self.set_root(path)

    def set_root(self, path: str) -> None:
        self.root = path
        self.settings.setValue("im_root", path)
        self.reload()

    # ── 열기·비교 ──
    def _doc(self, it) -> dict | None:
        return it.data(0, ROLE_DOC) if it is not None else None

    def _open_item(self, it, _col=0) -> None:
        d = self._doc(it)
        if d:
            self.openRequested.emit(d["path"])

    def selected_docs(self) -> list[dict]:
        return [d for d in (self._doc(it) for it in self.tree.selectedItems()) if d]

    def _compare_selected(self) -> None:
        docs = self.selected_docs()
        if len(docs) != 2:
            self.root_label.setText(tr("비교할 파일을 Ctrl을 누른 채 두 개 골라 주세요."))
            return
        a, b = sorted(docs, key=lambda d: (d["rev"] is None, d["rev"] or 0, d["name"]))
        self.compareRequested.emit(a["path"], b["path"])

    def _menu(self, pos) -> None:
        it = self.tree.itemAt(pos)
        d = self._doc(it)
        if d is None:
            return
        m = QMenu(self)
        m.addAction(tr("열기")).triggered.connect(lambda: self.openRequested.emit(d["path"]))
        prev = im_index.previous_rev(it.parent().data(0, ROLE_GROUP), d)
        act = m.addAction(tr("이전 rev와 비교({name})").format(name=prev['name']) if prev else tr("이전 rev 없음"))
        act.setEnabled(prev is not None)
        if prev:
            act.triggered.connect(lambda: self.compareRequested.emit(prev["path"], d["path"]))
        m.exec(self.tree.viewport().mapToGlobal(pos))
