"""화면 없이(offscreen) 창을 띄워 열기·끌어다 넣기·옮기기·찾기·확대·되돌리기·저장 흐름을 확인한다."""
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest  # noqa: E402
from PySide6.QtCore import QMimeData, QPoint, QPointF, Qt, QUrl  # noqa: E402
from PySide6.QtGui import QDrag, QDragEnterEvent, QDragLeaveEvent, QDragMoveEvent, QDropEvent, QTextCursor  # noqa: E402
from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox, QToolBar  # noqa: E402

from pdfviewer.ui.compare_tab import CompareTab  # noqa: E402
from pdfviewer.ui.main_window import MainWindow  # noqa: E402
from test_doc_ops import labels_of, make_pdf  # noqa: E402

app = QApplication.instance() or QApplication([])


def pump():
    for _ in range(30):
        app.processEvents()


@pytest.fixture
def win(tmp_path, monkeypatch):
    # 실제 사용자 설정(마지막 폴더·자료 폴더)과 섞이지 않게, 자료 폴더 자동 찾기도 끈다
    monkeypatch.setenv("PDFV_SETTINGS", str(tmp_path / "settings.ini"))
    monkeypatch.setattr("pdfviewer.core.im_index.find_root", lambda roots=None: None)
    w = MainWindow()
    w.resize(1000, 800)
    w.show()
    yield w
    # 시험이 중간에 실패해도 닫을 때 묻는 창(모달)에 걸려 멈추지 않게: 쓰던 칸은 버리고, 묻는 창은 바로 답함
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **k: QMessageBox.StandardButton.Discard)
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: QMessageBox.StandardButton.Ok)
    for i in range(w.tabs.count()):
        t = w.tabs.widget(i)
        if hasattr(t, "view") and getattr(t.view, "_editor", None) is not None:
            t.view._editor.cancel()
        if hasattr(t, "d"):  # 비교 탭에는 편집 문서가 없음
            t.d.modified = False
    w.close()


def send_drag(th, mime, pos):
    """실제 마우스 끌기처럼 viewport에 들어가기→움직이기→놓기 이벤트를 차례로 보낸다."""
    vp = th.viewport()
    p = QPointF(pos)
    args = (Qt.DropAction.CopyAction | Qt.DropAction.MoveAction, mime,
            Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
    enter = QDragEnterEvent(pos, *args)
    QApplication.sendEvent(vp, enter)
    assert enter.isAccepted(), "목록이 끌어 온 것을 받지 않음"
    move = QDragMoveEvent(pos, *args)
    QApplication.sendEvent(vp, move)
    assert move.isAccepted()
    drop = QDropEvent(p, *args)
    QApplication.sendEvent(vp, drop)
    pump()
    return drop


def gap_point(th, row):
    """row번 쪽 바로 앞(그림 위쪽 가장자리)의 viewport 좌표."""
    if row < th.count():
        return th.visualItemRect(th.item(row)).topLeft() + QPoint(5, 2)
    return th.visualItemRect(th.item(th.count() - 1)).bottomLeft() + QPoint(5, 6)


def drop_files_on_thumbs(t, paths, row):
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(p) for p in paths])
    send_drag(t.thumbs, mime, gap_point(t.thumbs, row))


def test_full_flow(win, tmp_path, monkeypatch):
    a = make_pdf(tmp_path / "a.pdf", [f"A{i}" for i in range(1, 7)])
    b = make_pdf(tmp_path / "b.pdf", ["B1", "B2"])
    win.open_paths([a, a])  # 같은 파일은 탭 하나만
    pump()
    assert win.tabs.count() == 1
    t = win.tab()
    assert len(t.view.pages) == 6 and t.thumbs.count() == 6

    # 끌어다 넣기: 3쪽 앞(위쪽 절반) → 2번 자리에
    drop_files_on_thumbs(t, [b], 2)
    assert labels_of(t.d.doc) == ["A1", "A2", "B1", "B2", "A3", "A4", "A5", "A6"]
    assert t.thumbs.selected_pages() == [2, 3]
    assert t.thumbs.count() == 8 and len(t.view.pages) == 8
    assert win.tabs.tabText(0).startswith("* ")
    # 맨 끝에 넣기
    drop_files_on_thumbs(t, [b], t.thumbs.count())
    assert labels_of(t.d.doc)[-2:] == ["B1", "B2"]

    # 옮기기, 지우기, 되돌리기
    t.undo(); pump()
    t.move_pages([2, 3], 0); pump()
    assert labels_of(t.d.doc)[:3] == ["B1", "B2", "A1"]
    t.delete_pages([0, 1]); pump()
    assert labels_of(t.d.doc) == [f"A{i}" for i in range(1, 7)]
    t.undo(); pump()
    assert labels_of(t.d.doc)[:2] == ["B1", "B2"]

    # 찾기
    t.show_search()
    t.search.edit.setText("A5")
    t._search_enter(); pump()
    assert t.search.count.text() == "1 / 1"
    assert t.view.current_page == 6

    # 확대·맞춤
    z = t.view.zoom
    t.view.zoom_in(); pump()
    assert t.view.zoom > z and t.view.fit_mode is None
    t.view.fit("page"); pump()
    assert t.view.fit_mode == "page"

    # 고른 쪽 내보내기(대화 상자는 정해진 경로로 대신)
    out = str(tmp_path / "part.pdf")
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *a, **k: (out, "PDF"))
    assert t.export_selected([0, 7])
    import pymupdf
    assert labels_of(pymupdf.open(out)) == ["B1", "A6"]

    # 다른 이름으로 저장: 원본은 그대로
    before = open(a, "rb").read()
    out2 = str(tmp_path / "edited.pdf")
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *a, **k: (out2, "PDF"))
    assert t.save_as()
    assert open(a, "rb").read() == before
    assert not win.tabs.tabText(0).startswith("* ")

    # 저장(Ctrl+S): 묻지 않고 지금 이름(다른 이름으로 저장한 뒤라 edited.pdf)에 바로 저장
    t.rotate([0], 90); pump()
    asked = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: asked.append(1) or QMessageBox.StandardButton.No)
    assert win.a_save.shortcut().toString() == "Ctrl+S"
    win.a_save.trigger(); pump()
    assert not asked and not t.d.modified
    assert pymupdf.open(out2)[0].rotation == 90
    assert open(a, "rb").read() == before
    assert not win.tabs.tabText(0).startswith("* ")


def test_save_writes_current_file(win, tmp_path, monkeypatch):
    import pymupdf
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3"])
    win.open_path(a); pump()
    t = win.tab()
    t.move_pages([2], 0); pump()
    assert win.tabs.tabText(0).startswith("* ")
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: pytest.fail("묻지 않아야 함"))
    assert t.save()
    assert labels_of(pymupdf.open(a)) == ["A3", "A1", "A2"]
    assert not t.d.modified and t.d.path == os.path.abspath(a)
    assert not win.tabs.tabText(0).startswith("* ")
    # 도구 막대에 '저장'이 '다른 이름으로 저장' 앞에 있고, 다른 이름으로 저장은 Ctrl+Shift+S
    tb = win.findChildren(QToolBar)[0]
    names = [x.text() for x in tb.actions()]
    assert names.index("저장") < names.index("다른 이름으로 저장")
    assert win.a_save_as.shortcut().toString() == "Ctrl+Shift+S"


def test_save_as_refuses_file_open_in_other_tab(win, tmp_path, monkeypatch):
    """다른 탭에 열린 파일로 다른 이름 저장·내보내기를 하면 막는다(그 탭의 옛 내용 Ctrl+S로 지워지는 일 방지)."""
    import pymupdf
    a = make_pdf(tmp_path / "가.pdf", ["A1"])
    b = make_pdf(tmp_path / "나.pdf", ["B1", "B2"])
    win.open_path(a); win.open_path(b); pump()
    ta = win.tabs.widget(0)
    warned = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *x, **k: warned.append(x[1]) or QMessageBox.StandardButton.No)
    monkeypatch.setattr(QMessageBox, "question", lambda *x, **k: QMessageBox.StandardButton.Yes)
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *x, **k: (b, "PDF"))
    assert not ta.save_as()
    assert not ta.export_selected([0])
    assert warned == ["다른 이름으로 저장", "고른 쪽 내보내기"]
    assert labels_of(pymupdf.open(b)) == ["B1", "B2"]
    # 폴더 링크로 같은 파일을 가리켜도 막는다
    alias = tmp_path / "별칭"
    try:
        os.symlink(tmp_path, alias, target_is_directory=True)
    except OSError:  # 윈도우는 개발자 모드가 아니면 링크를 못 만든다
        alias = None
    if alias:
        monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *x, **k: (str(alias / "나.pdf"), "PDF"))
        assert not ta.save_as()
        assert not ta.export_selected([0])
        assert labels_of(pymupdf.open(b)) == ["B1", "B2"]
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *x, **k: (b, "PDF"))
    # 그 탭을 닫으면 저장된다
    win.tabs.removeTab(1); pump()
    assert ta.save_as()
    assert labels_of(pymupdf.open(b)) == ["A1"]


def test_move_by_internal_drop(win, tmp_path, monkeypatch):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3", "A4", "A5"])
    win.open_path(a); pump()
    t = win.tab()
    th = t.thumbs
    assert th.viewport().acceptDrops(), "viewport가 놓기를 안 받으면 실제 마우스 놓기가 무시됨"
    # 실제 끌기 시작(startDrag)이 담는 내용을 그대로 받아 놓기에 쓴다
    sent = {}

    def fake_exec(self, *a, **k):
        sent["mime"] = self.mimeData()
        return Qt.DropAction.CopyAction
    monkeypatch.setattr(QDrag, "exec", fake_exec)
    th.clearSelection()
    th.item(0).setSelected(True)
    th.startDrag(Qt.DropAction.CopyAction)
    mime = sent["mime"]
    send_drag(th, mime, gap_point(th, 3))  # 1쪽을 4쪽 앞으로
    assert labels_of(t.d.doc) == ["A2", "A3", "A1", "A4", "A5"]
    assert th.count() == 5 and th.selected_pages() == [2]
    # 여러 쪽을 맨 앞으로
    th.clearSelection()
    th.item(3).setSelected(True); th.item(4).setSelected(True)
    th.startDrag(Qt.DropAction.CopyAction)
    send_drag(th, sent["mime"], gap_point(th, 0))
    assert labels_of(t.d.doc) == ["A4", "A5", "A2", "A3", "A1"]


def test_drop_on_page_takes_its_place(win, tmp_path, monkeypatch):
    """쪽 그림 한가운데에 놓으면 그 쪽 자리를 차지한다(1쪽을 3쪽 위에 → 2 3 1)."""
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3", "A4", "A5"])
    win.open_path(a); pump()
    t = win.tab()
    th = t.thumbs
    center = lambda row: th.visualItemRect(th.item(row)).center()  # noqa: E731
    send_drag(th, th.pages_mime([0]), center(2))  # 아래로: 3쪽 뒤
    assert labels_of(t.d.doc) == ["A2", "A3", "A1", "A4", "A5"]
    assert th.selected_pages() == [2]
    send_drag(th, th.pages_mime([4]), center(1))  # 위로: 2쪽 앞(그 자리)
    assert labels_of(t.d.doc) == ["A2", "A5", "A3", "A1", "A4"]
    send_drag(th, th.pages_mime([0, 1]), center(4))  # 여러 쪽을 맨 끝 쪽 위에: 맨 뒤로
    assert labels_of(t.d.doc) == ["A3", "A1", "A4", "A2", "A5"]
    # 끄는 동안 주황 줄도 같은 자리를 가리킨다
    args = (Qt.DropAction.CopyAction, th.pages_mime([0]), Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
    QApplication.sendEvent(th.viewport(), QDragEnterEvent(center(2), *args))
    QApplication.sendEvent(th.viewport(), QDragMoveEvent(center(2), *args))
    assert th._drop_row == 3
    th._stop_drag()


def test_other_tab_pages_not_accepted(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2"])
    b = make_pdf(tmp_path / "b.pdf", ["B1", "B2"])
    win.open_paths([a, b]); pump()
    ta, tb = win.tabs.widget(0), win.tabs.widget(1)
    mime = ta.thumbs.pages_mime([0])  # A 탭의 쪽 번호를 B 탭에 놓아도 B가 바뀌면 안 됨
    vp = tb.thumbs.viewport()
    ev = QDragEnterEvent(QPoint(5, 5), Qt.DropAction.CopyAction, mime,
                         Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
    QApplication.sendEvent(vp, ev)
    assert not ev.isAccepted()
    assert labels_of(tb.d.doc) == ["B1", "B2"]


def test_rotate_buttons_on_toolbar(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3"])
    win.open_path(a); pump()
    t = win.tab()
    tb_actions = [a.text() for tb in win.findChildren(QToolBar) for a in tb.actions()]
    assert "↻ 시계 90°" in tb_actions and "↺ 반시계 90°" in tb_actions
    t.thumbs.clearSelection(); t.thumbs.item(1).setSelected(True)
    win.a_rot_r.trigger(); pump()
    assert [p.rotation for p in t.d.doc] == [0, 90, 0]
    win.a_rot_l.trigger(); win.a_rot_l.trigger(); pump()
    assert [p.rotation for p in t.d.doc] == [0, 270, 0]
    t.thumbs.clearSelection(); t.view.go_to_page(2)
    win.a_rot_r.trigger(); pump()  # 고른 쪽이 없으면 지금 보는 쪽
    assert t.d.doc[2].rotation == 90


# ── 1차 검토(opus) 지적 회귀 시험 ──
def test_rejected_edit_keeps_view_on_live_doc(win, tmp_path, monkeypatch):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3"])
    win.open_path(a); pump()
    t = win.tab()
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: QMessageBox.StandardButton.Ok)
    t.delete_pages([0, 1, 2]); pump()  # 모든 쪽 지우기는 거절됨
    assert t.view.doc is t.d.doc and t.thumbs.doc is t.d.doc
    t.view.zoom_in(); t.view._render_visible(); pump()  # 닫힌 문서면 여기서 오류
    assert labels_of(t.d.doc) == ["A1", "A2", "A3"]


def test_drop_in_gap_and_side_space(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", [f"A{i}" for i in range(1, 7)])
    win.open_path(a); pump()
    th = win.tab().thumbs
    r0, r1 = th.visualItemRect(th.item(0)), th.visualItemRect(th.item(1))
    gap_y = (r0.bottom() + r1.top()) // 2
    assert th._row_at(QPoint(r0.center().x(), gap_y)) == 1
    assert th._row_at(QPoint(th.viewport().width() - 3, r1.top() + 2)) == 1
    assert th._row_at(QPoint(th.viewport().width() - 3, r1.bottom() - 2)) == 2


def test_close_with_failed_save_keeps_window(win, tmp_path, monkeypatch):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2"])
    win.open_path(a); pump()
    t = win.tab()
    t.rotate([0], 90); pump()
    from pdfviewer.core.doc_ops import DocOpError

    def fail(*a, **k):
        raise DocOpError("저장하지 못했습니다: 시험")
    monkeypatch.setattr("pdfviewer.core.document.doc_ops.atomic_save", fail)
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **k: QMessageBox.StandardButton.Save)
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: QMessageBox.StandardButton.Ok)
    assert not win.close()
    assert win.isVisible() and t.d.modified


def test_encrypted_save_asks_first(win, tmp_path, monkeypatch):
    import pymupdf
    enc = tmp_path / "enc.pdf"
    d = pymupdf.open(); d.new_page(); d.save(enc, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="pw", owner_pw="ow")
    monkeypatch.setattr("pdfviewer.ui.main_window.ask_password", lambda *a: "pw")
    win.open_path(str(enc)); pump()
    t = win.tab()
    called = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: called.append(1) or QMessageBox.StandardButton.No)
    out = str(tmp_path / "o.pdf")
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *a, **k: (out, "PDF"))
    before = open(enc, "rb").read()
    assert not t.save_as() and not t.export_selected([0]) and not t.save()
    assert len(called) == 3 and not os.path.exists(out)
    assert open(enc, "rb").read() == before
    # 확인하고 한 번 저장하면 파일에 암호가 없으므로 다음 저장부터는 묻지 않는다
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: called.append(1) or QMessageBox.StandardButton.Yes)
    assert t.save() and len(called) == 4
    assert t.save() and len(called) == 4


def test_noop_drop_is_not_an_edit(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3"])
    win.open_path(a); pump()
    t = win.tab()
    t.move_pages([1], 1); pump()
    assert not t.d.modified and not t.d.can_undo()


def test_drag_autoscroll_at_edges(win, tmp_path):
    import time
    from PySide6.QtGui import QDragMoveEvent
    a = make_pdf(tmp_path / "a.pdf", [f"A{i}" for i in range(1, 31)])
    b = make_pdf(tmp_path / "b.pdf", ["B1"])
    win.open_path(a); pump()
    th = win.tab().thumbs
    mime = QMimeData(); mime.setUrls([QUrl.fromLocalFile(b)])
    bottom = QPointF(20, th.viewport().height() - 5)
    th.dragMoveEvent(QDragMoveEvent(bottom.toPoint(), Qt.DropAction.CopyAction, mime,
                                    Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier))
    end = time.time() + 0.6
    while time.time() < end:
        app.processEvents(); time.sleep(0.01)
    down = th.verticalScrollBar().value()
    assert down > 0
    from PySide6.QtGui import QCursor
    assert th._drop_row == th._row_at(th.viewport().mapFromGlobal(QCursor.pos()))
    th._stop_drag()
    assert not th._scroll_timer.isActive()


def test_thumb_layout_stays_after_landscape_rotation(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", [f"A{i}" for i in range(1, 6)])
    win.open_path(a); pump()
    t = win.tab()
    t.rotate([1, 3], 90); pump()
    th = t.thumbs
    rects = [th.visualItemRect(th.item(i)) for i in range(th.count())]
    assert len({r.height() for r in rects}) == 1  # 가로 쪽이 섞여도 칸 크기 같음
    assert all(b.top() >= a.bottom() for a, b in zip(rects, rects[1:]))  # 겹치지 않음
    # 고른 쪽 그림이 회색으로 덮이지 않도록 고른 모드에도 실제 그림이 들어 있음
    from PySide6.QtGui import QIcon
    ic = th.item(1).icon()
    n = ic.pixmap(th.iconSize(), QIcon.Mode.Normal).toImage()
    s = ic.pixmap(th.iconSize(), QIcon.Mode.Selected).toImage()
    assert n == s


# ── 2단계: 형광펜·메모·지우개 ──
def _annots(t, pno):
    page = t.d.doc[pno]
    return [(a.type[1], a.info.get("content", "")) for a in page.annots()]


def _screen_rect_of(t, pno, word):
    """그 낱말이 화면(쪽 위젯)에 보이는 사각형."""
    from PySide6.QtCore import QRectF
    page = t.d.doc[pno]
    (r,) = page.search_for(word)
    r = r * page.rotation_matrix
    z = t.view.zoom
    return QRectF(r.x0 * z, r.y0 * z, r.width * z, r.height * z)


def _annot_pdf(tmp_path):
    import pymupdf
    d = pymupdf.open()
    for i in (1, 2):
        p = d.new_page()
        p.insert_text((72, 120), f"Alpha{i} Beta{i}", fontsize=24)
    path = str(tmp_path / "n.pdf")
    d.save(path)
    return path


def test_highlight_by_mouse_drag_then_undo(win, tmp_path):
    from PySide6.QtTest import QTest
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    win.a_mode["highlight"].trigger(); pump()
    assert t.view.mode == "highlight"
    scroll = t.view.verticalScrollBar().value()
    w = t.view.pages[0]
    r = _screen_rect_of(t, 0, "Beta1")
    QTest.mousePress(w, Qt.MouseButton.LeftButton, pos=(r.topLeft() + QPointF(-2, -2)).toPoint())
    QTest.mouseMove(w, (r.bottomRight() + QPointF(2, 2)).toPoint())
    QTest.mouseRelease(w, Qt.MouseButton.LeftButton, pos=(r.bottomRight() + QPointF(2, 2)).toPoint())
    pump()
    assert [a[0] for a in _annots(t, 0)] == ["Highlight"]
    assert t.d.modified and win.tabs.tabText(0).startswith("* ")
    assert t.view.verticalScrollBar().value() == scroll  # 칠한 뒤에도 보던 자리 그대로
    t.undo(); pump()
    assert _annots(t, 0) == []


def test_note_add_open_edit_and_erase(win, tmp_path, monkeypatch):
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QInputDialog
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    answers = iter([("임대료 확인", True), ("임대료 다시 확인", True)])
    monkeypatch.setattr(QInputDialog, "getMultiLineText", lambda *a, **k: next(answers))
    win.a_mode["note"].trigger(); pump()
    w = t.view.pages[1]
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(200, 300)); pump()
    assert _annots(t, 1) == [("Text", "임대료 확인")]
    # 보기 모드에서 두 번 누르면 고치기
    win.a_mode["view"].trigger(); pump()
    page = t.d.doc[1]
    (a,) = list(page.annots())
    c = (a.rect * page.rotation_matrix).tl * t.view.zoom
    QTest.mouseDClick(w, Qt.MouseButton.LeftButton, pos=QPoint(int(c.x) + 3, int(c.y) + 3)); pump()
    assert _annots(t, 1) == [("Text", "임대료 다시 확인")]
    # 지우개: 빈 곳은 아무 일 없음, 메모 자리는 지워짐
    win.a_mode["erase"].trigger(); pump()
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(20, 20)); pump()
    assert len(_annots(t, 1)) == 1
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(int(c.x) + 3, int(c.y) + 3)); pump()
    assert _annots(t, 1) == []


def test_tool_mode_follows_tab_switch_and_esc(win, tmp_path):
    a = _annot_pdf(tmp_path)
    b = make_pdf(tmp_path / "b.pdf", ["B1"])
    win.open_paths([a, b]); pump()
    win.set_hl_color("pink"); pump()
    win.tabs.setCurrentIndex(0); pump()
    t = win.tab()
    assert t.view.mode == "highlight" and t.view.hl_color == "pink"
    win.a_mode["view"].trigger(); pump()
    assert t.view.mode == "view"
    assert win.a_mode["view"].shortcut().toString() == "Esc"


def test_broken_page_drag_data_ignored(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2"])
    win.open_path(a); pump()
    th = win.tab().thumbs
    from pdfviewer.ui.thumbs import MIME_PAGES
    for bad in (b"\xff\xfe", f"{id(th)}:x".encode(), f"{id(th)}:0,99".encode()):
        mime = QMimeData()
        mime.setData(MIME_PAGES, bad)
        assert th._internal_pages(mime) is None


# ── 3단계: 자료 폴더 창·두 판 비교 ──
def _im_root(tmp_path):
    root = tmp_path / "브리핑자료"
    b = root / "가나빌딩 브리핑자료"
    b.mkdir(parents=True)
    make_pdf(b / "가나빌딩_rev1.pdf", ["R1-1", "R1-2", "R1-3"])
    make_pdf(b / "가나빌딩_rev2.pdf", ["R2-1", "R2-2", "R2-3", "R2-4"])
    make_pdf(b / "건축물 대장.pdf", ["대장"])
    return root


def test_im_panel_open_and_compare_previous_rev(win, tmp_path):
    root = _im_root(tmp_path)
    win.im_panel.set_root(str(root)); pump()
    tree = win.im_panel.tree
    top = tree.topLevelItem(0)
    assert top.text(0) == "가나빌딩 브리핑자료"
    first = top.child(0)
    assert first.text(0).startswith("★ 최신") and first.font(0).bold()
    win.im_panel._open_item(first); pump()
    assert win.tab().d.name == "가나빌딩_rev2.pdf"
    # 고른 두 파일 비교: 예전 rev가 왼쪽
    tree.clearSelection()
    top.child(0).setSelected(True); top.child(1).setSelected(True)
    win.im_panel._compare_selected(); pump()
    cmp = win.tabs.currentWidget()
    assert isinstance(cmp, CompareTab)
    assert [d.name for d in cmp.docs] == ["가나빌딩_rev1.pdf", "가나빌딩_rev2.pdf"]
    # 비교 탭에서는 편집 동작이 꺼지고 보기 동작만
    assert win.tab() is None
    assert not win.a_rot_r.isEnabled() and not win.a_mode["highlight"].isEnabled()
    assert win.a_zoom_in.isEnabled() and win.a_next.isEnabled()
    # 같이 넘기기
    cmp.views[0].go_to_page(2); pump()
    assert cmp.views[1].current_page == 2
    cmp.views[1].go_to_page(3); pump()  # 왼쪽은 3쪽뿐이라 따라가지 않음(넘치지 않게)
    assert cmp.views[0].current_page == 2
    cmp.sync.setChecked(False)
    cmp.views[0].go_to_page(0); pump()
    assert cmp.views[1].current_page == 3
    # 확대는 양쪽 모두
    z = [v.zoom for v in cmp.views]
    win.a_zoom_in.trigger(); pump()
    assert all(v.zoom > old for v, old in zip(cmp.views, z))
    # 닫기: 저장 질문 없이 닫히고 원본은 그대로
    before = open(root / "가나빌딩 브리핑자료" / "가나빌딩_rev1.pdf", "rb").read()
    win.close_tab(win.tabs.currentIndex()); pump()
    assert not any(isinstance(win.tabs.widget(i), CompareTab) for i in range(win.tabs.count()))
    assert open(root / "가나빌딩 브리핑자료" / "가나빌딩_rev1.pdf", "rb").read() == before


def test_im_panel_missing_root_message(win, tmp_path):
    win.im_panel.set_root(str(tmp_path / "없는 폴더")); pump()
    assert win.im_panel.tree.topLevelItemCount() == 0
    assert "찾지 못했습니다" in win.im_panel.root_label.text()


# ── Codex 8판 지적 회귀 시험 ──
def _cmp(win, tmp_path, n_left=5, n_right=7):
    a = make_pdf(tmp_path / "old_rev1.pdf", [f"L{i}" for i in range(n_left)])
    b = make_pdf(tmp_path / "new_rev2.pdf", [f"R{i}" for i in range(n_right)])
    win.open_compare(a, b); pump()
    return win.tabs.currentWidget()


def test_compare_next_prev_moves_one_page(win, tmp_path):
    cmp = _cmp(win, tmp_path)
    cmp.views[0].go_to_page(1); pump()
    assert [v.current_page for v in cmp.views] == [1, 1]
    win.a_next.trigger(); pump()
    assert [v.current_page for v in cmp.views] == [2, 2]
    win.a_prev.trigger(); pump()
    assert [v.current_page for v in cmp.views] == [1, 1]
    cmp.sync.setChecked(False)
    win.a_next.trigger(); pump()  # 따로 넘기기면 양쪽 각자 한 쪽씩
    assert [v.current_page for v in cmp.views] == [2, 2]


def test_compare_resync_on_toggle(win, tmp_path):
    cmp = _cmp(win, tmp_path)
    cmp.sync.setChecked(False)
    cmp.views[0].go_to_page(1); cmp.views[1].go_to_page(4); pump()
    assert [v.current_page for v in cmp.views] == [1, 4]
    cmp.sync.setChecked(True); pump()
    assert [v.current_page for v in cmp.views] == [1, 1]


def test_window_close_closes_compare_docs(win, tmp_path):
    cmp = _cmp(win, tmp_path)
    docs = [d.doc for d in cmp.docs]
    assert win.close()
    pump()
    assert all(d.is_closed for d in docs)


def test_drop_file_on_page_view_inserts(win, tmp_path, monkeypatch):
    """오른쪽 큰 쪽 화면에 PDF를 놓으면 새 탭 대신 놓은 쪽 앞(위 절반)·뒤(아래 절반)에 넣는다."""
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3"])
    b = make_pdf(tmp_path / "b.pdf", ["B1"])
    win.open_path(a); pump()
    t = win.tab()
    v = t.view
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(b)])

    monkeypatch.setattr("pdfviewer.ui.page_view.DROP_EDGE", 0)  # 자리 계산만 보려고 여기서는 자동 굴림을 끈다(굴림은 아래 시험)

    v.set_zoom(0.25); v.verticalScrollBar().setValue(0); pump()  # 쪽을 작게 해 한 화면에 다 보이게

    def at(page, frac):
        w = v.pages[page]
        p = w.mapTo(v.viewport(), QPoint(w.width() // 2, int(w.height() * frac)))
        assert 0 <= p.y() < v.viewport().height()
        return p

    args = (Qt.DropAction.CopyAction | Qt.DropAction.MoveAction, mime,
            Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
    pos = at(1, 0.7)  # 2쪽 아래 절반 → 2쪽 뒤
    for ev in (QDragEnterEvent(pos, *args), QDragMoveEvent(pos, *args)):
        QApplication.sendEvent(v.viewport(), ev)
        assert ev.isAccepted()
    assert v._drop_line.isVisible()
    QApplication.sendEvent(v.viewport(), QDropEvent(QPointF(pos), *args)); pump()
    assert win.tabs.count() == 1
    assert labels_of(t.d.doc) == ["A1", "A2", "B1", "A3"]
    assert not v._drop_line.isVisible()
    v.verticalScrollBar().setValue(0); pump()
    pos = at(0, 0.3)  # 1쪽 위 절반 → 맨 앞
    for ev in (QDragEnterEvent(pos, *args), QDragMoveEvent(pos, *args), QDropEvent(QPointF(pos), *args)):
        QApplication.sendEvent(v.viewport(), ev)
    pump()
    assert labels_of(t.d.doc) == ["B1", "A1", "A2", "B1", "A3"]


def test_compare_view_does_not_take_file_drop(win, tmp_path):
    a = make_pdf(tmp_path / "a_rev1.pdf", ["A1"])
    b = make_pdf(tmp_path / "a_rev2.pdf", ["B1"])
    win.open_compare(a, b); pump()
    cmp = win.tabs.currentWidget()
    assert isinstance(cmp, CompareTab)
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(b)])
    for v in cmp.views:  # 놓기를 안 받으면 Qt가 창(새 탭으로 열기)으로 넘긴다
        assert not v.acceptDrops() and not v.viewport().acceptDrops()
        assert not v._accepts_drop(type("E", (), {"mimeData": lambda self: mime})())
    got = []
    for v in cmp.views:
        v.insertFiles.connect(lambda paths, at: got.append((paths, at)))
        pos = v.viewport().rect().center()
        args = (Qt.DropAction.CopyAction, mime, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
        enter, drop = QDragEnterEvent(pos, *args), QDropEvent(QPointF(pos), *args)
        v.dragEnterEvent(enter)
        v.dropEvent(drop)
        assert not enter.isAccepted() and not drop.isAccepted()
    pump()
    assert got == []  # 비교 화면은 끼워 넣기 신호를 내지 않는다
    assert [d.page_count for d in cmp.docs] == [1, 1]  # 비교 문서는 그대로


def test_page_view_drop_keeps_scrolling_at_edge(win, tmp_path):
    """오른쪽 화면에 PDF를 끌고 아래 가장자리에 커서를 멈춰 두어도 계속 굴러 내려간다."""
    import time
    a = make_pdf(tmp_path / "a.pdf", [f"A{i}" for i in range(1, 13)])
    b = make_pdf(tmp_path / "b.pdf", ["B1"])
    win.open_path(a); pump()
    v = win.tab().view
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(b)])
    pos = QPoint(v.viewport().width() // 2, v.viewport().height() - 5)
    args = (Qt.DropAction.CopyAction, mime, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
    for ev in (QDragEnterEvent(pos, *args), QDragMoveEvent(pos, *args)):
        QApplication.sendEvent(v.viewport(), ev)
    start = v.verticalScrollBar().value()
    end = time.time() + 0.6
    while time.time() < end:
        QApplication.processEvents()
    assert v.verticalScrollBar().value() > start + 100
    QApplication.sendEvent(v.viewport(), QDragLeaveEvent())
    assert not v._drop_timer.isActive() and not v._drop_line.isVisible()


def test_redo_button_and_shortcuts(win, tmp_path):
    from PySide6.QtGui import QKeySequence
    assert win.windowTitle() == "한장PDF"
    a = make_pdf(tmp_path / "a.pdf", ["A1", "A2", "A3"])
    win.open_path(a); pump()
    t = win.tab()
    assert win.windowTitle().endswith(" - 한장PDF")
    tb_actions = [x for tb in win.findChildren(QToolBar) for x in tb.actions()]
    assert win.a_undo in tb_actions and win.a_redo in tb_actions
    assert QKeySequence("Ctrl+Z") in win.a_undo.shortcuts()
    assert QKeySequence("Ctrl+Y") in win.a_redo.shortcuts()
    assert not win.a_undo.isEnabled() and not win.a_redo.isEnabled()
    t.delete_pages([0]); pump()
    assert win.a_undo.isEnabled() and not win.a_redo.isEnabled()
    win.a_undo.trigger(); pump()
    assert labels_of(t.d.doc) == ["A1", "A2", "A3"]
    assert not win.a_undo.isEnabled() and win.a_redo.isEnabled()
    win.a_redo.trigger(); pump()
    assert labels_of(t.d.doc) == ["A2", "A3"] and t.thumbs.count() == 2
    assert win.a_undo.isEnabled() and not win.a_redo.isEnabled()
    win.a_undo.trigger(); pump()
    t.rotate([0], 90); pump()  # 새 편집 뒤에는 다시 하기가 꺼진다
    assert not win.a_redo.isEnabled()
    win.a_redo.trigger(); pump()
    assert labels_of(t.d.doc) == ["A1", "A2", "A3"]


def _drag(w, a, b):
    from PySide6.QtTest import QTest
    QTest.mousePress(w, Qt.MouseButton.LeftButton, pos=a.toPoint())
    QTest.mouseMove(w, ((a + b) / 2).toPoint())
    QTest.mouseMove(w, b.toPoint())
    QTest.mouseRelease(w, Qt.MouseButton.LeftButton, pos=b.toPoint())
    pump()


def test_drag_select_text_and_copy(win, tmp_path):
    from PySide6.QtTest import QTest
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    assert t.view.mode == "view"
    clip = QApplication.clipboard()
    clip.setText("전")
    win.a_copy.trigger(); pump()  # 고른 것이 없으면 복사하지 않음
    assert clip.text() == "전"
    w = t.view.pages[0]
    _drag(w, _screen_rect_of(t, 0, "Alpha1").center(), _screen_rect_of(t, 0, "Beta1").center())
    assert t.view.pages[0].sel_rects and "Ctrl+C" in win.statusBar().currentMessage()
    win.a_copy.trigger(); pump()
    assert clip.text() == "Alpha1 Beta1"
    assert not t.d.modified and not t.d.can_undo()  # 고르기는 편집이 아님
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(5, 5)); pump()  # 그냥 누르면 고른 것 풀림
    assert not t.view.pages[0].sel_rects and t.view.selection_seq == 0
    # 돌린 쪽에서도 화면에 보이는 낱말을 고른다
    t.rotate([1], 90); pump()
    w1 = t.view.pages[1]
    r = _screen_rect_of(t, 1, "Beta2")
    _drag(w1, r.center(), r.center() + QPointF(0, 2) + QPointF(r.width() / 4, 0))
    win.a_copy.trigger(); pump()
    assert clip.text() == "Beta2"
    # 형광펜 모드에서 끌면 고르지 않고 칠한다
    win.a_mode["highlight"].trigger(); pump()
    assert t.view.selection_seq == 0
    # 찾기 칸에서는 Ctrl+C가 그 칸 글자를 복사한다
    t.show_search(); t.search.edit.setText("찾기글"); t.search.edit.selectAll(); pump()
    QTest.keyClick(t.search.edit, Qt.Key.Key_C, Qt.KeyboardModifier.ControlModifier); pump()
    assert clip.text() == "찾기글"


def test_select_text_in_compare_tab_copies_last_selected(win, tmp_path):
    a = make_pdf(tmp_path / "a.pdf", ["A1"])
    b = make_pdf(tmp_path / "b.pdf", ["B1"])
    win.open_compare(a, b); pump()
    cmp = win.tabs.currentWidget()
    assert isinstance(cmp, CompareTab)
    for v, word in ((cmp.views[0], "A1"), (cmp.views[1], "B1")):
        page = v.doc[0]
        (r,) = page.search_for(word)
        z = v.zoom
        c = QPointF((r.x0 + r.x1) / 2 * z, (r.y0 + r.y1) / 2 * z)
        _drag(v.pages[0], c - QPointF(3, 0), c + QPointF(3, 0))
    win.a_copy.trigger(); pump()
    assert QApplication.clipboard().text() == "B1"


def test_selection_follows_zoom(win, tmp_path):
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    t.rotate([0], 90); pump()
    w = t.view.pages[0]
    r = _screen_rect_of(t, 0, "Alpha1")
    _drag(w, r.center(), r.center() + QPointF(0, 6))
    before = t.view.pages[0].sel_rects[0]
    assert before.intersects(_screen_rect_of(t, 0, "Alpha1"))
    t.view.set_zoom(t.view.zoom * 2); pump()
    after = t.view.pages[0].sel_rects[0]
    want = _screen_rect_of(t, 0, "Alpha1")
    assert abs(after.width() - before.width() * 2) < 1 and after.intersects(want)
    assert abs(after.center().x() - want.center().x()) < 3 and abs(after.center().y() - want.center().y()) < 3


def test_compare_tab_scan_page_hint(win, tmp_path):
    import pymupdf
    a = make_pdf(tmp_path / "a.pdf", ["A1"])
    blank = str(tmp_path / "blank.pdf")
    d = pymupdf.open(); d.new_page(); d.save(blank); d.close()
    win.open_compare(a, blank); pump()
    v = win.tabs.currentWidget().views[1]
    _drag(v.pages[0], QPointF(50, 50), QPointF(90, 60))
    assert "글자 정보가 없어" in win.statusBar().currentMessage()


def _type_in_editor(t, text, size=None, align=None, color=None):
    ed = t.view._editor
    assert ed is not None and ed.edit.isVisible()
    ed.edit.selectAll()
    ed.edit.insertPlainText(text)  # 직접 친 것처럼(칸 안 되돌리기 기록이 남게)
    if size:
        ed.size.setValue(size)
    if align:
        ed.align.setCurrentIndex([ed.align.itemData(i) for i in range(ed.align.count())].index(align))
    if color:
        ed.set_color(color)
    return ed


def test_text_tool_drag_box_type_edit_erase(win, tmp_path):
    import pymupdf
    from PySide6.QtTest import QTest
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    win.a_mode["text"].trigger(); pump()
    assert t.view.mode == "text" and "글자" in win.statusBar().currentMessage()
    w = t.view.pages[0]
    _drag(w, QPointF(150, 380), QPointF(400, 430))
    ed = _type_in_editor(t, "넣은 글자\n둘째 줄", 18, "center", "#d00000")
    z = t.view.zoom
    assert abs(ed.edit.x() - 150) <= 1 and abs(ed.edit.y() - 380) <= 1  # 끈 자리에 칸이 뜸
    assert t.d.doc[0].first_annot is None  # 다 쓰기 전에는 문서를 바꾸지 않음
    QTest.keyClick(ed.edit, Qt.Key.Key_Return, Qt.KeyboardModifier.ControlModifier); pump()
    assert t.view._editor is None
    page = t.d.doc[0]
    a = page.first_annot
    assert a.type[0] == pymupdf.PDF_ANNOT_FREE_TEXT and a.info["content"] == "넣은 글자\n둘째 줄"
    from pdfviewer.core import annots
    shown = annots.text_style(t.d.doc, 0, a.xref)["rect"]  # 글이 보이는 자리 = 끈 자리
    assert abs(shown.x0 - 150 / z) < 1 and abs(shown.x1 - 400 / z) < 1 and abs(shown.y0 - 380 / z) < 1
    assert t.d.modified and win.a_undo.isEnabled()
    # 넣은 글자를 누르면 그 자리에서 지금 값으로 고치기
    r = shown * z
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(int(r.x0 + 5), int(r.y0 + 5))); pump()
    ed = t.view._editor
    assert ed is not None and ed.values() == ("넣은 글자\n둘째 줄", 18, "center", "#d00000")
    ed.edit.setPlainText("고친 글자")
    # 칸 밖을 누르면 넣고 끝(그 누름으로 새 상자를 만들지 않음)
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(50, 700)); pump()
    assert t.view._editor is None
    page = t.d.doc[0]
    assert [x.info["content"] for x in page.annots()] == ["고친 글자"]
    # Esc는 고치지 않고 닫음(보기 모드로 바뀌지도 않음)
    r = annots.text_style(t.d.doc, 0, page.first_annot.xref)["rect"] * z
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(int(r.x0 + 5), int(r.y0 + 5))); pump()
    t.view._editor.edit.setPlainText("버릴 글")
    QTest.keyClick(t.view._editor.edit, Qt.Key.Key_Escape); pump()
    assert t.view._editor is None and t.view.mode == "text"
    page = t.d.doc[0]
    assert [x.info["content"] for x in page.annots()] == ["고친 글자"]
    # 보기 모드에서 두 번 누르면 고치기 칸
    win.a_mode["view"].trigger(); pump()
    QTest.mouseDClick(w, Qt.MouseButton.LeftButton, pos=QPoint(int(r.x0 + 5), int(r.y0 + 5))); pump()
    assert t.view._editor is not None and t.view._editor.values()[0] == "고친 글자"
    t.view._editor.cancel(); pump()
    # 지우개
    win.a_mode["erase"].trigger(); pump()
    QTest.mouseClick(w, Qt.MouseButton.LeftButton, pos=QPoint(int(r.x0 + 5), int(r.y0 + 5))); pump()
    page = t.d.doc[0]
    assert list(page.annots()) == []
    t.undo(); pump()
    page = t.d.doc[0]
    assert [x.info["content"] for x in page.annots()] == ["고친 글자"]


def test_text_click_opens_default_box_and_mode_change_commits(win, tmp_path):
    from PySide6.QtTest import QTest
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    win.a_mode["text"].trigger(); pump()
    QTest.mouseClick(t.view.pages[0], Qt.MouseButton.LeftButton, pos=QPoint(100, 500)); pump()
    _type_in_editor(t, "모드 바꾸면 넣어짐")
    win.a_mode["view"].trigger(); pump()
    assert t.view._editor is None
    page = t.d.doc[0]
    assert [x.info["content"] for x in page.annots()] == ["모드 바꾸면 넣어짐"]
    # 빈 글은 아무것도 넣지 않음
    win.a_mode["text"].trigger(); pump()
    QTest.mouseClick(t.view.pages[0], Qt.MouseButton.LeftButton, pos=QPoint(100, 650)); pump()
    QTest.keyClick(t.view._editor.edit, Qt.Key.Key_Return, Qt.KeyboardModifier.ControlModifier); pump()
    page = t.d.doc[0]
    assert len(list(page.annots())) == 1


def test_editor_follows_zoom(win, tmp_path):
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    win.a_mode["text"].trigger(); pump()
    _drag(t.view.pages[0], QPointF(100, 100), QPointF(300, 150)); pump()
    z0 = t.view.zoom
    x0 = t.view._editor.edit.x()
    t.view.set_zoom(z0 * 2); pump()
    assert abs(t.view._editor.edit.x() - x0 * 2) <= 2
    t.view._editor.cancel()


def test_text_dialog_values():
    from pdfviewer.ui.text_dialog import TextDialog
    dlg = TextDialog(None, "글자 넣기", text="가", size=20, align="right", color="#0050c8")
    assert dlg.values() == ("가", 20, "right", "#0050c8")
    assert dlg.color_buttons["#0050c8"].isChecked()
    dlg.color_buttons["#d00000"].click()
    dlg.size.setValue(500)  # 범위 밖은 최대로 잘림
    assert dlg.values()[1] == 72 and dlg.values()[3] == "#d00000"


def test_print_includes_text_box(win, tmp_path):
    import pymupdf
    from PySide6.QtPrintSupport import QPrinter
    from pdfviewer.core import annots
    from pdfviewer.ui.printing import print_pages
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    assert win.a_print in [x for tb in win.findChildren(QToolBar) for x in tb.actions()]
    t.d.edit(annots.add_text, 1, pymupdf.Point(100, 500), "인쇄되는 빨간 글자", 28, "left", "#d00000")
    out = str(tmp_path / "printed.pdf")
    pr = QPrinter(QPrinter.PrinterMode.HighResolution)
    pr.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
    pr.setOutputFileName(out)
    assert print_pages(pr, t.d.doc, [0, 1]) == 2
    with pymupdf.open(out) as p:
        assert p.page_count == 2
        pix = p[1].get_pixmap(dpi=40)
        s = pix.samples
        red = sum(1 for i in range(0, len(s), pix.n) if s[i] > 150 and s[i + 1] < 90 and s[i + 2] < 90)
        assert red > 20  # 둘째 쪽 인쇄물에 빨간 글자 상자가 찍힘
        pix0 = p[0].get_pixmap(dpi=40)
        s0 = pix0.samples
        assert sum(1 for i in range(0, len(s0), pix0.n) if s0[i] > 150 and s0[i + 1] < 90 and s0[i + 2] < 90) == 0


def test_text_too_long_reopens_editor_with_typed_text(win, tmp_path, monkeypatch):
    from PySide6.QtTest import QTest
    import pdfviewer.ui.doc_tab as dt
    long_text = "가" * 3000
    warned = []
    monkeypatch.setattr(dt.QMessageBox, "warning", lambda *a, **k: warned.append(a[2]))
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    win.a_mode["text"].trigger(); pump()
    _drag(t.view.pages[0], QPointF(100, 300), QPointF(300, 350))
    ed = _type_in_editor(t, long_text, 18)
    QTest.keyClick(ed.edit, Qt.Key.Key_Return, Qt.KeyboardModifier.ControlModifier); pump()
    assert warned and "다 들어가지 않습니다" in warned[0]
    assert t.view._editor is not None and t.view._editor.values()[0] == long_text  # 쓴 글이 날아가지 않음
    t.view._editor.edit.setPlainText("짧게")
    QTest.keyClick(t.view._editor.edit, Qt.Key.Key_Return, Qt.KeyboardModifier.ControlModifier); pump()
    page = t.d.doc[0]
    assert [a.info["content"] for a in page.annots()] == ["짧게"]


def test_erase_by_drag_area(win, tmp_path):
    import pymupdf
    from pdfviewer.core import annots
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    t.d.edit(annots.add_note, 0, pymupdf.Point(100, 200), "a")
    t.d.edit(annots.add_note, 0, pymupdf.Point(150, 220), "b")
    t.d.edit(annots.add_note, 0, pymupdf.Point(400, 700), "멀리")
    t.refresh(); pump()
    win.a_mode["erase"].trigger(); pump()
    z = t.view.zoom
    _drag(t.view.pages[0], QPointF(80 * z, 180 * z), QPointF(200 * z, 260 * z))
    page = t.d.doc[0]
    assert [a.info["content"] for a in page.annots()] == ["멀리"]
    assert "2개" in win.statusBar().currentMessage()
    t.undo(); pump()
    page = t.d.doc[0]
    assert len(list(page.annots())) == 3
    # 빈 곳을 끌면 아무것도 바뀌지 않고 안내만
    n_undo = t.d.can_undo()
    _drag(t.view.pages[0], QPointF(300 * z, 300 * z), QPointF(350 * z, 350 * z))
    assert "없습니다" in win.statusBar().currentMessage() and t.d.can_undo() == n_undo


def test_box_tool_draw_and_recolor(win, tmp_path):
    import pymupdf
    from PySide6.QtTest import QTest
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    btn = [b for tb in win.findChildren(QToolBar) for b in [tb.widgetForAction(win.a_mode["box"])] if b]
    assert btn and btn[0].menu() is win.box_menu  # '상자' 옆 ▾에 색 메뉴
    win.box_actions[("fill", "#fff3a0")].trigger(); pump()
    assert t.view.mode == "box" and t.view.box_style["fill"] == "#fff3a0"
    z = t.view.zoom
    _drag(t.view.pages[0], QPointF(300 * z, 500 * z), QPointF(450 * z, 600 * z))
    page = t.d.doc[0]
    a = page.first_annot
    assert a.type[0] == pymupdf.PDF_ANNOT_SQUARE
    pix = t.d.doc[0].get_pixmap(dpi=72, clip=pymupdf.Rect(374, 549, 376, 551))
    assert tuple(pix.samples[:3]) == (255, 243, 160)
    # 색을 바꾸고 그린 상자를 누르면 그 색으로
    win.box_actions[("fill", "#cfe3ff")].trigger(); pump()
    QTest.mouseClick(t.view.pages[0], Qt.MouseButton.LeftButton, pos=QPoint(int(375 * z), int(550 * z))); pump()
    pix = t.d.doc[0].get_pixmap(dpi=72, clip=pymupdf.Rect(374, 549, 376, 551))
    assert tuple(pix.samples[:3]) == (207, 227, 255)
    page = t.d.doc[0]
    assert len(list(page.annots())) == 1
    # 다른 탭으로 열어도 같은 색 설정이 따라감
    win.open_path(make_pdf(tmp_path / "x.pdf", ["X"])); pump()
    assert win.tab().view.box_style["fill"] == "#cfe3ff"


def test_select_drag_in_table_cell_copies_only_that_cell(win, tmp_path):
    import pymupdf
    from test_text_select import _table_page
    d, p = _table_page()
    path = str(tmp_path / "tbl.pdf")
    d.save(path)
    d.close()
    win.open_path(path); pump()
    t = win.tab()
    page = t.d.doc[0]
    a = page.search_for("Mapo")[0]
    b = page.search_for("401")[0]
    z = t.view.zoom
    _drag(t.view.pages[0], QPointF(a.x0 * z + 2, a.y0 * z + 3), QPointF(b.x1 * z - 2, b.y1 * z - 3))
    assert t.view.selected_text() == "Mapo Seogyo\nStreet 401"


@pytest.mark.parametrize("text", ["노란 상자 안 가운데 글자입니다 줄이 바뀌는지 봅니다",
                                  "Seoul Gangnam-gu Yeoksam-dong 123-4 building", "가" * 30])
@pytest.mark.parametrize("size", [10, 14, 24])
@pytest.mark.parametrize("zoom", [None, 0.25, 0.6, 2.0])
def test_editor_breaks_lines_like_result(win, tmp_path, text, size, zoom):
    """쓰는 칸의 줄 수가 넣은 뒤 상자의 줄 수와 같아야(같은 글꼴 폭·같은 안쪽 폭, 낮은 배율에서도)."""
    import pymupdf
    from pdfviewer.core import annots
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    if zoom:
        t.view.set_zoom(zoom); pump()
    win.a_mode["text"].trigger(); pump()
    box = pymupdf.Rect(60, 300, 280, 340)
    t.view.open_text_editor(0, box, text=text, size=size, align="left"); pump()
    ed = t.view._editor
    shown = ed.visual_rows()
    # 넣는 쪽이 쓰는 칸의 줄을 다시 나누지 않고 그대로 받아들여야(그래야 결과 = 미리보기)
    rows, _, _ = annots._fixed_rect(t.d.doc[0], box, text, size, shown)
    assert rows == shown
    assert ed.edit.verticalScrollBar().value() == 0 and ed.edit.height() >= ed.edit.document().size().height()
    ed.cancel()


def test_editor_text_kept_on_save_undo_and_close(win, tmp_path, monkeypatch):
    import pymupdf
    import pdfviewer.ui.doc_tab as dt
    from pdfviewer.core import annots
    warned = []
    monkeypatch.setattr(dt.QMessageBox, "warning", lambda *a, **k: warned.append(a[2]))
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    t.d.edit(annots.add_note, 1, pymupdf.Point(100, 200), "먼저 한 편집"); t.refresh(); pump()
    win.a_mode["text"].trigger(); pump()
    # 쓰는 중에 도구 막대 되돌리기: 문서는 그대로, 칸 안의 글만 되돌림(다시 하기로 돌아옴)
    _drag(t.view.pages[0], QPointF(100, 100), QPointF(300, 150))
    _type_in_editor(t, "쓰는 중")
    win.a_undo.trigger(); pump()
    assert t.view._editor is not None and t.view._editor.values()[0] == ""
    assert [a.info["content"] for a in t.d.doc[1].annots()] == ["먼저 한 편집"]
    win.a_redo.trigger(); pump()
    assert t.view._editor.values()[0] == "쓰는 중"
    # 저장하면 쓰던 글을 먼저 넣고 저장
    t.save(); pump()
    with pymupdf.open(t.d.path) as r:
        assert [a.info["content"] for a in r[0].annots()] == ["쓰는 중"]
        assert [a.info["content"] for a in r[1].annots()] == ["먼저 한 편집"]
    assert t.view._editor is None and not t.d.modified
    # 닫을 때도 쓰던 글을 편집으로 쳐서 물어봄
    _drag(t.view.pages[0], QPointF(100, 300), QPointF(300, 350))
    _type_in_editor(t, "닫기 전")
    asked = []
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **k: (asked.append(a[2]), QMessageBox.StandardButton.Cancel)[1])
    win.close_tab(0); pump()
    assert asked and win.tabs.count() == 1, (warned, asked)
    page = t.d.doc[0]
    assert sorted(a.info["content"] for a in page.annots()) == ["닫기 전", "쓰는 중"]
    assert warned == []


@pytest.mark.parametrize("action", ["close", "save", "save_as", "export", "print"])
def test_failed_editor_commit_stops_save_close(win, tmp_path, monkeypatch, action):
    """쓰던 글을 넣지 못하면(너무 많음) 저장·내보내기·인쇄·닫기를 멈추고 쓴 글을 지킨다."""
    import pymupdf
    import pdfviewer.ui.doc_tab as dt
    warned = []
    monkeypatch.setattr(dt.QMessageBox, "warning", lambda *a, **k: warned.append(a[2]))
    called = []
    monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *a, **k: (called.append("dlg"), ("", ""))[1])
    monkeypatch.setattr(dt, "ask_and_print", lambda *a, **k: called.append("print") or 1)
    path = _annot_pdf(tmp_path)
    win.open_path(path); pump()
    t = win.tab()
    win.a_mode["text"].trigger(); pump()
    _drag(t.view.pages[0], QPointF(100, 300), QPointF(300, 350))
    long_text = "가" * 3000
    _type_in_editor(t, long_text, 18)
    if action == "close":
        win.close_tab(0); pump()
        assert win.tabs.count() == 1
    else:
        assert not getattr(t, {"save": "save", "save_as": "save_as", "export": "export_selected",
                               "print": "print_doc"}[action])()
    assert warned and "다 들어가지 않습니다" in warned[0]
    assert called == []  # 저장 창·인쇄로 넘어가지 않음
    assert t.view._editor is not None and t.view._editor.values()[0] == long_text
    with pymupdf.open(path) as r:
        assert list(r[0].annots()) == []


def test_editing_existing_box_then_undo_redo_keeps_one_box(win, tmp_path):
    """기존 상자를 고치는 중 되돌리기·다시 하기를 해도 상자가 둘로 늘지 않아야."""
    import pymupdf
    from pdfviewer.core import annots
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    x = t.d.edit(annots.add_text_box, 0, pymupdf.Rect(100, 100, 300, 140), "ORIGINAL", 14, "left", "#000000")
    t.refresh(); pump()
    st = annots.text_style(t.d.doc, 0, x)
    t.view.open_text_editor(0, st["rect"], x, text="ORIGINAL", size=14, align="left", color="#000000"); pump()
    ed = t.view._editor
    ed.edit.selectAll(); ed.edit.insertPlainText("EDITED DRAFT"); pump()
    t.undo(); pump()  # 단추 켜짐 여부와 상관없이 되돌리기·다시 하기를 실제로 부름(Ctrl+Z·Ctrl+Y와 같음)
    t.redo(); pump()
    assert t.view._editor.values()[0] == "EDITED DRAFT"
    t.view._editor.commit(); pump()
    assert [a.info["content"] for a in t.d.doc[0].annots()] == ["EDITED DRAFT"]


def test_page_ops_put_typed_text_first(win, tmp_path):
    """쪽 지우기·돌리기 등 쪽 편집 전에 쓰던 글을 먼저 넣는다: 쪽 번호가 바뀌어도 글이 엉뚱한 쪽으로 가지 않고,
    쓰던 쪽을 지웠어도 되돌리기로 글과 쪽이 함께 돌아온다."""
    import pymupdf
    src = pymupdf.open()
    for i in range(3):
        src.new_page().insert_text((72, 120), f"Page{i}", fontsize=24)
    path = str(tmp_path / "three.pdf")
    src.save(path)
    src.close()
    win.open_path(path); pump()
    t = win.tab()
    n = t.d.doc.page_count
    t.view.open_text_editor(1, pymupdf.Rect(100, 100, 300, 140), text="둘째 쪽 글"); pump()
    t.delete_pages([0]); pump()
    assert t.view._editor is None and t.d.doc.page_count == n - 1
    assert [a.info["content"] for a in t.d.doc[0].annots()] == ["둘째 쪽 글"]
    t.view.open_text_editor(0, pymupdf.Rect(100, 200, 300, 240), text="지울 쪽 글"); pump()
    t.delete_pages([0]); pump()
    assert t.view._editor is None and t.d.doc.page_count == n - 2
    t.undo(); pump()
    assert sorted(a.info["content"] for a in t.d.doc[0].annots()) == ["둘째 쪽 글", "지울 쪽 글"]


@pytest.mark.parametrize("zoom", [1.0, 0.5])
def test_editor_at_page_bottom_shows_where_text_lands(win, tmp_path, zoom):
    """쪽 맨 아래에서 여러 줄을 쓰면 넣은 글은 위로 밀려 들어가므로, 쓰는 칸도 같은 높이에 보여야."""
    import pymupdf
    d = pymupdf.open()
    d.new_page(width=600, height=800)
    path = str(tmp_path / "bottom.pdf")
    d.save(path)
    d.close()
    win.open_path(path); pump()
    t = win.tab()
    t.view.set_zoom(zoom); pump()
    text = "BOTTOM\nSECOND\nTHIRD\nFOURTH"
    t.view.open_text_editor(0, pymupdf.Rect(60, 770, 280, 790), text=text, size=24, align="left"); pump()
    top = t.view._editor.edit.geometry().y() / t.view.zoom
    t.view.finish_editor(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    glyph = t.d.doc[0].search_for("BOTTOM")[0]
    assert abs(top - glyph.y0) < 4, (top, glyph.y0)


_WRAP_TEXTS = ["Seoul Gangnam-gu Yeoksam-dong 123-4 building",
               "노란 상자 안 가운데 글자입니다 줄이 바뀌는지 봅니다",
               "서울 강남구 역삼동 123-4 Building A, 3층 상가",
               "가" * 40, "x" * 60]


@pytest.mark.parametrize("text", _WRAP_TEXTS)
@pytest.mark.parametrize("width,size,zoom", [(200, 12, 1.0), (220, 10, 0.25), (150, 14, 0.6), (300, 24, 2.0),
                                              (60, 12, 0.25), (66, 12, 0.25)])
def test_editor_line_breaks_equal_result_lines(win, tmp_path, text, width, size, zoom):
    """쓰는 칸에서 보이던 줄과 넣은 상자의 줄이 낱낱이 같아야(줄 수만이 아니라 꺾이는 자리까지)."""
    import pymupdf
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    t.view.set_zoom(zoom); pump()
    win.a_mode["text"].trigger(); pump()
    box = pymupdf.Rect(60, 300, 60 + width, 330)
    t.view.open_text_editor(0, box, text=text, size=size, align="left"); pump()
    shown = [r.strip() for r in t.view._editor.visual_rows()]
    t.view.finish_editor(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    page = t.d.doc[0]  # 쪽을 잡아 두어야 주석 객체가 살아 있음
    a = next(iter(page.annots()))
    drawn = [ln.strip() for ln in a.get_textpage().extractText().split("\n") if ln.strip()]
    assert drawn == shown


@pytest.mark.parametrize("size,zoom", [(72, 1.0), (10, 0.25), (10, 0.75), (12, 1.0), (14, 0.6), (24, 2.0)])
def test_editor_glyph_size_and_line_pitch_match_result(win, tmp_path, size, zoom):
    """쓰는 칸의 글자 크기·줄 간격이 넣은 결과와 같아야(큰 글자·낮은 배율 포함)."""
    import pymupdf
    d = pymupdf.open()
    d.new_page(width=600, height=2000)
    path = str(tmp_path / "big.pdf")
    d.save(path)
    d.close()
    win.open_path(path); pump()
    t = win.tab()
    t.view.set_zoom(zoom); pump()
    text = "\n".join(["Hello world"] * 6)
    t.view.open_text_editor(0, pymupdf.Rect(60, 100, 560, 140), text=text, size=size, align="left"); pump()
    ed = t.view._editor
    lay = ed.edit.document().documentLayout()
    blocks = [ed.edit.document().findBlockByNumber(i) for i in range(6)]
    tops = [lay.blockBoundingRect(b).top() for b in blocks]
    preview_pitch = (tops[-1] - tops[0]) / 5 / t.view.zoom
    first = blocks[0].layout().lineAt(0)
    preview_w = first.naturalTextWidth() / t.view.zoom  # 칸이 실제로 배치한 첫 줄 폭
    ed.commit(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    page = t.d.doc[0]
    hits = page.search_for("Hello world")
    result_pitch = (hits[-1].y0 - hits[0].y0) / 5
    result_w = hits[0].width
    assert abs(preview_pitch - result_pitch) <= 0.02 * result_pitch + 0.5, (preview_pitch, result_pitch)
    assert abs(preview_w - result_w) <= 0.03 * result_w + 0.5, (preview_w, result_w)


def test_editor_undo_button_tracks_only_typing(win, tmp_path):
    """칸을 막 열었을 때는 되돌릴 것이 없고, 크기·정렬을 바꿔도 되돌리기가 헛눌리지 않아야."""
    import pymupdf
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    t.view.open_text_editor(0, pymupdf.Rect(100, 100, 300, 140), text="기존 글"); pump()
    assert t.view.editor_can_undo() == (False, False) and not win.a_undo.isEnabled()
    ed = t.view._editor
    ed.edit.moveCursor(QTextCursor.MoveOperation.End)
    ed.edit.insertPlainText(" 추가"); pump()
    assert win.a_undo.isEnabled()
    ed.align.setCurrentIndex(1); pump()  # 정렬 바꾸기는 기록에 안 남음
    t.undo(); pump()
    assert ed.values()[0] == "기존 글" and not win.a_undo.isEnabled()
    t.redo(); pump()
    assert ed.values()[0] == "기존 글 추가"
    ed.cancel(); pump()


def test_legacy_click_box_edit_follows_preview(win, tmp_path):
    """예전 판에서 눌러 만든 글자 상자를 쓰는 칸에서 고치면 넣은 줄도 미리보기 그대로."""
    import pymupdf
    from pdfviewer.core import annots
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    x = t.d.edit(annots.add_text, 0, pymupdf.Point(100, 300), "OLD", 12, "left", "#000000")
    t.refresh(); pump()
    t.edit_text(0, x); pump()
    ed = t.view._editor
    ed.edit.selectAll()
    ed.edit.insertPlainText("Seoul Gangnam building street address repeated words"); pump()
    shown = [r.strip() for r in ed.visual_rows()]
    ed.commit(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    page = t.d.doc[0]
    a = next(iter(page.annots()))
    drawn = [ln.strip() for ln in a.get_textpage().extractText().split("\n") if ln.strip()]
    assert drawn == shown and len(list(page.annots())) == 1


@pytest.mark.parametrize("zoom", [1.0, 0.5])
def test_shortening_bottom_box_keeps_its_place(win, tmp_path, zoom):
    """쪽 맨 아래에서 밀려 올라간 기존 상자를 짧게 고쳐도 글이 쓰는 칸(보던 자리)에 그대로 있어야."""
    import pymupdf
    from pdfviewer.core import annots
    d = pymupdf.open()
    d.new_page(width=600, height=800)
    path = str(tmp_path / "bottom.pdf")
    d.save(path)
    d.close()
    win.open_path(path); pump()
    t = win.tab()
    t.view.set_zoom(zoom); pump()
    x = t.d.edit(annots.add_text_box, 0, pymupdf.Rect(60, 770, 280, 790), "BOTTOM\nSECOND\nTHIRD\nFOURTH",
                 24, "left", "#000000")
    t.refresh(); pump()
    before = t.d.doc[0].search_for("BOTTOM")[0].y0
    t.edit_text(0, x); pump()
    ed = t.view._editor
    ed.edit.selectAll()
    ed.edit.insertPlainText("ONE\nTWO"); pump()
    ed.commit(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    after = t.d.doc[0].search_for("ONE")[0].y0
    assert abs(after - before) < 2, (before, after)
    # 다시 열면 같은 자리에 칸이 뜨고, 바꾸지 않고 넣으면 그대로
    x2 = next(iter(t.d.doc[0].annots())).xref
    st = annots.text_style(t.d.doc, 0, x2)
    assert abs(st["edit_box"].y0 - st["rect"].y0) < 0.5


@pytest.mark.parametrize("size", [8, 12, 24, 48, 72])
@pytest.mark.parametrize("zoom", [0.5, 1.0, 2.0])
def test_editor_first_baseline_matches_result(win, tmp_path, size, zoom):
    """쓰는 칸 첫 줄 기준선이 넣은 결과의 첫 줄 기준선과 같은 높이여야(크기에 상관없이)."""
    import pymupdf
    d = pymupdf.open()
    d.new_page(width=600, height=2000)
    path = str(tmp_path / "base.pdf")
    d.save(path)
    d.close()
    win.open_path(path); pump()
    t = win.tab()
    t.view.set_zoom(zoom); pump()
    t.view.open_text_editor(0, pymupdf.Rect(60, 100, 560, 140), text="Hxg 가", size=size); pump()
    ed, z = t.view._editor, t.view.zoom
    line = ed.edit.document().firstBlock().layout().lineAt(0)
    prev = (ed.edit.geometry().y() + ed.edit.viewport().geometry().y() + ed.edit.document().documentMargin()
            + line.y() + line.ascent()) / z
    t.view.finish_editor(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    page = t.d.doc[0]
    a = next(iter(page.annots()))
    spans = [sp for b in a.get_textpage().extractDICT()["blocks"] for ln in b["lines"] for sp in ln["spans"]
             if sp["text"].strip()]
    assert abs(prev - spans[0]["origin"][1]) <= 1.5, (prev, spans[0]["origin"][1])


def _first_baselines(t, needle):
    """쓰는 칸 첫 줄 기준선(쪽 pt)과, 넣은 뒤 needle이 든 첫 span의 기준선."""
    ed, z = t.view._editor, t.view.zoom
    line = ed.edit.document().firstBlock().layout().lineAt(0)
    prev = (ed.edit.geometry().y() + ed.edit.viewport().geometry().y() + ed.edit.document().documentMargin()
            + line.y() + line.ascent()) / z
    t.view.finish_editor(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    page = t.d.doc[0]
    a = next(iter(page.annots()))
    spans = [sp for b in a.get_textpage().extractDICT()["blocks"] for ln in b["lines"] for sp in ln["spans"]
             if needle in sp["text"]]
    return prev, spans[0]["origin"][1]


@pytest.mark.parametrize("y0,size,n,zoom", [(583, 24, 7, 1.0), (591, 24, 7, 1.0), (616, 12, 12, 0.5),
                                             (700, 12, 20, 1.0)])
def test_editor_near_bottom_where_only_result_overflows(win, tmp_path, y0, size, n, zoom):
    """칸은 쪽 안에 들어가지만 넣을 상자만 쪽 아래로 넘치는 구간에서도 첫 줄 기준선이 같아야."""
    import pymupdf
    d = pymupdf.open()
    d.new_page(width=600, height=800)
    path = str(tmp_path / "near.pdf")
    d.save(path)
    d.close()
    win.open_path(path); pump()
    t = win.tab()
    t.view.set_zoom(zoom); pump()
    text = "\n".join(["First"] + ["Hello"] * (n - 1))
    t.view.open_text_editor(0, pymupdf.Rect(60, y0, 280, y0 + 20), text=text, size=size, align="left"); pump()
    prev, res = _first_baselines(t, "First")
    assert abs(prev - res) <= 1.5, (prev, res)


def test_editor_no_kerning_like_result(win, tmp_path):
    """MuPDF는 글자 쌍 간격을 줄이지 않으므로 미리보기 폭도 커닝 없이 결과와 같아야."""
    import pymupdf
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    t.view.open_text_editor(0, pymupdf.Rect(40, 100, 560, 140), text="AVAVAV Total Yoga", size=36); pump()
    ed = t.view._editor
    prev_w = ed.edit.document().firstBlock().layout().lineAt(0).naturalTextWidth() / t.view.zoom
    t.view.finish_editor(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    hits = t.d.doc[0].search_for("AVAVAV Total Yoga")
    res_w = hits[-1].x1 - hits[0].x0
    assert abs(prev_w - res_w) <= 0.02 * res_w, (prev_w, res_w)


def test_text_under_box_shape_still_editable(win, tmp_path):
    """글자 상자 둘레에 상자 도형을 그려도 글 위를 누르면 그 글자 상자를 고치는 칸이 떠야."""
    import pymupdf
    from pdfviewer.core import annots
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    x = t.d.edit(annots.add_text_box, 0, pymupdf.Rect(100, 300, 300, 330), "UNDER BOX", 12, "left", "#000000")
    t.d.edit(annots.add_box, 0, pymupdf.Rect(90, 290, 320, 360), "#0000ff", "#ffff00", 2, 0.5)
    t.refresh(); pump()
    c = t.d.doc[0].search_for("UNDER BOX")[0]
    t.text_at(0, pymupdf.Point((c.x0 + c.x1) / 2, (c.y0 + c.y1) / 2)); pump()
    assert t.view._editor is not None
    assert t.view._editor_at[2] == x


def test_rev8_box_edit_starts_where_text_shows(win, tmp_path):
    """예전 판(누른 곳 기준) 상자를 고쳐도 칸과 결과가 보이던 글자리에서 시작해야(위로 뛰지 않게)."""
    import pymupdf
    from pdfviewer.core import annots
    win.open_path(_annot_pdf(tmp_path)); pump()
    t = win.tab()
    x = t.d.edit(annots.add_text, 0, pymupdf.Point(100, 300), "OLD STYLE", 12, "left", "#000000")
    t.refresh(); pump()
    before = t.d.doc[0].search_for("OLD STYLE")[0].y0
    t.edit_text(0, x); pump()
    ed = t.view._editor
    ed.edit.selectAll()
    ed.edit.insertPlainText("NEW STYLE"); pump()
    ed.commit(); pump()
    QApplication.sendPostedEvents(None, 0); pump()
    after = t.d.doc[0].search_for("NEW STYLE")[0].y0
    assert abs(after - before) < 2, (before, after)


def _bg_and_darkest(widget) -> tuple[float, float]:
    """위젯 그림에서 가장 많은 색(바탕)의 밝기와 가장 어두운 점(글자)의 밝기."""
    from collections import Counter
    img = widget.grab().toImage()
    lums = [round((img.pixelColor(x, y).red() + img.pixelColor(x, y).green() + img.pixelColor(x, y).blue()) / 3)
            for y in range(img.height()) for x in range(img.width())]
    return Counter(lums).most_common(1)[0][0], min(lums)


def _readable(widget) -> bool:
    bg, darkest = _bg_and_darkest(widget)
    return bg >= 200 and darkest <= 80


def test_editor_bar_readable_in_dark_theme(win, tmp_path):
    """윈도우 어두운 테마(흰 글자 팔레트)에서도 쓰는 칸 위 막대의 글자가 밝은 바탕 위에 어둡게 보여야."""
    import pymupdf
    from PySide6.QtGui import QColor, QPalette
    app = QApplication.instance()
    old = app.palette()
    pal = QPalette(old)
    for role, col in ((QPalette.ColorRole.Window, "#202020"), (QPalette.ColorRole.WindowText, "#ffffff"),
                      (QPalette.ColorRole.Base, "#1e1e1e"), (QPalette.ColorRole.Text, "#ffffff"),
                      (QPalette.ColorRole.Button, "#2d2d2d"), (QPalette.ColorRole.ButtonText, "#ffffff")):
        pal.setColor(role, QColor(col))
    app.setPalette(pal)
    try:
        win.open_path(_annot_pdf(tmp_path)); pump()
        t = win.tab()
        t.view.open_text_editor(0, pymupdf.Rect(60, 300, 300, 330), text="x", size=12); pump()
        ed = t.view._editor
        for w in (ed.bar, ed.size, ed.align, ed.ok, ed.no):
            assert _readable(w), (w, _bg_and_darkest(w))
        # 색 메뉴·정렬 펼친 목록도 읽혀야
        menu = ed.color_btn.menu()
        menu.popup(ed.color_btn.mapToGlobal(ed.color_btn.rect().bottomLeft())); pump()
        assert _readable(menu), _bg_and_darkest(menu)
        menu.hide()
        ed.align.showPopup(); pump()
        view = ed.align.view()
        assert _readable(view), _bg_and_darkest(view)
        ed.align.hidePopup(); pump()
        # 흰색·노랑·연회색을 골라도 '색' 단추 글자가 보여야(밝은 색이면 어두운 바탕)
        for hexv in ("#ffffff", "#ffff00", "#aaaaaa"):
            ed.set_color(hexv); pump()
            bg, _ = _bg_and_darkest(ed.color_btn)
            c = QColor(hexv)
            assert abs((c.red() + c.green() + c.blue()) / 3 - bg) > 100, (hexv, bg)
    finally:
        app.setPalette(old)


def test_app_icon_file_loads():
    """앱 아이콘(창·작업 표시줄)이 들어 있고 Qt가 읽을 수 있어야."""
    import pdf_viewer
    from PySide6.QtGui import QIcon
    p = pdf_viewer.icon_path()
    assert os.path.exists(p)
    assert not QIcon(p).isNull()
    assert QIcon(p).availableSizes() or not QIcon(p).pixmap(32, 32).isNull()


def _picture_pdf(tmp_path, pages=2):
    """글자 정보 없이 선만 그린 쪽(세움터 건축물대장처럼 글자를 그림으로 바꾼 문서 흉내)."""
    import pymupdf
    d = pymupdf.open()
    for _ in range(pages):
        p = d.new_page(width=400, height=600)
        p.draw_rect(pymupdf.Rect(40, 60, 360, 200), color=(0, 0, 0), width=0.8)
    path = str(tmp_path / "picture.pdf")
    d.save(path)
    return path


def _fake_ocr(monkeypatch, calls):
    def factory():
        def run(rgb, w, h):
            calls.append((w, h))
            s = w / 400
            return [(60 * s, 90 * s, 80 * s, 14 * s, "대지위치"), (150 * s, 90 * s, 140 * s, 14 * s, "서울특별시")]
        return run, ""
    monkeypatch.setattr("pdfviewer.ui.doc_tab.ocr_engine", factory)


def test_ocr_button_adds_searchable_text_and_undo(win, tmp_path, monkeypatch):
    """글자 인식: 글자 없는 쪽마다 읽어 글자층을 넣고, 찾기·끌어 고르기가 되고, Ctrl+Z 한 번에 모두 되돌아감."""
    calls = []
    _fake_ocr(monkeypatch, calls)
    win.open_path(_picture_pdf(tmp_path)); pump()
    t = win.tab()
    assert win.a_ocr.isEnabled()
    win.a_ocr.trigger(); pump()
    assert len(calls) == 2  # 두 쪽 모두 읽음
    assert t.d.modified
    assert [p for p, _ in __import__("pdfviewer.core.doc_ops", fromlist=["x"]).search(t.d.doc, "서울특별시")] == [0, 1]
    t.view.set_mode("view")
    assert "서울특별시" in t.d.doc[0].get_text()
    # 이미 글자가 생긴 쪽은 다시 읽지 않는다
    infos = []
    monkeypatch.setattr(QMessageBox, "information", lambda *a, **k: infos.append(a[2]))
    win.a_ocr.trigger(); pump()
    assert len(calls) == 2 and infos and "읽을 쪽이 없습니다" in infos[-1]
    t.undo(); pump()
    assert not t.d.doc[0].get_text().strip() and not t.d.doc[1].get_text().strip()
    assert not t.d.modified


def test_ocr_without_engine_explains(win, tmp_path, monkeypatch):
    infos = []
    monkeypatch.setattr("pdfviewer.ui.doc_tab.ocr_engine", lambda: (None, "글자 인식은 윈도우에서만 쓸 수 있습니다."))
    monkeypatch.setattr(QMessageBox, "information", lambda *a, **k: infos.append(a[2]))
    win.open_path(_picture_pdf(tmp_path)); pump()
    win.a_ocr.trigger(); pump()
    assert infos == ["글자 인식은 윈도우에서만 쓸 수 있습니다."]
    assert not win.tab().d.modified


def test_ocr_engine_error_leaves_document(win, tmp_path, monkeypatch):
    warns = []

    def factory():
        def run(rgb, w, h):
            raise RuntimeError("엔진 고장")
        return run, ""
    monkeypatch.setattr("pdfviewer.ui.doc_tab.ocr_engine", factory)
    monkeypatch.setattr(QMessageBox, "warning", lambda *a, **k: warns.append(a[2]) or QMessageBox.StandardButton.Ok)
    win.open_path(_picture_pdf(tmp_path)); pump()
    win.a_ocr.trigger(); pump()
    assert warns and "엔진 고장" in warns[0]
    assert not win.tab().d.modified and not win.tab().d.doc[0].get_text().strip()


def test_no_text_hint_mentions_ocr():
    from pdfviewer.ui.page_view import select_hint
    assert "글자 인식" in select_hint(0)


def test_ocr_cancel_during_last_page_leaves_document(win, tmp_path, monkeypatch):
    """마지막 쪽을 읽는 도중 취소를 눌러도 글자층을 넣지 않는다."""
    from PySide6.QtWidgets import QProgressDialog

    def factory():
        def run(rgb, w, h):
            for dlg in QApplication.topLevelWidgets() + win.findChildren(QProgressDialog):
                if isinstance(dlg, QProgressDialog):
                    dlg.cancel()
            s = w / 400
            return [(60 * s, 90 * s, 80 * s, 14 * s, "취소시험")]
        return run, ""
    monkeypatch.setattr("pdfviewer.ui.doc_tab.ocr_engine", factory)
    win.open_path(_picture_pdf(tmp_path, pages=1)); pump()
    t = win.tab()
    assert t.recognize_text() == 0
    assert not t.d.modified and not t.d.doc[0].get_text().strip()


def test_help_menu_links_and_about(win, monkeypatch):
    """도움말: 부빅터·소스 코드는 브라우저로 열고, 정보 창에 라이선스(AGPL)와 소스 주소가 있다."""
    import pdfviewer.ui.main_window as mw
    opened, shown = [], []
    monkeypatch.setattr(mw, "open_url", opened.append)
    monkeypatch.setattr(QMessageBox, "about", lambda parent, title, text: shown.append(text))
    titles = [a.text() for a in win.menuBar().actions()]
    assert titles[-1] == "도움말"
    win.a_bubigter.trigger()
    win.a_source.trigger()
    win.a_about.trigger()
    assert opened == [mw.BUBIGTER_URL, mw.SOURCE_URL]
    assert opened[0].startswith("https://www.bubigter.com/") and opened[1].startswith("https://github.com/")
    assert "AGPL" in shown[0] and mw.SOURCE_URL in shown[0] and mw.APP_VERSION in shown[0]


def test_public_build_has_no_company_folder_paths():
    """공개 배포판: 자료 폴더 자동 찾기 후보에 회사 폴더 구조를 넣지 않는다."""
    from pdfviewer.core import im_index
    assert im_index.SUBPATHS == []
    assert im_index.find_root([os.path.expanduser("~")]) is None


def test_language_menu_saves_choice(win, monkeypatch):
    """도움말 > Language: 고르면 설정에 저장되고, 지금과 다른 언어면 다시 시작하라고 알린다."""
    told = []
    monkeypatch.setattr(QMessageBox, "information", lambda parent, title, text: told.append(text))
    assert win.a_lang["ko"].isChecked() and not win.a_lang["en"].isChecked()
    win.a_lang["en"].trigger()
    assert win.settings.value("ui/lang") == "en"
    assert told and "Restart" in told[0]
    win.a_lang["ko"].trigger()
    assert win.settings.value("ui/lang") == "ko"
    assert len(told) == 1  # 지금 언어를 다시 고르면 묻지 않음
