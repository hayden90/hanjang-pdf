"""쪽 편집·저장 시험. 쪽마다 'P<번호>' 글자를 넣은 가짜 PDF로 순서를 확인한다."""
import os
import sys

import pymupdf
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pdfviewer.core import doc_ops  # noqa: E402
from pdfviewer.core.doc_ops import DocOpError  # noqa: E402
from pdfviewer.core.document import Document  # noqa: E402


def make_pdf(path, labels):
    d = pymupdf.open()
    for lab in labels:
        p = d.new_page()
        p.insert_text((72, 72), lab)
    d.save(path)
    d.close()
    return str(path)


def labels_of(doc):
    return [p.get_text().strip() for p in doc]


@pytest.fixture
def five(tmp_path):
    return make_pdf(tmp_path / "five.pdf", ["A1", "A2", "A3", "A4", "A5"])


@pytest.fixture
def two(tmp_path):
    return make_pdf(tmp_path / "two.pdf", ["B1", "B2"])


def test_insert_at_middle_start_end(five, two):
    for at, expect in [
        (2, ["A1", "A2", "B1", "B2", "A3", "A4", "A5"]),
        (0, ["B1", "B2", "A1", "A2", "A3", "A4", "A5"]),
        (5, ["A1", "A2", "A3", "A4", "A5", "B1", "B2"]),
    ]:
        d = Document(five)
        assert d.edit(doc_ops.insert_pdf, two, at) == 2
        assert labels_of(d.doc) == expect
        assert d.modified


def test_insert_bad_position_keeps_doc(five, two):
    d = Document(five)
    with pytest.raises(DocOpError):
        d.edit(doc_ops.insert_pdf, two, 9)
    assert labels_of(d.doc) == ["A1", "A2", "A3", "A4", "A5"]
    assert not d.can_undo()


def test_export_selected_in_doc_order(five, tmp_path):
    d = Document(five)
    out = tmp_path / "out.pdf"
    assert d.export_pages([3, 0, 3], str(out)) == 2
    e = pymupdf.open(out)
    assert labels_of(e) == ["A1", "A4"]
    # 내보내기는 원본을 바꾸지 않는다
    assert labels_of(d.doc) == ["A1", "A2", "A3", "A4", "A5"]
    assert not d.modified


def test_export_refuses_original_path(five):
    d = Document(five)
    with pytest.raises(DocOpError):
        d.export_pages([0], five)


def test_delete_and_refuse_all(five):
    d = Document(five)
    d.edit(doc_ops.delete_pages, [1, 3])
    assert labels_of(d.doc) == ["A1", "A3", "A5"]
    with pytest.raises(DocOpError):
        d.edit(doc_ops.delete_pages, [0, 1, 2])
    assert labels_of(d.doc) == ["A1", "A3", "A5"]


@pytest.mark.parametrize("sel,to,expect,pos", [
    ([0], 3, ["A2", "A3", "A1", "A4", "A5"], 2),
    ([4], 0, ["A5", "A1", "A2", "A3", "A4"], 0),
    ([1, 3], 5, ["A1", "A3", "A5", "A2", "A4"], 3),
    ([1, 2], 1, ["A1", "A2", "A3", "A4", "A5"], 1),
    ([1, 2], 3, ["A1", "A2", "A3", "A4", "A5"], 1),
])
def test_move(five, sel, to, expect, pos):
    d = Document(five)
    assert d.edit(doc_ops.move_pages, sel, to) == pos
    assert labels_of(d.doc) == expect


def test_rotate_and_undo(five):
    d = Document(five)
    d.edit(doc_ops.rotate_pages, [0, 2], 90)
    assert [p.rotation for p in d.doc] == [90, 0, 90, 0, 0]
    d.edit(doc_ops.rotate_pages, [0], -90)
    assert d.doc[0].rotation == 0
    assert d.undo() and d.doc[0].rotation == 90
    assert d.undo() and d.doc[0].rotation == 0
    assert not d.undo()


def test_search_finds_page(five):
    d = Document(five)
    hits = doc_ops.search(d.doc, "A4")
    assert [h[0] for h in hits] == [3]
    assert doc_ops.search(d.doc, "  ") == []


def test_search_rect_follows_rotation(five):
    d = Document(five)
    d.edit(doc_ops.rotate_pages, [3], 90)
    (pno, r), = doc_ops.search(d.doc, "A4")
    page = d.doc[pno]
    # 돌린 쪽에서도 찾은 사각형이 보이는 쪽 안에 있어야 한다
    assert page.rect.contains(r)


def test_save_as_and_overwrite(five, two, tmp_path):
    before = open(five, "rb").read()
    d = Document(five)
    d.edit(doc_ops.insert_pdf, two, 1)
    new = tmp_path / "new.pdf"
    d.save_to(str(new))
    assert open(five, "rb").read() == before  # 다른 이름 저장은 원본을 건드리지 않음
    assert d.path == str(new) and not d.modified
    assert labels_of(pymupdf.open(new)) == ["A1", "B1", "B2", "A2", "A3", "A4", "A5"]
    # 원본 덮어쓰기(메모리에서 열었으므로 파일이 잠기지 않음)
    d2 = Document(five)
    d2.edit(doc_ops.delete_pages, [0])
    d2.save_to(five)
    assert labels_of(pymupdf.open(five)) == ["A2", "A3", "A4", "A5"]
    assert not [f for f in os.listdir(tmp_path) if f.endswith(".tmp")]


def test_broken_and_encrypted(tmp_path):
    bad = tmp_path / "bad.pdf"
    bad.write_bytes(b"not a pdf")
    with pytest.raises(DocOpError):
        Document(str(bad))
    enc = tmp_path / "enc.pdf"
    d = pymupdf.open()
    d.new_page().insert_text((72, 72), "S1")
    d.save(enc, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="pw", owner_pw="ow")
    with pytest.raises(DocOpError):
        Document(str(enc))
    assert labels_of(Document(str(enc), "pw").doc) == ["S1"]


# ── 1차 검토(opus) 지적 회귀 시험 ──
def test_save_to_unwritable_folder_is_docop_error(five, tmp_path):
    gone = tmp_path / "없는 폴더"  # 쓸 수 없는 곳(chmod는 윈도우에서 안 먹어서 없는 폴더로)
    d = Document(five)
    d.edit(doc_ops.rotate_pages, [0], 90)
    with pytest.raises(DocOpError):
        d.save_to(str(gone / "x.pdf"))
    assert d.modified  # 실패하면 저장된 것으로 치지 않음
    with pytest.raises(DocOpError):
        d.export_pages([0], str(gone / "y.pdf"))


def test_export_failure_keeps_existing_file(five, tmp_path, monkeypatch):
    target = tmp_path / "keep.pdf"
    target.write_bytes(b"OLD")

    def boom(self, *a, **k):
        raise RuntimeError("disk full")
    monkeypatch.setattr(pymupdf.Document, "save", boom)
    d = Document(five)
    with pytest.raises(DocOpError):
        d.export_pages([0], str(target))
    assert target.read_bytes() == b"OLD"
    assert not [f for f in os.listdir(tmp_path) if f.endswith(".tmp")]


def test_encrypted_flag(tmp_path):
    enc = tmp_path / "enc.pdf"
    d = pymupdf.open()
    d.new_page()
    d.save(enc, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="pw", owner_pw="ow")
    assert Document(str(enc), "pw").was_encrypted
    owner = tmp_path / "owner.pdf"
    d.save(owner, encryption=pymupdf.PDF_ENCRYPT_AES_256, owner_pw="ow", permissions=0)
    assert Document(str(owner)).was_encrypted  # 권한 암호만 걸린 문서
    plain = make_pdf(tmp_path / "plain.pdf", ["P1"])
    assert not Document(plain).was_encrypted


def test_undo_byte_budget(five, monkeypatch):
    import pdfviewer.core.document as docmod
    d = Document(five)
    one = len(d.doc.tobytes())
    monkeypatch.setattr(docmod, "UNDO_BYTES", one * 3 + 10)
    for _ in range(8):
        d.edit(doc_ops.rotate_pages, [0], 90)
    assert 1 <= len(d._undo) <= 3


@pytest.mark.parametrize("sel,to,noop", [
    ([1], 1, True), ([1], 2, True), ([1, 2], 3, True), ([1], 3, False), ([0, 2], 1, False), ([4], 5, True),
])
def test_noop_move(sel, to, noop):
    assert doc_ops.is_noop_move(5, sel, to) is noop


# ── Codex 6판 지적 회귀 시험: 쪽 옮기기가 입력 서식을 잃으면 안 됨 ──
def _form_pdf(path):
    d = pymupdf.open()
    for i in range(4):
        d.new_page().insert_text((72, 72), f"F{i}")
    w = pymupdf.Widget()
    w.field_type = pymupdf.PDF_WIDGET_TYPE_TEXT
    w.field_name = "name"
    w.field_value = "VALUE"
    w.rect = pymupdf.Rect(72, 100, 300, 130)
    d[0].add_widget(w)
    d.save(path)
    return str(path)


@pytest.mark.parametrize("sel,to", [([0], 4), ([0], 2), ([2, 3], 0), ([1, 3], 0), ([0, 2], 4)])
def test_move_keeps_form_fields(tmp_path, sel, to):
    src = _form_pdf(tmp_path / "form.pdf")
    d = Document(src)
    expect = doc_ops.move_order(4, sel, to)[0]
    d.edit(doc_ops.move_pages, sel, to)
    assert [p.get_text().split()[0] for p in d.doc] == [f"F{i}" for i in expect]
    out = tmp_path / "out.pdf"
    d.save_to(str(out))
    e = pymupdf.open(out)
    assert e.is_form_pdf
    pos = expect.index(0)
    pg = e[pos]
    (wd,) = list(pg.widgets())
    assert wd.field_value == "VALUE"
    # 옮긴 뒤 그 쪽만 내보내도 서식이 남음
    part = tmp_path / "part.pdf"
    d.export_pages([pos], str(part))
    p2 = pymupdf.open(part)
    assert p2.is_form_pdf


def test_redo_after_undo_and_new_edit_clears_it(five):
    d = Document(five)
    assert not d.can_redo() and not d.redo()
    d.edit(doc_ops.delete_pages, [0])
    d.edit(doc_ops.rotate_pages, [0], 90)
    assert d.undo() and d.undo()
    assert labels_of(d.doc)[0] == "A1" and d.can_redo()
    assert d.redo() and labels_of(d.doc)[0] == "A2" and d.doc[0].rotation == 0
    assert d.redo() and d.doc[0].rotation == 90 and not d.can_redo()
    assert d.undo() and d.can_redo()
    d.edit(doc_ops.rotate_pages, [1], 180)  # 새 편집을 하면 다시 하기 칸은 비운다
    assert not d.can_redo() and not d.redo()


def test_failed_edit_keeps_redo(five):
    d = Document(five)
    d.edit(doc_ops.rotate_pages, [0], 90)
    d.undo()
    with pytest.raises(DocOpError):
        d.edit(doc_ops.delete_pages, [0, 1, 2, 3, 4])
    assert d.can_redo() and d.redo() and d.doc[0].rotation == 90


def _history_bytes(d):
    return sum(len(b) for b, _ in d._undo) + sum(len(b) for b, _ in d._redo)


def test_undo_and_redo_share_byte_budget(five, monkeypatch):
    import pdfviewer.core.document as docmod
    d = Document(five)
    one = len(d.doc.tobytes())
    monkeypatch.setattr(docmod, "UNDO_BYTES", int(one * 3.5))
    for _ in range(4):
        d.edit(doc_ops.rotate_pages, [0], 90)
    d.edit(doc_ops.insert_pdf, five, 0)  # 더 큰 사본
    while d.undo():
        assert _history_bytes(d) <= docmod.UNDO_BYTES
    assert d.can_redo() and len(d._redo) < 5  # 한도 때문에 오래된 이력은 버려짐
    while d.redo():
        assert _history_bytes(d) <= docmod.UNDO_BYTES
    assert d.page_count == 10


def test_failed_edit_keeps_full_undo_history(five, monkeypatch):
    import pdfviewer.core.document as docmod
    monkeypatch.setattr(docmod, "UNDO_LIMIT", 3)
    d = Document(five)
    for _ in range(3):
        d.edit(doc_ops.rotate_pages, [0], 90)
    with pytest.raises(DocOpError):
        d.edit(doc_ops.delete_pages, [0, 1, 2, 3, 4])
    assert len(d._undo) == 3 and d.modified


def test_modified_follows_saved_state(five, tmp_path):
    d = Document(five)
    d.edit(doc_ops.rotate_pages, [0], 90)
    d.save_to(str(tmp_path / "s.pdf"))
    assert not d.modified
    assert d.undo() and d.modified
    assert d.redo() and not d.modified  # 저장한 그 상태로 돌아옴
    d.edit(doc_ops.rotate_pages, [1], 90)
    assert d.modified
    assert d.undo() and not d.modified
    d2 = Document(five)
    d2.edit(doc_ops.rotate_pages, [0], 90)
    assert d2.undo() and not d2.modified  # 연 그대로로 돌아옴


def test_budget_holds_with_one_undo_and_one_redo(five, monkeypatch):
    import pdfviewer.core.document as docmod
    d = Document(five)
    d.edit(doc_ops.rotate_pages, [0], 90)
    d.edit(doc_ops.insert_pdf, five, 0)
    a, b = (len(x) for x, _ in d._undo)
    monkeypatch.setattr(docmod, "UNDO_BYTES", a + b + 10)  # 둘은 들어가도 셋은 안 들어감
    assert d.undo()  # 되돌리기 하나 + 다시 하기 하나(10쪽 사본)
    assert _history_bytes(d) <= docmod.UNDO_BYTES
    assert d.can_redo()  # 방금 되돌린 것은 다시 할 수 있어야 함
    assert d.redo() and d.page_count == 10
    assert _history_bytes(d) <= docmod.UNDO_BYTES and d.can_undo()
