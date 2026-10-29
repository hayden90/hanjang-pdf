"""주 창: 탭, 메뉴·도구 막대, 파일 끌어다 열기, 닫을 때 저장 확인, 자료 폴더 창·두 판 비교."""
from __future__ import annotations

import os

from PySide6.QtCore import QSettings, Qt, QUrl
from PySide6.QtGui import QAction, QActionGroup, QColor, QDesktopServices, QIntValidator, QKeySequence
from PySide6.QtWidgets import (
    QColorDialog, QFileDialog, QLabel, QLineEdit, QMainWindow, QMenu, QMessageBox, QTabWidget, QToolBar, QToolButton,
)

from ..core.doc_ops import DocOpError
from ..core.document import Document, same_file
from ..core import doc_ops
from .compare_tab import CompareTab
from .doc_tab import DocTab, ask_password
from .im_panel import ImPanel
from .page_view import select_hint
from .thumbs import pdf_paths_from_mime
from .. import i18n
from ..i18n import tr

APP_NAME = tr("한장PDF")
APP_VERSION = "rev13"
SOURCE_URL = "https://github.com/hayden90/hanjang-pdf"  # AGPL: 받은 사람이 소스를 받을 수 있는 곳
BUBIGTER_URL = "https://www.bubigter.com/?utm_source=hanjangpdf&utm_medium=app"
ABOUT_TEXT = (
    f"<b>{APP_NAME}</b> {APP_VERSION}<br>" + tr("서류를 보기 좋게 만든 PDF 뷰어") + "<br><br>"
    + tr("이 프로그램은 자유 소프트웨어입니다. GNU AGPL v3 조건으로 다시 배포하거나 고칠 수 있으며,"
         " 어떤 보증도 하지 않습니다.") + "<br>"
    + tr("소스 코드") + f": <a href=\"{SOURCE_URL}\">{SOURCE_URL}</a><br><br>"
    + tr("쓰는 부품: PyMuPDF·MuPDF(AGPL v3, Artifex), Qt·PySide6(LGPL v3), pywinrt(MIT)") + "<br>"
    + tr("문서 내용은 PC 밖으로 보내지 않습니다(글자 인식도 윈도우 안에서만).")
)


def open_url(url: str) -> None:
    """기본 브라우저로 열기(시험에서 바꿔 끼움)."""
    QDesktopServices.openUrl(QUrl(url))


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        ini = os.environ.get("PDFV_SETTINGS")  # 시험용: 실제 사용자 설정과 섞이지 않게 따로 둔 파일
        self.settings = QSettings(ini, QSettings.Format.IniFormat) if ini else QSettings("HighStar", "PDFViewer")
        self.setWindowTitle(APP_NAME)
        self.resize(1200, 860)
        self.setAcceptDrops(True)
        self.tabs = QTabWidget(tabsClosable=True, movable=True, documentMode=True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.tabs.currentChanged.connect(lambda _: self._sync())
        self.setCentralWidget(self.tabs)
        self.page_box = QLineEdit(fixedWidth=48, alignment=Qt.AlignmentFlag.AlignCenter)
        self.page_box.setValidator(QIntValidator(1, 99999))
        self.page_box.returnPressed.connect(self._goto_typed_page)
        self.page_total = QLabel(" / 0 ")
        self.zoom_label = QLabel("")
        self.im_panel = ImPanel(self.settings, self)
        self.addDockWidget(Qt.DockWidgetArea.LeftDockWidgetArea, self.im_panel)
        self.im_panel.openRequested.connect(self.open_path)
        self.im_panel.compareRequested.connect(self.open_compare)
        self.im_panel.setVisible(bool(self.im_panel.root))
        self._build_actions()
        self.statusBar().addPermanentWidget(self.zoom_label)
        self._show_empty_hint()

    def choose_lang(self, lang: str):
        """고른 언어를 저장한다. 화면은 다시 켤 때 바뀐다."""
        i18n.save_lang(lang, self.settings)
        if lang != i18n.LANG:
            QMessageBox.information(self, "Language / 언어",
                                    "다시 시작하면 한국어로 바뀝니다." if lang == "ko"
                                    else "Restart the program to switch to English.")

    def show_about(self):
        QMessageBox.about(self, tr("{APP_NAME} 정보").format(APP_NAME=APP_NAME), ABOUT_TEXT)

    # ── 메뉴·단추 ──
    def _act(self, text, slot, shortcut=None, tip=None):
        a = QAction(text, self)
        if shortcut:
            a.setShortcuts(shortcut if isinstance(shortcut, list) else [QKeySequence(shortcut)])
        a.setToolTip(tip or text)
        a.triggered.connect(slot)
        return a

    def _tab_call(self, name, *args):
        def run():
            t = self.tab()
            if t is not None:
                getattr(t, name)(*args)
        return run

    def _build_actions(self):
        A = self._act
        self.a_open = A(tr("열기"), self.open_dialog, QKeySequence.StandardKey.Open)
        self.a_save = A(tr("저장"), self._tab_call("save"), "Ctrl+S", tr("지금 파일 이름 그대로 저장 (Ctrl+S)"))
        self.a_save_as = A(tr("다른 이름으로 저장"), self._tab_call("save_as"), "Ctrl+Shift+S")
        self.a_print = A(tr("인쇄…"), self._tab_call("print_doc"), "Ctrl+P", tr("인쇄 (Ctrl+P). 형광펜·메모·글자 상자도 함께 찍힘"))
        self.a_close = A(tr("탭 닫기"), lambda: self.close_tab(self.tabs.currentIndex()), "Ctrl+W")
        self.a_quit = A(tr("끝내기"), self.close, "Ctrl+Q")
        self.a_undo = A(tr("되돌리기"), self._tab_call("undo"), "Ctrl+Z", tr("방금 한 편집 되돌리기 (Ctrl+Z)"))
        self.a_redo = A(tr("다시 하기"), self._tab_call("redo"), ["Ctrl+Y", "Ctrl+Shift+Z"],
                        tr("되돌린 편집 다시 하기 (Ctrl+Y)"))
        self.a_copy = A(tr("글자 복사"), self._copy_text, "Ctrl+C", tr("보기 모드에서 쪽 위를 끌어 고른 글자 복사 (Ctrl+C)"))
        self.a_export = A(tr("고른 쪽 내보내기…"), self._tab_call("export_selected"), "Ctrl+E")
        self.a_insert = A(tr("현재 쪽 뒤에 PDF 넣기…"), self._insert_after_current, "Ctrl+I")
        self.a_delete = A(tr("고른 쪽 지우기"), lambda: self._on_pages("delete_pages"))
        self.a_rot_l = A(tr("↺ 반시계 90°"), lambda: self._on_pages("rotate", -90), "Ctrl+L",
                         tr("고른 쪽(없으면 지금 쪽)을 반시계 방향으로 90° 돌리기 (Ctrl+L)"))
        self.a_rot_r = A(tr("↻ 시계 90°"), lambda: self._on_pages("rotate", 90), "Ctrl+R",
                         tr("고른 쪽(없으면 지금 쪽)을 시계 방향으로 90° 돌리기 (Ctrl+R)"))
        self.a_find = A(tr("찾기"), self._tab_call("show_search"), QKeySequence.StandardKey.Find)
        self.a_zoom_in = A(tr("확대"), self._view_call("zoom_in"), ["Ctrl+=", "Ctrl++"])
        self.a_zoom_out = A(tr("축소"), self._view_call("zoom_out"), "Ctrl+-")
        self.a_fit_w = A(tr("폭 맞춤"), self._view_call("fit", "width"), "Ctrl+1")
        self.a_fit_p = A(tr("쪽 맞춤"), self._view_call("fit", "page"), "Ctrl+0")
        # 도구 모드: 한 번에 하나만(보기·형광펜·메모·글자·상자·지우개). Esc는 언제나 보기로
        self.mode_group = QActionGroup(self)
        self.a_mode = {}
        for key, label, sc, tip in [
            ("view", tr("보기"), "Esc", tr("보기 모드(Esc). 쪽 위를 끌면 글자 고르기(Ctrl+C 복사), 메모 위에 올리면 내용, 두 번 누르면 고치기")),
            ("highlight", tr("형광펜"), "Ctrl+H", tr("글자 위를 끌어서 칠하기(Ctrl+H). 색은 옆 ▾")),
            ("note", tr("메모"), "Ctrl+M", tr("쪽을 눌러 메모 붙이기(Ctrl+M)")),
            ("text", tr("글자"), "Ctrl+T", tr("쪽 위를 끌어 글자 상자를 그리고 그 안에 바로 쓰기(Ctrl+T). 그냥 누르면 기본 폭 상자. 크기·정렬·색은 상자 위 막대에서, 인쇄에도 나옴. 넣은 글자를 누르면 고치기")),
            ("box", tr("상자"), "Ctrl+Shift+R", tr("쪽 위를 끌어 네모 상자 그리기(Ctrl+Shift+R). 선·채우기 색은 옆 ▾, 그린 상자를 누르면 지금 색으로 바뀜")),
            ("erase", tr("지우개"), "Ctrl+Shift+E", tr("형광펜·메모·글자·상자를 눌러 지우거나, 끌어서 그 범위를 한꺼번에 지우기(Ctrl+Shift+E)")),
        ]:
            a = A(label, lambda _=False, k=key: self.set_mode(k), sc, tip)
            a.setCheckable(True)
            self.mode_group.addAction(a)
            self.a_mode[key] = a
        self.a_mode["view"].setChecked(True)
        self.hl_color = "yellow"
        self.color_menu = QMenu(tr("형광펜 색"), self)
        color_group = QActionGroup(self)
        for key, label in [("yellow", tr("노랑")), ("green", tr("초록")), ("pink", tr("분홍"))]:
            a = self.color_menu.addAction(label)
            a.setCheckable(True)
            a.setChecked(key == "yellow")
            color_group.addAction(a)
            a.triggered.connect(lambda _=False, k=key: self.set_hl_color(k))
        self.box_style = {"line": "#d00000", "fill": None, "width": 2, "opacity": 1.0}
        self.box_menu = self._build_box_menu()
        self.a_ocr = A(tr("글자 인식"), self._tab_call("recognize_text"), "Ctrl+Shift+O",
                       tr("글자가 그림으로 된 쪽(세움터 건축물대장·스캔)을 읽어 고르기·복사·찾기가 되게 함(Ctrl+Shift+O)."
                       " 윈도우 내장 인식, PC 밖으로 안 보냄. 숫자·지번은 원문과 대조"))
        self.a_prev = A(tr("◀ 이전 쪽"), self._view_call("prev_page"))
        self.a_next = A(tr("다음 쪽 ▶"), self._view_call("next_page"))

        mb = self.menuBar()
        m = mb.addMenu(tr("파일"))
        for a in (self.a_open, None, self.a_save, self.a_save_as, self.a_export, None, self.a_print, None, self.a_close, self.a_quit):
            m.addSeparator() if a is None else m.addAction(a)
        m = mb.addMenu(tr("편집"))
        for a in (self.a_undo, self.a_redo, None, self.a_copy, None, self.a_insert, self.a_export, self.a_delete, self.a_rot_r, self.a_rot_l, None, self.a_find):
            m.addSeparator() if a is None else m.addAction(a)
        m = mb.addMenu(tr("도구"))
        for k in ("view", "highlight", "note", "text", "box", "erase"):
            m.addAction(self.a_mode[k])
        m.addMenu(self.color_menu)
        m.addMenu(self.box_menu)
        m.addSeparator()
        m.addAction(self.a_ocr)
        self.a_im = self.im_panel.toggleViewAction()
        self.a_im.setText(tr("자료 폴더"))
        self.a_im.setShortcut(QKeySequence("Ctrl+B"))
        self.a_im.setToolTip(tr("브리핑·매각자료 폴더 목록 보이기/숨기기(Ctrl+B). 두 판 비교도 여기서"))
        m = mb.addMenu(tr("보기"))
        m.addAction(self.a_im)
        for a in (self.a_zoom_in, self.a_zoom_out, self.a_fit_w, self.a_fit_p, None, self.a_prev, self.a_next):
            m.addSeparator() if a is None else m.addAction(a)
        self.a_bubigter = A(tr("부빅터에서 건물·실거래 정보 보기"), lambda: open_url(BUBIGTER_URL), tip=tr("부빅터 웹사이트를 브라우저로 엽니다"))
        self.a_source = A(tr("소스 코드"), lambda: open_url(SOURCE_URL), tip=tr("한장PDF 소스 코드(GitHub)를 브라우저로 엽니다"))
        self.a_about = A(tr("{APP_NAME} 정보").format(APP_NAME=APP_NAME), self.show_about)
        m = mb.addMenu(tr("도움말"))
        for a in (self.a_bubigter, None, self.a_source, self.a_about):
            m.addSeparator() if a is None else m.addAction(a)
        m.addSeparator()
        lang_menu = m.addMenu("Language / 언어")  # 어느 언어로 보든 찾을 수 있게 두 말로 적는다
        lang_group = QActionGroup(self)
        self.a_lang = {}
        for code, label in (("ko", "한국어"), ("en", "English")):
            a = lang_menu.addAction(label)
            a.setCheckable(True)
            a.setChecked(code == i18n.LANG)
            lang_group.addAction(a)
            a.triggered.connect(lambda _=False, c=code: self.choose_lang(c))
            self.a_lang[code] = a

        tb = QToolBar(tr("도구"))
        tb.setMovable(False)
        tb.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)
        for a in (self.a_open, self.a_save, self.a_save_as, self.a_print, self.a_undo, self.a_redo, None, self.a_prev):
            tb.addSeparator() if a is None else tb.addAction(a)
        tb.addWidget(self.page_box)
        tb.addWidget(self.page_total)
        for a in (self.a_next, None, self.a_zoom_out, self.a_zoom_in, self.a_fit_w, self.a_fit_p,
                  None, self.a_rot_l, self.a_rot_r, None, self.a_find, self.a_export, self.a_insert):
            tb.addSeparator() if a is None else tb.addAction(a)
        self.addToolBar(tb)
        # 둘째 줄: 형광펜·메모 도구(첫 줄이 길어 따로 둠)
        tb2 = QToolBar(tr("주석"))
        tb2.setMovable(False)
        tb2.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)
        tb2.addAction(self.a_im)
        tb2.addSeparator()
        for k in ("view", "highlight", "note", "text", "box", "erase"):
            tb2.addAction(self.a_mode[k])
            if k in ("highlight", "box"):
                btn = tb2.widgetForAction(self.a_mode[k])
                if isinstance(btn, QToolButton):
                    btn.setMenu(self.color_menu if k == "highlight" else self.box_menu)
                    btn.setPopupMode(QToolButton.ToolButtonPopupMode.MenuButtonPopup)
        tb2.addSeparator()
        tb2.addAction(self.a_ocr)
        self.addToolBarBreak()
        self.addToolBar(tb2)

    def set_mode(self, mode: str):
        self.a_mode[mode].setChecked(True)
        t = self.tab()
        if t is not None:
            t.view.set_mode(mode)
        hints = {"view": "", "highlight": tr("형광펜: 칠할 글자 위를 끌어 주세요. Esc로 끝"),
                 "note": tr("메모: 붙일 자리를 눌러 주세요. Esc로 끝"),
                 "text": tr("글자: 넣을 자리를 끌어 상자를 그리고 바로 쓰세요(Ctrl+Enter 넣기). 넣은 글자를 누르면 고치기"),
                 "box": tr("상자: 쪽 위를 끌어 그리세요. 색은 '상자' 옆 ▾. 그린 상자를 누르면 지금 색으로 바뀜"),
                 "erase": tr("지우개: 지울 것을 누르거나, 끌어서 범위를 한꺼번에 지우세요. Esc로 끝")}
        self.statusBar().showMessage(hints.get(mode, ""))

    # ── 상자 색 ──
    def _build_box_menu(self) -> QMenu:
        menu = QMenu(tr("상자 색"), self)
        presets = [("#000000", tr("검정")), ("#d00000", tr("빨강")), ("#0050c8", tr("파랑")), ("#138a36", tr("초록")), ("#e8590c", tr("주황"))]
        fills = [("#fff3a0", tr("노랑")), ("#cdeccd", tr("연두")), ("#cfe3ff", tr("하늘")), ("#ffd6e7", tr("분홍")),
                 ("#e6e6e6", tr("회색")), ("#ffffff", tr("흰색"))] + presets
        self.box_actions = {}
        for key, title, items in (("line", tr("선 색"), [(None, tr("없음"))] + presets),
                                  ("fill", tr("채우기 색"), [(None, tr("없음"))] + fills)):
            sub = menu.addMenu(title)
            group = QActionGroup(self)
            for hexv, label in items:
                a = sub.addAction(label)
                a.setCheckable(True)
                a.setChecked(hexv == self.box_style[key])
                group.addAction(a)
                a.triggered.connect(lambda _=False, k=key, h=hexv: self.set_box_style(**{k: h}))
                self.box_actions[(key, hexv)] = a
            sub.addSeparator()
            sub.addAction(tr("다른 색…")).triggered.connect(lambda _=False, k=key, g=group: self._pick_box_color(k, g))
        for key, title, items in (("width", tr("선 굵기"), [(w, f"{w:g} pt") for w in (0.5, 1, 2, 3, 5)]),
                                  ("opacity", tr("진하기"), [(1.0, tr("100% (안 비침)")), (0.7, "70%"), (0.5, "50%"),
                                                        (0.3, tr("30% (아래 글자가 잘 보임)"))])):
            sub = menu.addMenu(title)
            group = QActionGroup(self)
            for v, label in items:
                a = sub.addAction(label)
                a.setCheckable(True)
                a.setChecked(v == self.box_style[key])
                group.addAction(a)
                a.triggered.connect(lambda _=False, k=key, v=v: self.set_box_style(**{k: v}))
        return menu

    def _pick_box_color(self, key: str, group: QActionGroup):
        cur = self.box_style[key] or "#000000"
        c = QColorDialog.getColor(QColor(cur), self, tr("선 색") if key == "line" else tr("채우기 색"))
        if c.isValid():
            for a in group.actions():
                a.setChecked(False)
            self.set_box_style(**{key: c.name()})

    def set_box_style(self, **changes):
        self.box_style = {**self.box_style, **changes}
        t = self.tab()  # 다른 탭은 그 탭으로 바꿀 때 _apply_tools가 맞춘다
        if t is not None:
            t.view.box_style = dict(self.box_style)
        self.set_mode("box")
        st = self.box_style
        self.statusBar().showMessage(
            tr("상자: 선 {line} · 채우기 {fill} · 굵기 {width:g}pt · 진하기 {opacity}%").format(
                line=st['line'] or tr("없음"), fill=st['fill'] or tr("없음"), width=st['width'],
                opacity=round(st['opacity'] * 100)))

    def set_hl_color(self, color: str):
        self.hl_color = color
        t = self.tab()
        if t is not None:
            t.view.hl_color = color
        self.set_mode("highlight")

    def _apply_tools(self, t):
        """탭을 바꾸거나 새로 열면 지금 고른 도구·색을 그 탭에도 적용."""
        mode = next(k for k, a in self.a_mode.items() if a.isChecked())
        t.view.hl_color = self.hl_color
        t.view.box_style = dict(self.box_style)
        t.view.set_mode(mode)

    def _copy_text(self):
        views = [v for v in self._views() if v.selection_seq]
        if not views:
            self.statusBar().showMessage(tr("보기 모드(Esc)에서 쪽 위를 마우스로 끌어 글자를 먼저 골라 주세요."), 4000)
            return
        v = max(views, key=lambda v: v.selection_seq)  # 비교 탭: 마지막에 고른 쪽
        if v.copy_selection():
            self.statusBar().showMessage(tr("글자 {n}자를 복사했습니다.").format(n=len(v.selected_text())), 4000)

    def _views(self) -> list:
        w = self.tabs.currentWidget()
        if isinstance(w, DocTab):
            return [w.view]
        if isinstance(w, CompareTab):
            return list(w.views)
        return []

    NAV = ("prev_page", "next_page")

    def _view_call(self, name, *args):
        def run():
            views = self._views()
            w = self.tabs.currentWidget()
            # 비교 탭에서 같이 넘기기가 켜져 있으면 쪽 이동은 왼쪽만(오른쪽은 따라옴, 두 번 넘어가지 않게)
            if name in self.NAV and isinstance(w, CompareTab) and w.sync.isChecked():
                views = views[:1]
            for v in views:  # 확대·맞춤은 양쪽 모두
                getattr(v, name)(*args)
        return run

    def _on_pages(self, name, *args):
        t = self.tab()
        if t is None:
            return
        pages = t.thumbs.selected_pages() or [t.view.current_page]
        getattr(t, name)(pages, *args)

    def _insert_after_current(self):
        t = self.tab()
        if t is not None:
            paths = t._pick_pdfs()
            if paths:
                t.insert_files(paths, t.view.current_page + 1)

    # ── 탭 ──
    def tab(self) -> DocTab | None:
        w = self.tabs.currentWidget()
        return w if isinstance(w, DocTab) else None

    def _show_empty_hint(self):
        self.statusBar().showMessage(tr("PDF 파일을 이 창에 끌어다 놓거나 Ctrl+O로 여세요."))

    def _sync(self):
        t = self.tab()
        has = t is not None
        for a in (self.a_save, self.a_save_as, self.a_print, self.a_undo, self.a_redo, self.a_export, self.a_insert,
                  self.a_delete, self.a_rot_l, self.a_rot_r, self.a_find, self.a_ocr, *self.a_mode.values()):
            a.setEnabled(has)
        views = self._views()
        for a in (self.a_close, self.a_copy, self.a_zoom_in, self.a_zoom_out, self.a_fit_w, self.a_fit_p, self.a_prev, self.a_next):
            a.setEnabled(bool(views))
        cmp = self.tabs.currentWidget()
        if isinstance(cmp, CompareTab):  # 비교 탭: 읽기 전용, 보기 조작만
            self.setWindowTitle(f"{cmp.title} - {APP_NAME}")
            v = cmp.views[0]
            if not self.page_box.hasFocus():
                self.page_box.setText(str(v.current_page + 1))
            self.page_total.setText(f" / {cmp.docs[0].page_count} ")
            self.zoom_label.setText(f"{round(v.zoom * 100)}%")
            return
        if not has:
            self.setWindowTitle(APP_NAME)
            self.page_box.setText("")
            self.page_total.setText(" / 0 ")
            self.zoom_label.setText("")
            return
        # 쓰는 중에는 되돌리기·다시 하기가 칸 안의 글에 걸리므로(DocTab.undo) 그 기준으로 켠다
        can = t.view.editor_can_undo() if t.view.editing_text else (t.d.can_undo(), t.d.can_redo())
        self.a_undo.setEnabled(can[0])
        self.a_redo.setEnabled(can[1])
        self._apply_tools(t)
        i = self.tabs.indexOf(t)
        self.tabs.setTabText(i, t.title)
        self.tabs.setTabToolTip(i, t.d.path)
        self.setWindowTitle(f"{t.title} - {APP_NAME}")
        if not self.page_box.hasFocus():
            self.page_box.setText(str(t.view.current_page + 1))
        self.page_total.setText(f" / {t.d.page_count} ")
        self.zoom_label.setText(f"{round(t.view.zoom * 100)}%")

    def _goto_typed_page(self):
        views = self._views()
        if views and self.page_box.text():
            views[0].go_to_page(int(self.page_box.text()) - 1)  # 비교 탭은 같이 넘기기로 오른쪽도 따라감
            views[0].setFocus()

    def open_paths(self, paths):
        for p in paths:
            self.open_path(p)

    def open_path(self, path: str):
        for i in range(self.tabs.count()):
            w = self.tabs.widget(i)
            if isinstance(w, DocTab) and same_file(w.d.path, path):
                self.tabs.setCurrentIndex(i)
                return
        pw = None
        if doc_ops.needs_password(path):
            pw = ask_password(self, path)
            if pw is None:
                return
        try:
            d = Document(path, pw)
        except (DocOpError, OSError) as e:
            QMessageBox.warning(self, tr("열기"), f"{os.path.basename(path)}\n{e}")
            return
        t = DocTab(d)
        t.open_elsewhere = lambda p, me=t: any(
            isinstance(w, DocTab) and w is not me and same_file(w.d.path, p)
            for w in (self.tabs.widget(i) for i in range(self.tabs.count())))
        t.changed.connect(self._sync)
        t.status.connect(lambda msg: self.statusBar().showMessage(msg, 6000))
        self.tabs.setCurrentIndex(self.tabs.addTab(t, t.title))
        self.settings.setValue("last_dir", os.path.dirname(d.path))
        self.statusBar().clearMessage()
        self._sync()

    def _load(self, path: str) -> Document | None:
        pw = None
        if doc_ops.needs_password(path):
            pw = ask_password(self, path)
            if pw is None:
                return None
        try:
            return Document(path, pw)
        except (DocOpError, OSError) as e:
            QMessageBox.warning(self, tr("열기"), f"{os.path.basename(path)}\n{e}")
            return None

    def open_compare(self, left: str, right: str):
        a = self._load(left)
        if a is None:
            return
        b = self._load(right)
        if b is None:
            a.close()
            return
        t = CompareTab(a, b)
        for v in t.views:
            v.currentPageChanged.connect(lambda _: self._sync())
            v.zoomChanged.connect(lambda _: self._sync())
            v.textSelected.connect(lambda n: self.statusBar().showMessage(select_hint(n), 4000))
        i = self.tabs.addTab(t, tr("비교: {name} ↔ {name2}").format(name=a.name, name2=b.name))
        self.tabs.setTabToolTip(i, f"{a.path}\n{b.path}")
        self.tabs.setCurrentIndex(i)
        self.statusBar().showMessage(tr("두 판 비교(읽기 전용). '같이 넘기기'를 끄면 따로 넘길 수 있습니다."), 6000)
        self._sync()

    def open_dialog(self):
        start = self.settings.value("last_dir", os.path.expanduser("~"))
        paths, _ = QFileDialog.getOpenFileNames(self, tr("PDF 열기"), start, "PDF (*.pdf)")
        self.open_paths(paths)

    def _confirm_close(self, t: DocTab) -> bool:
        # 쓰던 글자 상자도 편집으로 쳐서 저장할지 묻는다. 넣지 못했으면(너무 많음 등) 닫지 않고 그 칸으로 돌아감
        if not t.flush_editor():
            self.tabs.setCurrentWidget(t)
            return False
        if not t.d.modified:
            return True
        self.tabs.setCurrentWidget(t)
        r = QMessageBox.question(
            self, tr("저장"), tr("{name}에 저장하지 않은 편집이 있습니다. 저장할까요?").format(name=t.d.name),
            QMessageBox.StandardButton.Save | QMessageBox.StandardButton.Discard | QMessageBox.StandardButton.Cancel)
        if r == QMessageBox.StandardButton.Save:
            return t.save()
        return r == QMessageBox.StandardButton.Discard

    def close_tab(self, i: int):
        w = self.tabs.widget(i)
        if isinstance(w, CompareTab):
            self.tabs.removeTab(i)
            w.close_docs()
        elif isinstance(w, DocTab) and self._confirm_close(w):
            self.tabs.removeTab(i)
            w.d.close()
        else:
            return
        w.deleteLater()
        self._sync()
        if self.tabs.count() == 0:
            self._show_empty_hint()

    def closeEvent(self, e):
        for i in range(self.tabs.count()):
            w = self.tabs.widget(i)
            if isinstance(w, DocTab) and not self._confirm_close(w):
                e.ignore()
                return
        for i in range(self.tabs.count()):  # 닫기로 정해졌으면 비교 탭 문서도 닫는다
            w = self.tabs.widget(i)
            if isinstance(w, CompareTab):
                w.close_docs()
        e.accept()

    # ── 창에 끌어다 놓아 열기 ──
    def dragEnterEvent(self, e):
        if pdf_paths_from_mime(e.mimeData()):
            e.acceptProposedAction()

    def dropEvent(self, e):
        self.open_paths(pdf_paths_from_mime(e.mimeData()))
