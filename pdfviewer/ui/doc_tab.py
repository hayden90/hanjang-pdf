"""탭 하나 = 열린 PDF 하나. 쪽 그림 목록 + 본문 + 찾기 줄, 편집·저장 대화 상자."""
from __future__ import annotations

import os

import pymupdf
from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QApplication, QFileDialog, QHBoxLayout, QInputDialog, QLabel, QLineEdit, QMessageBox,
    QProgressDialog, QPushButton, QSizePolicy, QSplitter, QVBoxLayout, QWidget,
)

from ..core import annots, doc_ops, ocr
from ..core.doc_ops import DocOpError
from ..core.document import Document, same_file
from .page_view import PageView, select_hint
from .printing import ask_and_print
from .text_dialog import last_style
from .thumbs import ThumbList
from ..i18n import tr


DEFAULT_BOX_W = 200  # 끌지 않고 눌렀을 때 여는 글자 상자 폭(pt)
ocr_engine = ocr.windows_engine  # 글자 인식 엔진을 만드는 함수(시험에서 가짜로 바꿔 끼움)


def ask_password(parent, path: str) -> str | None:
    pw, ok = QInputDialog.getText(
        parent, tr("암호"), tr("{file}\n암호를 넣어 주세요.").format(file=os.path.basename(path)), QLineEdit.EchoMode.Password)
    return pw if ok and pw else None


def page_label(pages: list[int]) -> str:
    """[0,1,2,5] → '1-3,6'"""
    runs, start, prev = [], None, None
    for p in pages + [None]:
        if start is None:
            start = prev = p
        elif p is not None and p == prev + 1:
            prev = p
        else:
            runs.append(f"{start + 1}" if start == prev else f"{start + 1}-{prev + 1}")
            start = prev = p
    return ",".join(runs)


class SearchBar(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(6, 4, 6, 4)
        self.edit = QLineEdit(placeholderText=tr("찾을 글자 (Enter: 다음, Shift+Enter: 이전)"))
        self.count = QLabel("")
        self.prev = QPushButton(tr("이전"))
        self.next = QPushButton(tr("다음"))
        self.close_btn = QPushButton(tr("닫기"))
        for w in (self.edit, self.count, self.prev, self.next, self.close_btn):
            lay.addWidget(w)


class DocTab(QWidget):
    changed = Signal()  # 제목(수정 표시)·쪽 번호가 바뀔 때
    status = Signal(str)

    def __init__(self, document: Document, parent=None):
        super().__init__(parent)
        self.d = document
        self.open_elsewhere = lambda path: False  # 창이 채운다: 그 경로가 다른 편집 탭에 열려 있나
        self._query = ""
        self._hits = []
        self._hit_i = -1
        self.thumbs = ThumbList()
        self.view = PageView()
        self.search = SearchBar()
        self.search.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        self.search.hide()
        split = QSplitter(Qt.Orientation.Horizontal)
        split.addWidget(self.thumbs)
        split.addWidget(self.view)
        split.setStretchFactor(1, 1)
        split.setSizes([190, 900])
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)
        lay.addWidget(self.search)
        lay.addWidget(split, 1)
        self._wire()
        self.refresh()

    def _wire(self):
        self.thumbs.pageClicked.connect(self.view.go_to_page)
        self.view.currentPageChanged.connect(self.thumbs.mark_current)
        self.view.currentPageChanged.connect(lambda _: self.changed.emit())
        self.view.zoomChanged.connect(lambda _: self.changed.emit())
        self.view.editorChanged.connect(self.changed.emit)
        self.thumbs.insertFiles.connect(self.insert_files)
        self.view.set_accept_files(True)
        self.view.insertFiles.connect(self.insert_files)
        self.thumbs.movePages.connect(self.move_pages)
        self.thumbs.contextAction.connect(self._on_context)
        self.search.edit.returnPressed.connect(self._search_enter)
        self.search.next.clicked.connect(lambda: self.find_step(1))
        self.search.prev.clicked.connect(lambda: self.find_step(-1))
        self.search.close_btn.clicked.connect(self.hide_search)
        # 마우스 처리가 끝난 뒤에 실행한다(Queued): 편집이 실패하면 화면을 다시 만들면서 쪽 위젯을
        # 지우는데, 그 위젯의 마우스 처리 도중이면 프로그램이 죽는다.
        q = Qt.ConnectionType.QueuedConnection
        self.view.highlightRequested.connect(self.add_highlight, q)
        self.view.noteRequested.connect(self.add_note, q)
        self.view.textRequested.connect(self.text_at, q)
        self.view.textBoxRequested.connect(self.text_box_at, q)
        self.view.textCommitted.connect(self.commit_text, q)
        self.view.boxRequested.connect(self.add_box, q)
        self.view.boxClickRequested.connect(self.box_click, q)
        self.view.eraseRequested.connect(self.erase_at, q)
        self.view.eraseAreaRequested.connect(self.erase_area, q)
        self.view.noteOpenRequested.connect(self.open_note, q)
        self.view.textSelected.connect(self._on_text_selected)

    # ── 표시 ──
    @property
    def title(self) -> str:
        return ("* " if self.d.modified else "") + self.d.name

    def refresh(self, keep_page: int | None = None, select: list[int] | None = None,
                keep_scroll: bool = False):
        page = self.view.current_page if keep_page is None else keep_page
        scroll = self.view.verticalScrollBar().value() if keep_scroll else None
        self.view.set_document(self.d.doc, keep_page=page, keep_scroll=scroll)
        self.thumbs.set_document(self.d.doc, select)
        if self._query:
            self._run_search(self._query, keep_index=True)
        self.changed.emit()

    def _do(self, fn, *args, keep_page=None, select=None, msg=""):
        # 쪽 넣기·옮기기·지우기·돌리기 전에 쓰던 글자 상자를 먼저 넣는다(쪽 번호가 바뀌면 쓰던 자리를 잃음)
        if not self.flush_editor():
            return None
        try:
            result = self.d.edit(fn, *args)
        except DocOpError as e:
            self.refresh()  # 실패해도 문서는 편집 전 사본으로 바뀌었으니 화면을 다시 맞춘다
            QMessageBox.warning(self, tr("편집"), str(e))
            return None
        self.refresh(keep_page, select)
        if msg:
            self.status.emit(msg)
        return result

    # ── 편집 ──
    def insert_files(self, paths: list[str], at: int):
        items = []
        for p in paths:
            pw = None
            if doc_ops.needs_password(p):
                pw = ask_password(self, p)
                if pw is None:
                    return
            items.append((p, pw))

        def insert_all(doc, items, at):
            pos = at
            for p, pw in items:
                pos += doc_ops.insert_pdf(doc, p, pos, pw)
            return pos - at

        n = self._do(insert_all, items, at, keep_page=at, select=None)
        if n:
            self.thumbs.set_document(self.d.doc, list(range(at, at + n)))
            self.status.emit(tr("{at}쪽 자리에 {n}쪽을 넣었습니다.").format(at=at + 1, n=n))

    def move_pages(self, pages: list[int], to: int):
        if doc_ops.is_noop_move(self.d.page_count, pages, to):
            return  # 제자리에 놓음: 수정 표시·되돌리기 칸을 만들지 않는다
        pos = self._do(doc_ops.move_pages, pages, to)
        if pos is not None:
            sel = list(range(pos, pos + len(pages)))
            self.thumbs.set_document(self.d.doc, sel)
            self.view.go_to_page(pos)

    def delete_pages(self, pages: list[int]):
        if pages:
            self._do(doc_ops.delete_pages, pages, keep_page=max(0, pages[0] - 1),
                     msg=tr("{n}쪽을 지웠습니다. 되돌리기: Ctrl+Z").format(n=len(pages)))

    def rotate(self, pages: list[int], deg: int):
        if pages:
            self._do(doc_ops.rotate_pages, pages, deg, select=pages)

    def flush_editor(self) -> bool:
        """쪽 위에서 쓰던 글자 상자가 있으면 지금 넣는다(저장·인쇄·닫기 전에: 쓴 글이 빠지지 않게).

        넣기는 마우스·키 처리가 끝난 뒤 실행되도록 미뤄 두었으므로(Queued) 여기서 바로 돌린다.
        넣지 못했으면(너무 많음 등) 칸이 쓴 글 그대로 다시 열려 있고 False: 부르는 쪽은 멈춘다.
        """
        if self.view.finish_editor():
            QApplication.sendPostedEvents(None, 0)
        return not self.view.editing_text

    def undo(self):
        if self.view.editing_text:  # 쓰는 중에는 문서 대신 칸 안의 글을 되돌린다(문서가 바뀌면 쓰던 상자와 어긋남)
            self.view.editor_undo(redo=False)
            return
        if self.d.undo():
            self.refresh(keep_scroll=True)
            self.status.emit(tr("되돌렸습니다. 다시 하기: Ctrl+Y"))

    def redo(self):
        if self.view.editing_text:
            self.view.editor_undo(redo=True)
            return
        if self.d.redo():
            self.refresh(keep_scroll=True)
            self.status.emit(tr("다시 했습니다."))

    # ── 글자 인식 ──
    def recognize_text(self) -> int:
        """글자 정보가 없는 쪽을 읽어 보이지 않는 글자층을 넣는다(모든 쪽 한꺼번에, Ctrl+Z 한 번에 되돌림).

        넣은 낱말 수를 돌려준다(못 했거나 취소하면 0).
        """
        if not self.flush_editor():
            return 0
        todo = [i for i in range(self.d.page_count) if ocr.needs_ocr(self.d.doc[i])]
        if not todo:
            QMessageBox.information(self, tr("글자 인식"), tr("모든 쪽에 이미 글자 정보가 있어 읽을 쪽이 없습니다."))
            return 0
        engine, why = ocr_engine()
        if engine is None:
            QMessageBox.information(self, tr("글자 인식"), why)
            return 0
        prog = QProgressDialog(tr("글자를 읽는 중…"), tr("취소"), 0, len(todo), self)
        prog.setWindowTitle(tr("글자 인식"))
        prog.setWindowModality(Qt.WindowModality.WindowModal)
        prog.setMinimumDuration(400)
        found = {}
        try:
            for n, pno in enumerate(todo):
                prog.setValue(n)
                QApplication.processEvents()
                if prog.wasCanceled():
                    self.status.emit(tr("글자 인식을 취소했습니다. 문서는 그대로입니다."))
                    return 0
                words = ocr.recognize_page(self.d.doc[pno], engine)
                if words:
                    found[pno] = words
        except Exception as e:
            QMessageBox.warning(self, tr("글자 인식"), tr("글자를 읽지 못했습니다: {e}").format(e=e))
            return 0
        finally:
            canceled = prog.wasCanceled()  # 마지막 쪽을 읽는 도중 누른 취소도 받는다
            prog.close()
        if canceled:
            self.status.emit(tr("글자 인식을 취소했습니다. 문서는 그대로입니다."))
            return 0
        if not found:
            QMessageBox.information(self, tr("글자 인식"), tr("읽을 수 있는 글자를 찾지 못했습니다."))
            return 0
        count = self._do(ocr.add_text_layer, found, keep_page=self.view.current_page)
        if count:
            self.status.emit(tr("글자 인식: {n}쪽에 낱말 {count}개를 넣었습니다. 이제 끌어서 고르기·복사·찾기가 됩니다. 인식한 글자는 틀릴 수 있으니 숫자·지번은 원문과 대조하세요. 되돌리기: Ctrl+Z").format(n=len(found), count=count))
        return count or 0

    def _on_text_selected(self, n: int):
        self.status.emit(select_hint(n))

    # ── 형광펜·메모 ──
    def _annot_do(self, pno: int, fn, *args, msg: str = ""):
        """주석 편집: 성공하면 그 쪽만 다시 그려 보던 자리를 지킨다."""
        try:
            result = self.d.edit(fn, pno, *args)
        except DocOpError as e:
            self.refresh(keep_scroll=True)  # 실패하면 문서가 편집 전 사본으로 바뀌므로 화면을 다시 맞춤
            QMessageBox.warning(self, tr("주석"), str(e))
            return None
        self.view.rerender_page(pno)
        self.thumbs.rerender(pno)
        self.changed.emit()
        if msg:
            self.status.emit(msg)
        return result

    def add_highlight(self, pno: int, rect):
        self._annot_do(pno, annots.add_highlight, rect, self.view.hl_color, msg=tr("형광펜을 칠했습니다. 되돌리기: Ctrl+Z"))

    def _ask_note(self, title: str, text: str = "") -> str | None:
        t, ok = QInputDialog.getMultiLineText(self, title, tr("메모 내용"), text)
        return t if ok and t.strip() else None

    def add_note(self, pno: int, point):
        text = self._ask_note(tr("메모 붙이기"))
        if text is not None:
            self._annot_do(pno, annots.add_note, point, text, msg=tr("메모를 붙였습니다."))

    def text_at(self, pno: int, point):
        """글자 도구로 그냥 누름: 이 프로그램의 글자 상자 위면 고치고, 아니면 그 자리에 기본 폭 상자를 연다."""
        info = annots.annot_at(self.d.doc, pno, point, prefer=pymupdf.PDF_ANNOT_FREE_TEXT)
        if info is not None and info["type"] == pymupdf.PDF_ANNOT_FREE_TEXT:
            self.edit_text(pno, info["xref"])
            return
        page = self.d.doc[pno]
        vis = pymupdf.Point(point) * page.rotation_matrix
        size = last_style["size"]
        x1 = min(vis.x + DEFAULT_BOX_W, page.rect.width)
        box = pymupdf.Rect(min(vis.x, x1 - annots.box_min_width(size)), vis.y, x1, vis.y + size * 1.6 + 8)
        self.view.open_text_editor(pno, (box * page.derotation_matrix).normalize())

    def text_box_at(self, pno: int, rect):
        """글자 도구로 끈 사각형: 그 자리에 바로 쓰는 칸을 연다(넣은 글자 고치기는 그냥 누름)."""
        self.view.open_text_editor(pno, rect)

    def edit_text(self, pno: int, xref: int):
        try:
            st = annots.text_style(self.d.doc, pno, xref)
        except DocOpError as e:
            QMessageBox.warning(self, tr("글자 고치기"), str(e))
            return
        self.view.open_text_editor(pno, st["edit_box"], xref, **{k: st[k] for k in ("text", "size", "align", "color")})

    def commit_text(self, pno: int, d: dict):
        """쪽 위에서 다 쓴 글자 상자를 넣거나 고친다. 못 넣으면 쓴 글 그대로 칸을 다시 연다."""
        vals = (d["text"], d["size"], d["align"], d["color"])
        rows = d.get("rows")  # 쓰는 칸에서 보이던 줄 그대로 넣음
        if d["xref"] is None:
            done = self._annot_do(pno, annots.add_text_box, d["box"], *vals, rows,
                                  msg=tr("글자를 넣었습니다. 인쇄에도 나옵니다. 되돌리기: Ctrl+Z"))
        else:
            try:
                st = annots.text_style(self.d.doc, pno, d["xref"])
            except DocOpError:
                st = None
            if st is not None and vals == (st["text"], st["size"], st["align"], st["color"]):
                return  # 바꾼 것이 없음
            done = self._annot_do(pno, annots.set_text, d["xref"], *vals, rows, d["box"], msg=tr("글자를 고쳤습니다. 되돌리기: Ctrl+Z"))
        if done is None:
            self.view.open_text_editor(pno, d["box"], d["xref"], **dict(zip(("text", "size", "align", "color"), vals)))

    def add_box(self, pno: int, rect):
        st = self.view.box_style
        self._annot_do(pno, annots.add_box, rect, st["line"], st["fill"], st["width"], st["opacity"],
                       msg=tr("상자를 그렸습니다. 색은 '상자' 옆 ▾, 그린 상자를 누르면 지금 색으로 바뀝니다."))

    def box_click(self, pno: int, point):
        xref = annots.box_at(self.d.doc, pno, point)
        if xref is None:
            self.status.emit(tr("상자는 쪽 위를 끌어서 그려 주세요. 그린 상자를 누르면 지금 고른 색으로 바뀝니다."))
            return
        st = self.view.box_style
        self._annot_do(pno, annots.set_box_style, xref, st["line"], st["fill"], st["width"], st["opacity"],
                       msg=tr("상자 색을 바꿨습니다. 되돌리기: Ctrl+Z"))

    def erase_at(self, pno: int, point):
        info = annots.annot_at(self.d.doc, pno, point)
        if info is None:
            self.status.emit(tr("그 자리에 지울 형광펜·메모·글자·상자가 없습니다. 끌면 그 범위를 한꺼번에 지웁니다."))
            return
        self._annot_do(pno, annots.delete_annot, info["xref"], msg=tr("지웠습니다. 되돌리기: Ctrl+Z"))

    def erase_area(self, pno: int, rect):
        if not annots.own_in_rect(self.d.doc, pno, rect):
            self.status.emit(tr("끈 범위에 지울 형광펜·메모·글자·상자가 없습니다."))
            return
        n = self._annot_do(pno, annots.delete_in_rect, rect)
        if n:
            self.status.emit(tr("{n}개를 지웠습니다. 되돌리기: Ctrl+Z").format(n=n))

    def open_note(self, pno: int, point):
        info = annots.annot_at(self.d.doc, pno, point, prefer=pymupdf.PDF_ANNOT_FREE_TEXT)
        if info is not None and info["type"] == pymupdf.PDF_ANNOT_FREE_TEXT:
            self.edit_text(pno, info["xref"])  # 보기 모드에서 글자 상자를 두 번 누르면 고치기
            return
        if info is None or info["type"] != pymupdf.PDF_ANNOT_TEXT:
            return
        text = self._ask_note(tr("메모 고치기"), info["text"])
        if text is not None and text.strip() != info["text"]:
            self._annot_do(pno, annots.set_note_text, info["xref"], text, msg=tr("메모를 고쳤습니다."))

    def _on_context(self, key: str, pages: list[int]):
        if key == "export":
            self.export_selected(pages)
        elif key in ("insert_before", "insert_after"):
            at = pages[0] if key == "insert_before" else pages[-1] + 1
            paths = self._pick_pdfs()
            if paths:
                self.insert_files(paths, at)
        elif key == "rotate_left":
            self.rotate(pages, -90)
        elif key == "rotate_right":
            self.rotate(pages, 90)
        elif key == "delete":
            self.delete_pages(pages)

    def _pick_pdfs(self) -> list[str]:
        paths, _ = QFileDialog.getOpenFileNames(self, tr("넣을 PDF 고르기"), os.path.dirname(self.d.path), "PDF (*.pdf)")
        return paths

    # ── 저장 ──
    def _ask_save_path(self, title: str, suggested: str) -> str | None:
        path, _ = QFileDialog.getSaveFileName(
            self, title, suggested, "PDF (*.pdf)", options=QFileDialog.Option.DontConfirmOverwrite)
        if not path:
            return None
        if not path.lower().endswith(".pdf"):
            path += ".pdf"
        if same_file(path, self.d.path):
            return path  # 원본 덮어쓰기는 부르는 쪽에서 따로 확인
        if self.open_elsewhere(path):
            # 그 탭에 남은 옛 내용을 나중에 Ctrl+S 하면 지금 저장한 것이 사라진다
            QMessageBox.warning(self, title, tr("{file} 파일은 다른 탭에 열려 있습니다.\n그 탭을 먼저 닫은 뒤 다시 저장해 주세요.").format(file=os.path.basename(path)))
            return None
        if os.path.exists(path):
            ok = QMessageBox.question(self, title, tr("{file} 파일이 이미 있습니다. 바꿀까요?").format(file=os.path.basename(path)))
            if ok != QMessageBox.StandardButton.Yes:
                return None
        return path

    def confirm_unencrypted(self) -> bool:
        if not self.d.was_encrypted:
            return True
        ok = QMessageBox.warning(
            self, tr("암호 문서"),
            tr("이 PDF는 암호가 걸려 있었습니다. 저장하는 파일에는 암호가 걸리지 않습니다. 계속할까요?"),
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No)
        return ok == QMessageBox.StandardButton.Yes

    def export_selected(self, pages: list[int] | None = None) -> bool:
        if not self.flush_editor():
            return False
        pages = pages or self.thumbs.selected_pages() or [self.view.current_page]
        if not self.confirm_unencrypted():
            return False
        stem = os.path.splitext(self.d.path)[0]
        path = self._ask_save_path(tr("고른 쪽 내보내기"), tr("{stem}_{pages}쪽.pdf").format(stem=stem, pages=page_label(pages)))
        if not path:
            return False
        try:
            n = self.d.export_pages(pages, path)
        except DocOpError as e:
            QMessageBox.warning(self, tr("내보내기"), str(e))
            return False
        self.status.emit(tr("{n}쪽을 {file}(으)로 내보냈습니다.").format(n=n, file=os.path.basename(path)))
        return True

    def confirm_overwrite_original(self) -> bool:
        ok = QMessageBox.warning(
            self, tr("원본에 덮어쓰기"),
            tr("원본 파일 {name}을(를) 지금 내용으로 덮어씁니다.\n덮어쓰면 되돌릴 수 없습니다. 계속할까요?").format(name=self.d.name),
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No)
        return ok == QMessageBox.StandardButton.Yes

    def save_as(self) -> bool:
        if not self.flush_editor() or not self.confirm_unencrypted():
            return False
        stem = os.path.splitext(self.d.path)[0]
        path = self._ask_save_path(tr("다른 이름으로 저장"), tr("{stem}_편집.pdf").format(stem=stem))
        if not path:
            return False
        if same_file(path, self.d.path) and not self.confirm_overwrite_original():
            return False
        return self._save(path)

    def save(self) -> bool:
        """지금 이름 그대로 바로 저장(대표님 요청 2026-10-26: 묻지 않고 저장). 암호 문서만 한 번 묻는다."""
        return self.flush_editor() and self.confirm_unencrypted() and self._save(self.d.path)

    def _save(self, path: str) -> bool:
        try:
            self.d.save_to(path)
        except DocOpError as e:
            QMessageBox.warning(self, tr("저장"), str(e))
            return False
        self.changed.emit()
        self.status.emit(tr("{file}(으)로 저장했습니다.").format(file=os.path.basename(path)))
        return True

    # ── 인쇄 ──
    def print_doc(self) -> int:
        if not self.flush_editor():
            return 0
        try:
            n = ask_and_print(self, self.d.doc, self.view.current_page)
        except RuntimeError as e:
            QMessageBox.warning(self, tr("인쇄"), str(e))
            return 0
        if n:
            self.status.emit(tr("{n}쪽을 인쇄로 보냈습니다.").format(n=n))
        return n

    # ── 찾기 ──
    def show_search(self):
        self.search.show()
        self.search.edit.setFocus()
        self.search.edit.selectAll()

    def hide_search(self):
        self.search.hide()
        self._query, self._hits = "", []
        self.view.set_hits([])
        self.search.count.setText("")

    def _search_enter(self):
        q = self.search.edit.text()
        if q != self._query:
            self._run_search(q)
        else:
            back = QApplication.keyboardModifiers() & Qt.KeyboardModifier.ShiftModifier
            self.find_step(-1 if back else 1)

    def _run_search(self, q: str, keep_index: bool = False):
        self._query = q
        self._hits = doc_ops.search(self.d.doc, q)
        self.view.set_hits(self._hits)
        if not keep_index:
            self._hit_i = -1
            self.find_step(1)
        else:
            self._update_count()

    def find_step(self, step: int):
        if not self._hits:
            self._update_count()
            return
        self._hit_i = (self._hit_i + step) % len(self._hits)
        self.view.show_hit(self._hit_i)
        self._update_count()

    def _update_count(self):
        if not self._query.strip():
            self.search.count.setText("")
        elif not self._hits:
            self.search.count.setText(tr("없음"))
        else:
            self.search.count.setText(f"{self._hit_i + 1} / {len(self._hits)}")
