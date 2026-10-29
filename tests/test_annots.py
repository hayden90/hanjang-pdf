"""형광펜·메모 시험."""
import os
import sys

import pymupdf
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pdfviewer.core import annots, doc_ops  # noqa: E402
from pdfviewer.core.doc_ops import DocOpError  # noqa: E402
from pdfviewer.core.document import Document  # noqa: E402


def make(path, n=2):
    d = pymupdf.open()
    for i in range(1, n + 1):
        p = d.new_page()
        p.insert_text((72, 100), f"Alpha{i} Beta{i}", fontsize=20)
        p.insert_text((72, 400), f"Gamma{i}", fontsize=20)
    d.save(path)
    d.close()
    return str(path)


@pytest.fixture
def pdf(tmp_path):
    return make(tmp_path / "a.pdf")


def own(doc, pno):
    page = doc[pno]
    return [(a.type[0], a.info.get("content", "")) for a in page.annots()]


def word_rect(doc, pno, word):
    (r,) = doc[pno].search_for(word)
    return r


def test_highlight_words_only_in_dragged_area(pdf):
    d = Document(pdf)
    r = word_rect(d.doc, 0, "Alpha1")
    d.edit(annots.add_highlight, 0, r + (-2, -2, 2, 2), "green")
    pg = d.doc[0]  # 쪽을 붙잡아 둬야 주석을 읽을 수 있음(PyMuPDF)
    (a,) = list(pg.annots())
    assert a.type[0] == pymupdf.PDF_ANNOT_HIGHLIGHT
    assert a.rect.intersects(r) and not a.rect.intersects(word_rect(d.doc, 0, "Gamma1"))
    assert tuple(round(c, 2) for c in a.colors["stroke"]) == (0.55, 0.9, 0.45)
    assert d.modified


def test_highlight_blank_area_paints_rect(pdf):
    d = Document(pdf)
    d.edit(annots.add_highlight, 0, (300, 600, 400, 650), "yellow")
    pg = d.doc[0]  # 쪽을 붙잡아 둬야 주석을 읽을 수 있음(PyMuPDF)
    (a,) = list(pg.annots())
    # PDF 형광펜은 가장자리를 조금 넓혀 그림: 끈 영역을 덮되 크게 넘치지 않으면 됨
    assert a.rect.contains(pymupdf.Rect(300, 600, 400, 650))
    assert a.rect.width < 100 + 30 and a.rect.height < 50 + 10


def test_highlight_too_small_or_bad_color_rejected(pdf):
    d = Document(pdf)
    with pytest.raises(DocOpError):
        d.edit(annots.add_highlight, 0, (100, 100, 101, 101), "yellow")
    with pytest.raises(DocOpError):
        d.edit(annots.add_highlight, 0, (100, 100, 200, 200), "purple")
    assert own(d.doc, 0) == [] and not d.can_undo()


def test_screen_to_page_on_rotated_page(pdf):
    d = Document(pdf)
    d.edit(doc_ops.rotate_pages, [0], 90)
    page = d.doc[0]
    zoom = 1.5
    # 화면에 보이는 'Gamma1' 자리(돌린 뒤 좌표)를 화면 픽셀로 만든 뒤 다시 쪽 좌표로
    shown = word_rect(d.doc, 0, "Gamma1") * page.rotation_matrix
    p0 = annots.to_page(page, shown.x0 * zoom, shown.y0 * zoom, zoom)
    p1 = annots.to_page(page, shown.x1 * zoom, shown.y1 * zoom, zoom)
    d.edit(annots.add_highlight, 0, pymupdf.Rect(p0, p1), "yellow")
    pg = d.doc[0]  # 쪽을 붙잡아 둬야 주석을 읽을 수 있음(PyMuPDF)
    (a,) = list(pg.annots())
    assert a.rect.intersects(word_rect(d.doc, 0, "Gamma1"))
    assert not a.rect.intersects(word_rect(d.doc, 0, "Alpha1"))


def test_note_add_edit_lookup_delete(pdf):
    d = Document(pdf)
    x = d.edit(annots.add_note, 1, (200, 300), "  가격 다시 확인  ")
    info = annots.annot_at(d.doc, 1, (205, 305))
    assert info == {"xref": x, "type": pymupdf.PDF_ANNOT_TEXT, "text": "가격 다시 확인"}
    d.edit(annots.set_note_text, 1, x, "임대료 확인")
    assert own(d.doc, 1) == [(pymupdf.PDF_ANNOT_TEXT, "임대료 확인")]
    with pytest.raises(DocOpError):
        d.edit(annots.set_note_text, 1, x, "   ")
    d.edit(annots.delete_annot, 1, x)
    assert own(d.doc, 1) == []
    with pytest.raises(DocOpError):
        d.edit(annots.add_note, 1, (200, 300), " ")
    with pytest.raises(DocOpError):
        d.edit(annots.add_note, 1, (5000, 300), "쪽 밖")


def test_eraser_leaves_other_annotations(pdf):
    d = Document(pdf)
    pg = d.doc[0]
    sq = pg.add_rect_annot((100, 500, 200, 550))  # 원래 문서에 있던 다른 주석이라고 침
    sq.update()
    assert annots.annot_at(d.doc, 0, (150, 520)) is None
    with pytest.raises(DocOpError):
        d.edit(annots.delete_annot, 0, sq.xref)
    assert len(own(d.doc, 0)) == 1


def test_saved_annotations_survive_and_undo(pdf, tmp_path):
    d = Document(pdf)
    d.edit(annots.add_highlight, 0, word_rect(d.doc, 0, "Beta1"), "pink")
    d.edit(annots.add_note, 0, (300, 300), "메모")
    out = tmp_path / "out.pdf"
    d.save_to(str(out))
    e = pymupdf.open(out)
    ep = e[0]
    assert sorted(a.type[0] for a in ep.annots()) == [pymupdf.PDF_ANNOT_TEXT, pymupdf.PDF_ANNOT_HIGHLIGHT]
    assert d.undo() and len(own(d.doc, 0)) == 1
    assert d.undo() and own(d.doc, 0) == []
    # 원본은 그대로
    src = pymupdf.open(pdf)
    sp = src[0]
    assert not list(sp.annots())


# ── Codex 5판 지적 회귀 시험 ──
def test_foreign_highlight_and_note_are_protected(tmp_path):
    src = tmp_path / "ext.pdf"
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 100), "Alpha Beta", fontsize=20)
    (r,) = p.search_for("Alpha")
    h = p.add_highlight_annot(r)
    h.set_info(title="External")
    h.update()
    n = p.add_text_annot((300, 300), "원래 메모")
    n.set_info(title="External", content="원래 메모")
    n.update()
    d.save(src)
    doc = Document(str(src))
    assert annots.annot_at(doc.doc, 0, r.tl + (1, 1)) is None
    assert annots.annot_at(doc.doc, 0, (302, 302)) is None
    pg = doc.doc[0]
    xs = [a.xref for a in pg.annots()]
    for x in xs:
        with pytest.raises(DocOpError):
            doc.edit(annots.delete_annot, 0, x)
    with pytest.raises(DocOpError):
        doc.edit(annots.set_note_text, 0, xs[1], "바꿈")
    assert own(doc.doc, 0) == [(pymupdf.PDF_ANNOT_HIGHLIGHT, ""), (pymupdf.PDF_ANNOT_TEXT, "원래 메모")]
    # 같은 종류라도 이 프로그램이 넣은 것은 지울 수 있음
    mine = doc.edit(annots.add_note, 0, (100, 500), "내 메모")
    assert annots.annot_at(doc.doc, 0, (102, 502))["xref"] == mine
    doc.edit(annots.delete_annot, 0, mine)
    assert len(own(doc.doc, 0)) == 2


def test_cropped_page_edges_accepted(tmp_path):
    src = tmp_path / "crop.pdf"
    d = pymupdf.open()
    p = d.new_page(width=600, height=800)
    p.insert_text((110, 130), "Corner", fontsize=14)
    p.set_cropbox(pymupdf.Rect(100, 100, 500, 700))
    d.save(src)
    doc = Document(str(src))
    page = doc.doc[0]
    assert page.rect == pymupdf.Rect(0, 0, 400, 600)
    doc.edit(annots.add_note, 0, (20, 20), "모서리 메모")
    doc.edit(annots.add_highlight, 0, (5, 5, 70, 40), "yellow")
    with pytest.raises(DocOpError):
        doc.edit(annots.add_note, 0, (450, 650), "보이는 영역 밖")
    assert sorted(t for t, _ in own(doc.doc, 0)) == [pymupdf.PDF_ANNOT_TEXT, pymupdf.PDF_ANNOT_HIGHLIGHT]


def test_eraser_hits_only_painted_lines(tmp_path):
    src = tmp_path / "two.pdf"
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 100), "First", fontsize=20)
    p.insert_text((72, 400), "Last", fontsize=20)
    d.save(src)
    doc = Document(str(src))
    x = doc.edit(annots.add_highlight, 0, (65, 80, 115, 405), "yellow")
    assert annots.annot_at(doc.doc, 0, (80, 250)) is None  # 두 줄 사이 빈 곳
    (r,) = doc.doc[0].search_for("Last")
    assert annots.annot_at(doc.doc, 0, r.tl + (2, 2))["xref"] == x


# ── 글자 상자(텍스트 넣기) ──
def _ink(page, rect):
    """그 사각형 안의 어두운(글자) 화소 수."""
    pix = page.get_pixmap(dpi=72, clip=rect)
    return sum(1 for i in range(0, len(pix.samples), pix.n) if min(pix.samples[i:i + 3]) < 160)


def _red(page, rect):
    pix = page.get_pixmap(dpi=72, clip=rect)
    s = pix.samples
    return sum(1 for i in range(0, len(s), pix.n) if s[i] > 150 and s[i + 1] < 90 and s[i + 2] < 90)


def test_add_text_box_prints_and_survives_save(pdf, tmp_path):
    d = Document(pdf)
    blank = pymupdf.Rect(300, 200, 590, 380)
    assert _ink(d.doc[0], blank) == 0
    d.edit(annots.add_text, 0, pymupdf.Point(320, 220), "서울 강남구\n둘째 줄 <태그> & 기호", 18, "left", "#d00000")
    assert d.modified and d.can_undo()
    page = d.doc[0]  # 쪽을 잡아 두어야 주석 객체가 살아 있음
    a = next(page.annots())
    assert a.type[0] == pymupdf.PDF_ANNOT_FREE_TEXT
    assert a.flags & pymupdf.PDF_ANNOT_IS_PRINT  # 인쇄에 나옴
    assert a.info["content"] == "서울 강남구\n둘째 줄 <태그> & 기호"
    assert _red(d.doc[0], blank) > 50  # 빨간 글자가 실제로 그려짐
    out = tmp_path / "out.pdf"
    d.save_to(str(out))
    with pymupdf.open(out) as r:
        assert _red(r[0], blank) > 50
        assert "서울 강남구" in r[0].first_annot.info["content"]
        # 글꼴이 파일 안에 들어 있어야 다른 PC·프린터에서도 같은 모양
        assert any("FontFile" in r.xref_object(x) for x in range(1, r.xref_length()))
    d.undo()
    assert own(d.doc, 0) == [] and _ink(d.doc[0], blank) == 0


def test_text_style_size_align_color(pdf):
    d = Document(pdf)
    x = d.edit(annots.add_text, 0, pymupdf.Point(300, 600), "가운데", 24, "center", "#0050c8")
    st = annots.text_style(d.doc, 0, x)
    assert st["text"] == "가운데" and st["size"] == 24 and st["align"] == "center" and st["color"] == "#0050c8"
    page = d.doc[0]
    r = page.first_annot.rect
    assert abs((r.x0 + r.x1) / 2 - 300) < 1  # 가운데 정렬은 누른 곳이 상자 가운데
    x = d.edit(annots.add_text, 0, pymupdf.Point(500, 700), "오른쪽", 12, "right", "#000000")
    page = d.doc[0]
    r = [a for a in page.annots() if a.xref == x][0].rect
    assert abs(r.x1 - 500) < 1  # 오른쪽 정렬은 누른 곳이 상자 오른쪽 끝


def test_bigger_font_makes_bigger_box(pdf):
    d = Document(pdf)
    a = d.edit(annots.add_text, 0, pymupdf.Point(72, 500), "크기 비교 ABC", 10, "left", "#000000")
    b = d.edit(annots.add_text, 0, pymupdf.Point(72, 600), "크기 비교 ABC", 30, "left", "#000000")
    page = d.doc[0]
    ra, rb = ([x for x in page.annots() if x.xref == k][0].rect for k in (a, b))
    assert rb.width > ra.width * 2 and rb.height > ra.height  # 높이는 작은 글자도 최소 32pt


def test_edit_text_keeps_anchor_and_erase(pdf):
    d = Document(pdf)
    x = d.edit(annots.add_text, 0, pymupdf.Point(100, 500), "처음", 14, "left", "#000000")
    x2 = d.edit(annots.set_text, 0, x, "고친 글자 더 길게", 20, "left", "#138a36")
    st = annots.text_style(d.doc, 0, x2)
    assert st["text"] == "고친 글자 더 길게" and st["size"] == 20 and st["color"] == "#138a36"
    page = d.doc[0]
    assert len(list(page.annots())) == 1
    r = page.first_annot.rect
    assert abs(r.x0 - 100) < 1 and abs(r.y0 - 500) < 1
    info = annots.annot_at(d.doc, 0, r.tl + (5, 5))
    assert info and info["type"] == pymupdf.PDF_ANNOT_FREE_TEXT
    d.edit(annots.delete_annot, 0, info["xref"])
    assert own(d.doc, 0) == []


@pytest.mark.parametrize("args", [
    ("", 12, "left", "#000000"),
    ("글", 3, "left", "#000000"),
    ("글", 200, "left", "#000000"),
    ("글", 12, "middle", "#000000"),
    ("글", 12, "left", "red"),
])
def test_text_bad_input(pdf, args):
    d = Document(pdf)
    with pytest.raises(DocOpError):
        d.edit(annots.add_text, 0, pymupdf.Point(100, 100), *args)
    assert own(d.doc, 0) == []


def test_text_outside_page_rejected_and_box_stays_inside(pdf):
    d = Document(pdf)
    with pytest.raises(DocOpError):
        d.edit(annots.add_text, 0, pymupdf.Point(-50, 100), "밖", 12, "left", "#000000")
    d.edit(annots.add_text, 0, pymupdf.Point(590, 830), "오른쪽 아래 끝에 긴 글자", 20, "left", "#000000")
    page = d.doc[0]
    r = page.first_annot.rect
    assert page.rect.contains(r)  # 쪽 밖으로 넘치지 않게 안으로 밀어 넣음


@pytest.mark.parametrize("rot", [90, 180, 270])
def test_text_on_rotated_page_reads_upright(pdf, rot):
    d = Document(pdf)
    d.edit(doc_ops.rotate_pages, [0], rot)
    page = d.doc[0]
    vis = pymupdf.Point(150, 150)  # 화면에서 누른 곳
    pt = vis * page.derotation_matrix
    d.edit(annots.add_text, 0, pt, "WIDE TEXT LINE", 20, "left", "#d00000")
    page = d.doc[0]
    r = (page.first_annot.rect * page.rotation_matrix).normalize()  # 화면에 보이는 상자
    assert r.width > r.height * 2  # 화면에서 가로로 놓임(글자가 바로 섬)
    assert abs(r.x0 - 150) < 1 and abs(r.y0 - 150) < 1
    # 화면(돌린 쪽 그림)에 찍힌 빨간 글자 묶음도 가로로 길어야 글자가 바로 선 것
    pix = page.get_pixmap(dpi=72)
    smp = pix.samples
    xs, ys = [], []
    for i in range(0, len(smp), pix.n):
        if smp[i] > 150 and smp[i + 1] < 90 and smp[i + 2] < 90:
            k = i // pix.n
            xs.append(k % pix.width)
            ys.append(k // pix.width)
    assert xs and (max(xs) - min(xs)) > (max(ys) - min(ys)) * 3


def test_other_freetext_not_touched(pdf):
    d = Document(pdf)
    page = d.doc[0]
    a = page.add_freetext_annot(pymupdf.Rect(100, 600, 300, 640), "남의 글자")
    a.update()
    assert annots.annot_at(d.doc, 0, pymupdf.Point(110, 610)) is None
    with pytest.raises(DocOpError):
        d.edit(annots.set_text, 0, a.xref, "x", 12, "left", "#000000")


def _ap_text(doc, pno):
    page = doc[pno]
    return "".join(a.get_textpage().extractText() for a in page.annots())


def test_long_line_wraps_and_all_text_drawn(pdf, tmp_path):
    d = Document(pdf)
    text = "가" * 120 + "끝표식"
    d.edit(annots.add_text, 0, pymupdf.Point(100, 100), text, 18, "left", "#000000")
    out = tmp_path / "o.pdf"
    d.save_to(str(out))
    with pymupdf.open(out) as r:
        assert "".join(_ap_text(r, 0).split()) == text  # 줄이 바뀌어도 글자는 빠짐없이
        assert r[0].rect.contains(r[0].first_annot.rect)


def test_too_much_text_rejected_not_cut(pdf):
    d = Document(pdf)
    for text in ("가" * 2000 + "끝표식", "\n".join(f"줄{i:03d}" for i in range(80))):
        with pytest.raises(DocOpError, match="다 들어가지 않습니다"):
            d.edit(annots.add_text, 0, pymupdf.Point(100, 100), text, 18, "left", "#000000")
        assert own(d.doc, 0) == [] and not d.can_undo()
    # 많은 줄도 작은 글자면 들어감
    many = "\n".join(f"줄{i:03d}" for i in range(40))
    d.edit(annots.add_text, 0, pymupdf.Point(100, 50), many, 10, "left", "#000000")
    assert "".join(_ap_text(d.doc, 0).split()) == "".join(many.split())


@pytest.mark.parametrize("text", ["a < b > c & \"q\" 'r'", "앞  두칸   세칸", "첫 줄\n\n셋째 줄", "  앞 띄움"])
def test_special_text_drawn_fully(pdf, text):
    d = Document(pdf)
    d.edit(annots.add_text, 0, pymupdf.Point(100, 600), text, 14, "left", "#000000")
    assert "".join(_ap_text(d.doc, 0).split()) == "".join(text.split())


@pytest.mark.parametrize("size", [6, 7, 8, 9, 12, 24, 48, 72])
@pytest.mark.parametrize("text", ["ABC", "한글", "X" * 120, "가나다 " * 40, "a\nb\nc", "https://example.com/" + "p" * 80])
def test_every_size_fits_and_draws_all(pdf, size, text):
    d = Document(pdf)
    try:
        d.edit(annots.add_text, 0, pymupdf.Point(30, 30), text, size, "left", "#000000")
    except DocOpError as e:
        # 거절은 정말 쪽에 다 못 들어갈 때만(큰 글자의 아주 긴 글)
        assert "다 들어가지 않습니다" in str(e) and size >= 48, (size, text[:10])
        return
    assert "".join(_ap_text(d.doc, 0).split()) == "".join(text.split())
    page = d.doc[0]
    assert page.rect.contains(page.first_annot.rect)


def test_trailing_blank_lines_kept(pdf):
    d = Document(pdf)
    x = d.edit(annots.add_text, 0, pymupdf.Point(100, 100), "A\n\n", 12, "left", "#000000")
    assert annots.text_style(d.doc, 0, x)["text"] == "A\n\n"


def _ink_all(page):
    pix = page.get_pixmap(dpi=72)
    s = pix.samples
    return sum(1 for i in range(0, len(s), pix.n) if s[i] < 200 or s[i + 2] < 200)


@pytest.mark.parametrize("size", [6, 12, 18, 30, 72])
@pytest.mark.parametrize("text", ["Xg가", "X" * 120, "가운데 정렬 긴 문장 " * 6, "오른쪽\n짧은 줄\nyg"])
def test_box_not_visually_clipped(size, text):
    """그린 상자가 같은 글을 아주 큰 상자에 그린 것과 잉크 양이 같아야(아래·옆이 잘리지 않음)."""
    d = pymupdf.open()
    page = d.new_page(width=595, height=842)
    try:
        x = annots.add_text(d, 0, pymupdf.Point(30, 30), text, size, "left", "#000000")
    except DocOpError:
        assert size >= 30
        return
    got = _ink_all(page)
    rows, r = annots._box_rect(page, pymupdf.Point(30, 30), text, size, "left")
    ref = pymupdf.open()
    rp = ref.new_page(width=1600, height=2400)
    # 같은 자리에서 시작하는 아주 큰 상자(잘릴 수 없음)와 비교: 반 픽셀 차이도 안 나게 같은 x0·y0
    rp.add_freetext_annot(pymupdf.Rect(r.x0, r.y0, 1500, 2300), annots._text_html(rows, size, "left", "#000000"),
                          richtext=True, border_width=0)
    assert got >= _ink_all(rp) * 0.995, (size, text[:8])


@pytest.mark.parametrize("align", ["left", "center", "right"])
@pytest.mark.parametrize("size", [6, 12, 24, 48, 72])
@pytest.mark.parametrize("text", ["ffff", "off", "fi fl", "W."])
def test_short_text_any_align_and_size(pdf, align, size, text):
    d = Document(pdf)
    d.edit(annots.add_text, 0, pymupdf.Point(300, 100), text, size, align, "#000000")
    page = d.doc[0]
    assert page.first_annot.info["content"] == text


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_too_long_rejected_on_rotated_page(pdf, rot):
    d = Document(pdf)
    d.doc[0].set_rotation(rot)
    with pytest.raises(DocOpError, match="다 들어가지 않습니다"):
        d.edit(annots.add_text, 0, pymupdf.Point(100, 100), "\n".join(f"줄{i:03d}" for i in range(80)), 18, "left", "#000000")
    d.edit(annots.add_text, 0, pymupdf.Point(100, 100), "X" * 120, 18, "right", "#000000")


# ── 끌어 만든 글자 상자 ──
def test_dragged_text_box_keeps_box_and_edits_in_place(pdf):
    d = Document(pdf)
    box = pymupdf.Rect(300, 200, 500, 240)
    x = d.edit(annots.add_text_box, 0, box, "상자 안 글자", 14, "center", "#0050c8")
    page = d.doc[0]
    a = page.first_annot
    assert a.flags & pymupdf.PDF_ANNOT_IS_PRINT and a.info["content"] == "상자 안 글자"
    assert abs(a.rect.x0 - 300) < 0.5 and abs(a.rect.x1 - 500) < 0.5
    assert abs(a.rect.y0 - (200 - annots.BOX_LEAD)) < 0.5  # 숨은 빈 줄만큼 위로 올림
    st = annots.text_style(d.doc, 0, x)
    assert abs(st["rect"].y0 - 200) < 0.5  # 고치기 칸은 끈 자리 그대로
    assert st["box"] == box and (st["size"], st["align"], st["color"]) == (14, "center", "#0050c8")
    x2 = d.edit(annots.set_text, 0, x, "고친 글자\n둘째 줄", 20, "right", "#d00000")
    st2 = annots.text_style(d.doc, 0, x2)
    assert st2["box"] == box  # 고쳐도 끈 상자 자리 그대로
    page = d.doc[0]
    a = page.first_annot
    assert abs(a.rect.x0 - 300) < 0.5 and abs(a.rect.x1 - 500) < 0.5
    assert _red(d.doc[0], a.rect) > 30
    d.undo()
    assert annots.text_style(d.doc, 0, x)["text"] == "상자 안 글자"


def test_dragged_box_grows_down_for_long_text_and_draws_all(pdf):
    d = Document(pdf)
    text = "가나다라마바사 아자차카타파하 " * 8
    x = d.edit(annots.add_text_box, 0, pymupdf.Rect(100, 450, 260, 470), text, 12, "left", "#000000")
    page = d.doc[0]
    a = page.first_annot
    assert a.xref == x and abs(a.rect.width - 160) < 0.5 and a.rect.height > 60  # 폭은 그대로, 아래로 늘어남
    assert annots._squash(annots._drawn(a)) == annots._squash(text)


def test_too_narrow_drag_widened_to_minimum(pdf):
    d = Document(pdf)
    d.edit(annots.add_text_box, 0, pymupdf.Rect(100, 450, 110, 455), "좁은 상자", 24, "left", "#000000")
    page = d.doc[0]
    assert page.first_annot.rect.width >= annots.box_min_width(24) - 0.5


def test_dragged_box_too_much_text_rejected(pdf):
    d = Document(pdf)
    with pytest.raises(DocOpError, match="다 들어가지 않습니다"):
        d.edit(annots.add_text_box, 0, pymupdf.Rect(100, 100, 300, 200), "가" * 4000, 18, "left", "#000000")
    assert own(d.doc, 0) == []


@pytest.mark.parametrize("rot", [90, 180, 270])
def test_dragged_box_on_rotated_page(pdf, rot):
    d = pymupdf.open(pdf)
    page = d[0]
    page.set_rotation(rot)
    box = (pymupdf.Rect(100, 100, 300, 140) * page.derotation_matrix).normalize()  # 화면에서 끈 상자
    x = annots.add_text_box(d, 0, box, "돌린 쪽 상자", 14, "left", "#d00000")
    page = d[0]
    a = next(page.annots())
    vis = (a.rect * page.rotation_matrix).normalize()
    assert abs(vis.x0 - 100) < 0.5 and abs(vis.x1 - 300) < 0.5 and abs(vis.y0 - (100 - annots.BOX_LEAD)) < 0.5
    st = annots.text_style(d, 0, x)
    assert st["box"] == box
    shown = (st["rect"] * page.rotation_matrix).normalize()
    assert abs(shown.y0 - 100) < 0.5
    # 글이 끈 상자 맨 위 근처에서 시작(화면 기준)
    pix = page.get_pixmap(dpi=72, clip=pymupdf.Rect(90, 60, 310, 160))  # clip은 화면(돌린 뒤) 좌표
    s = pix.samples
    ink = [y for y in range(pix.height) for x in range(0, pix.width, 2)
           if s[(y * pix.width + x) * pix.n] > 150 and s[(y * pix.width + x) * pix.n + 1] < 90]
    assert ink and 100 <= 60 + min(ink) <= 112


@pytest.mark.parametrize("size", [6, 12, 30, 72])
@pytest.mark.parametrize("align", ["left", "center", "right"])
@pytest.mark.parametrize("text", ["Xg가", "X" * 80, "가운데 정렬 긴 문장 " * 5, "오른쪽\n짧은 줄\nyg"])
def test_dragged_box_not_visually_clipped(size, align, text):
    d = pymupdf.open()
    page = d.new_page(width=595, height=842)
    try:
        annots.add_text_box(d, 0, pymupdf.Rect(40, 40, 300, 60), text, size, align, "#000000")
    except DocOpError:
        assert size >= 30
        return
    got = _ink_all(page)
    r = page.first_annot.rect
    rows, _, _ = annots._fixed_rect(page, pymupdf.Rect(40, 40, 300, 60), text, size)
    ref = pymupdf.open()
    rp = ref.new_page(width=595, height=2400)
    rp.add_freetext_annot(pymupdf.Rect(r.x0, r.y0, r.x1, 2300), annots._text_html(rows, size, align, "#000000"),
                          richtext=True, border_width=0)
    assert got >= _ink_all(rp) * 0.995, (size, align, text[:8])


def test_old_click_text_boxes_still_edit(pdf):
    d = Document(pdf)
    x = d.edit(annots.add_text, 0, pymupdf.Point(320, 220), "누른 곳 글자", 14, "left", "#000000")
    assert annots.text_style(d.doc, 0, x)["box"] is None
    x2 = d.edit(annots.set_text, 0, x, "고침", 14, "left", "#000000")
    assert annots.text_style(d.doc, 0, x2)["anchor"] == pymupdf.Point(320, 220)


# ── 지우개로 끌어 지우기 ──
def test_erase_area_removes_own_only_in_one_undo(pdf, tmp_path):
    d = Document(pdf)
    d.edit(annots.add_highlight, 0, word_rect(d.doc, 0, "Alpha1"))
    d.edit(annots.add_note, 0, pymupdf.Point(200, 150), "메모")
    d.edit(annots.add_box, 0, pymupdf.Rect(300, 120, 400, 180), "#d00000", None, 2, 1.0)
    d.edit(annots.add_text, 0, pymupdf.Point(100, 600), "멀리 있는 글자", 12, "left", "#000000")
    page = d.doc[0]
    page.add_highlight_annot(word_rect(d.doc, 0, "Beta1"))  # 원래 문서의 형광펜(남의 것)
    n = d.edit(annots.delete_in_rect, 0, pymupdf.Rect(50, 70, 450, 200))
    assert n == 3
    page = d.doc[0]
    kinds = sorted(a.type[1] for a in page.annots())
    assert kinds == ["FreeText", "Highlight"]  # 남의 형광펜·범위 밖 글자는 남음
    d.undo()
    page = d.doc[0]
    assert len(list(page.annots())) == 5  # 한 번 되돌리면 셋 다 돌아옴


def test_erase_area_empty_or_tiny_rejected(pdf):
    d = Document(pdf)
    with pytest.raises(DocOpError, match="지울 형광펜"):
        d.edit(annots.delete_in_rect, 0, pymupdf.Rect(10, 700, 100, 800))
    with pytest.raises(DocOpError, match="끌어서"):
        d.edit(annots.delete_in_rect, 0, pymupdf.Rect(10, 10, 11, 11))
    assert not d.modified


# ── 상자 도형 ──
def _px(page, pt):
    pix = page.get_pixmap(dpi=72, clip=pymupdf.Rect(pt.x - 1, pt.y - 1, pt.x + 1, pt.y + 1))
    return tuple(pix.samples[:3])


def test_box_fill_line_print_and_restyle(pdf, tmp_path):
    d = Document(pdf)
    r = pymupdf.Rect(300, 500, 450, 600)
    x = d.edit(annots.add_box, 0, r, "#0050c8", "#fff3a0", 3, 1.0)
    page = d.doc[0]
    a = page.first_annot
    assert a.type[0] == pymupdf.PDF_ANNOT_SQUARE and a.flags & pymupdf.PDF_ANNOT_IS_PRINT
    assert _px(d.doc[0], pymupdf.Point(375, 550)) == (255, 243, 160)  # 안쪽은 채우기 색
    assert _px(d.doc[0], pymupdf.Point(300, 550))[2] > 150  # 테두리는 파랑
    d.edit(annots.set_box_style, 0, x, None, "#cfe3ff", 2, 1.0)
    assert _px(d.doc[0], pymupdf.Point(375, 550)) == (207, 227, 255)
    out = tmp_path / "box.pdf"
    d.save_to(str(out))
    with pymupdf.open(out) as o:
        assert _px(o[0], pymupdf.Point(375, 550)) == (207, 227, 255)
    d.undo()
    assert _px(d.doc[0], pymupdf.Point(375, 550)) == (255, 243, 160)


def test_box_half_transparent_keeps_text_visible(pdf):
    d = Document(pdf)
    r = word_rect(d.doc, 0, "Gamma1")
    before = _ink(d.doc[0], r)
    d.edit(annots.add_box, 0, r + (-5, -5, 5, 5), None, "#e6e6e6", 2, 0.3)
    assert _ink(d.doc[0], r) >= before * 0.8  # 30%면 아래 글자가 비쳐 보임
    r2 = word_rect(d.doc, 1, "Gamma2")
    d.edit(annots.add_box, 1, r2 + (-5, -5, 5, 5), None, "#e6e6e6", 2, 1.0)
    assert _ink(d.doc[1], r2) == 0  # 100%면 가려짐


def test_box_bad_input(pdf):
    d = Document(pdf)
    for args in [((300, 500, 450, 600), None, None, 2, 1.0), ((300, 500, 450, 600), "red", None, 2, 1.0),
                 ((300, 500, 450, 600), "#000000", None, 7, 1.0), ((300, 500, 301, 501), "#000000", None, 2, 1.0),
                 ((300, 500, 450, 600), "#000000", None, 2, 0.0)]:
        with pytest.raises(DocOpError):
            d.edit(annots.add_box, 0, *args)
    assert own(d.doc, 0) == []


def test_foreign_square_not_restyled_or_erased(pdf):
    d = Document(pdf)
    page = d.doc[0]
    foreign = page.add_rect_annot(pymupdf.Rect(300, 500, 450, 600)).xref
    with pytest.raises(DocOpError):
        d.edit(annots.set_box_style, 0, foreign, "#000000", None, 2, 1.0)
    assert annots.annot_at(d.doc, 0, pymupdf.Point(375, 550)) is None


def test_dragged_text_starts_at_box_top_and_lead_strip_not_clickable(pdf):
    d = Document(pdf)
    x = d.edit(annots.add_text_box, 0, pymupdf.Rect(300, 200, 500, 240), "Hello 위쪽", 14, "left", "#d00000")
    page = d.doc[0]
    a = page.first_annot
    assert _red(d.doc[0], pymupdf.Rect(300, 200 - annots.BOX_LEAD, 500, 200)) == 0  # 끈 자리 위에는 아무것도 안 그림
    assert _red(d.doc[0], pymupdf.Rect(300, 200, 500, 215)) > 10  # 끈 자리 맨 위부터 글자
    assert annots.annot_at(d.doc, 0, pymupdf.Point(400, 190)) is None  # 위로 올린 빈 줄은 누를 수 없음
    assert annots.annot_at(d.doc, 0, pymupdf.Point(400, 210))["xref"] == x == a.xref
    assert annots.own_in_rect(d.doc, 0, pymupdf.Rect(300, 180, 500, 195)) == []  # 지우개도 마찬가지


def test_dragged_box_at_page_top_stays_on_page(pdf):
    d = Document(pdf)
    d.edit(annots.add_text_box, 0, pymupdf.Rect(300, 5, 500, 30), "맨 위", 14, "left", "#d00000")
    page = d.doc[0]
    a = page.first_annot
    # 숨은 첫 줄 자리는 쪽 위로 나가도 되지만 보이는 글 영역은 쪽 안
    assert annots.text_style(d.doc, 0, a.xref)["rect"].y0 >= 0
    assert _red(d.doc[0], pymupdf.Rect(300, 0, 500, 60)) > 10


@pytest.mark.parametrize("adder", ["click", "box"])
def test_text_added_after_save_draws_correctly(pdf, adder):
    """저장(Ctrl+S) 뒤에 넣은 글자 상자도 글자가 깨지지 않고 넣어져야(rev8까지는 깨지거나 거절됨)."""
    d = Document(pdf)

    def add(y, text):
        if adder == "click":
            return d.edit(annots.add_text, 0, pymupdf.Point(320, y), text, 18, "left", "#d00000")
        return d.edit(annots.add_text_box, 0, pymupdf.Rect(320, y, 520, y + 30), text, 18, "left", "#d00000")
    add(150, "첫 글자 Hello")
    d.save_to(pdf)
    x = add(300, "저장 뒤 글자 World")
    page = d.doc[0]
    a = next(a for a in page.annots() if a.xref == x)
    assert annots._squash(annots._drawn(a)) == "저장뒤글자World"
    assert "저장뒤글자World" in annots._squash(d.doc[0].get_text())
    d.save_to(pdf)
    add(500, "두 번 저장한 뒤")
    with pymupdf.open(pdf) as r:
        assert "저장뒤글자World" in annots._squash(r[0].get_text())


def test_box_at_page_bottom_hit_area_follows_text():
    """쪽 아래 끝에 끈 상자는 위로 밀려 들어가는데, 누르기·지우개 영역도 실제 글자를 따라가야."""
    d = pymupdf.open()
    d.new_page(width=595, height=842)
    x = annots.add_text_box(d, 0, pymupdf.Rect(100, 810, 300, 830), "FIRST\nSECOND\nTHIRD", 14, "left", "#000000")
    page = d[0]
    first = page.search_for("FIRST")[0]
    mid = pymupdf.Point((first.x0 + first.x1) / 2, (first.y0 + first.y1) / 2)
    assert annots.annot_at(d, 0, mid)["xref"] == x
    assert annots.own_in_rect(d, 0, first) == [x]
    st = annots.text_style(d, 0, x)
    assert st["rect"].y0 <= first.y0 + 1 and st["rect"].y1 >= page.search_for("THIRD")[0].y1 - 1


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_box_at_page_top_text_starts_at_top(rot):
    """쪽 맨 위에 끈 상자는 글도 쪽 맨 위부터 보여야(쓰는 칸 미리보기와 같은 자리)."""
    d = pymupdf.open()
    p = d.new_page(width=595, height=842)
    p.set_rotation(rot)
    box = (pymupdf.Rect(100, 0, 300, 40) * p.derotation_matrix).normalize()
    x = annots.add_text_box(d, 0, box, "POSITION", 14, "left", "#000000")
    p = d[0]
    raw = p.search_for("POSITION")[0]  # 돌리기 전 쪽 좌표
    glyph = (raw * p.rotation_matrix).normalize()  # 화면에서 보이는 자리
    shown = (annots.text_style(d, 0, x)["rect"] * p.rotation_matrix).normalize()
    assert glyph.y0 < 4 and shown.y0 <= glyph.y0 + 1
    assert annots.annot_at(d, 0, (raw.tl + raw.br) / 2)["xref"] == x


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
@pytest.mark.parametrize("text,box", [("stuff", (80, 200, 260, 230)), ("office ffi", (80, 200, 260, 230)),
                                      ("stuff office", (300, 400, 520, 440))])
def test_right_aligned_ff_not_rejected(rot, text, box):
    """오른쪽 정렬 'ff' 글을 상자 자리에 따라 '너무 많다'로 거절하지 않아야(글자 읽기 차이일 뿐)."""
    d = pymupdf.open()
    d.new_page()
    d[0].set_rotation(rot)
    x = annots.add_text_box(d, 0, pymupdf.Rect(*box), text, 13, "right", "#000000")
    page = d[0]
    assert any(a.xref == x for a in page.annots())
    d.close()


def test_box_overflow_still_rejected_after_ff_rule():
    """f를 견주지 않아도 정말 넘치는 글은 여전히 거절."""
    d = pymupdf.open()
    d.new_page(width=200, height=120)
    with pytest.raises(DocOpError):
        annots.add_text_box(d, 0, pymupdf.Rect(10, 10, 190, 30), "\n".join(["row"] * 30), 12, "left", "#000000")
    d.close()


def test_erase_inside_hollow_box_keeps_box():
    """채우기 없는 상자 안쪽에서 형광펜만 끌거나 누르면 상자는 남고 형광펜만 지워진다."""
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((100, 150), "inside text", fontsize=12)
    hb = annots.add_box(d, 0, pymupdf.Rect(40, 60, 400, 300), "#0000ff", None, 2, 1.0)
    hl = annots.add_highlight(d, 0, (98, 138, 170, 154), "yellow")
    inner = pymupdf.Rect(95, 135, 175, 157)
    assert annots.own_in_rect(d, 0, inner) == [hl]
    assert annots.annot_at(d, 0, (130, 146))["xref"] == hl
    # 테두리를 지나가게 끌면 상자도
    assert hb in annots.own_in_rect(d, 0, pymupdf.Rect(30, 100, 60, 120))
    assert annots.annot_at(d, 0, (40, 200))["xref"] == hb
    # 채운 상자는 안쪽 어디든
    fb = annots.add_box(d, 0, pymupdf.Rect(420, 60, 560, 200), None, "#ffff00", 2, 1.0)
    assert fb in annots.own_in_rect(d, 0, pymupdf.Rect(480, 120, 500, 140))
    d.close()


def test_erase_zero_height_drag_hits():
    """정확히 수평으로(높이 0) 끈 지우개도 닿은 것을 지운다."""
    d = pymupdf.open()
    d.new_page()
    x = annots.add_text_box(d, 0, pymupdf.Rect(100, 300, 300, 330), "LINE", 12, "left", "#000000")
    st = annots.text_style(d, 0, x)
    y = (st["rect"].y0 + st["rect"].y1) / 2
    assert annots.own_in_rect(d, 0, pymupdf.Rect(50, y, 400, y)) == [x]
    d.close()


def test_box_at_inside_hollow_and_under_other():
    """상자 도구 누르기: 채우기 없는 상자 안쪽이나, 위에 형광펜이 덮인 곳도 그 상자를 찾는다."""
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((100, 150), "inside text", fontsize=12)
    hb = annots.add_box(d, 0, pymupdf.Rect(40, 60, 400, 300), "#0000ff", None, 2, 1.0)
    annots.add_highlight(d, 0, (98, 138, 170, 154), "yellow")
    assert annots.box_at(d, 0, (200, 250)) == hb
    assert annots.box_at(d, 0, (130, 146)) == hb
    assert annots.box_at(d, 0, (450, 350)) is None
    d.close()
