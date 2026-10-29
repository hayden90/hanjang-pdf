"""글자 인식(OCR) 글자층: 가짜 엔진으로 자리·회전·찾기·고르기와 글자 획을 표 선으로 보지 않는 것을 확인한다."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pymupdf  # noqa: E402
import pytest  # noqa: E402

from pdfviewer.core import doc_ops, ocr  # noqa: E402
from pdfviewer.core.doc_ops import DocOpError  # noqa: E402
from pdfviewer.core.text_select import page_cells, page_frame, page_words, words_between, words_text  # noqa: E402

TRUTH = [  # 화면(돌린 쪽) 기준 pt 칸과 글자: 가짜 엔진이 '읽은' 것
    ((60, 90, 140, 104), "대지위치"),
    ((150, 90, 290, 104), "서울특별시"),
    ((60, 130, 110, 141), "연면적"),
    ((120, 130, 200, 141), "1,234.56"),
]


def fake_engine(truth=TRUTH, page_w=None):
    """그림 픽셀 좌표로 낱말을 돌려주는 가짜 엔진(화면 pt × 배율)."""
    def run(rgb, w, h):
        assert len(rgb) == w * h * 3  # RGB 그림을 받음
        s = w / page_w[0]
        return [(x0 * s, y0 * s, (x1 - x0) * s, (y1 - y0) * s, t) for (x0, y0, x1, y1), t in truth]
    return run


def picture_page(rot=0):
    """글자 정보 없이 그림(선)만 있는 쪽."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    p.draw_rect(pymupdf.Rect(40, 60, 360, 200), color=(0, 0, 0), width=0.8)
    p.set_rotation(rot)
    return d


def shown_words(page):
    return {w[4]: pymupdf.Rect(w[:4]) * page.rotation_matrix for w in page.get_text("words")}


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_layer_words_sit_where_recognized(rot):
    """돌린 쪽에서도 넣은 글자의 낱말 칸이 인식한 칸과 같은 자리(0.5pt 안)."""
    d = picture_page(rot)
    p = d[0]
    assert ocr.needs_ocr(p)
    words = ocr.recognize_page(p, fake_engine(page_w=[p.rect.width]))
    assert ocr.add_text_layer(d, {0: words}) == len(TRUTH)
    got = shown_words(d[0])
    for box, text in TRUTH:
        assert text in got
        assert max(abs(a - b) for a, b in zip(got[text], box)) < 0.5, (text, got[text], box)
    assert not ocr.needs_ocr(d[0])


def test_layer_is_invisible_and_searchable():
    """넣은 글자는 그림을 바꾸지 않고, 찾기·끌어 고르기가 된다."""
    d = picture_page()
    before = d[0].get_pixmap(dpi=72).samples
    ocr.add_text_layer(d, {0: ocr.recognize_page(d[0], fake_engine(page_w=[400]))})
    assert d[0].get_pixmap(dpi=72).samples == before
    assert [pno for pno, _ in doc_ops.search(d, "서울특별시")] == [0]
    ws = page_words(d[0])
    got = words_text(words_between(ws, pymupdf.Point(62, 97), pymupdf.Point(288, 97),
                                   page_cells(d[0]), page_frame(d[0])))
    assert got == "대지위치 서울특별시"


def test_layer_survives_save(tmp_path):
    d = picture_page(90)
    ocr.add_text_layer(d, {0: ocr.recognize_page(d[0], fake_engine(page_w=[d[0].rect.width]))})
    out = str(tmp_path / "o.pdf")
    doc_ops.atomic_save(d, out)
    assert "서울특별시" in pymupdf.open(out)[0].get_text()


def test_recognize_skips_blank_words_and_caps_size():
    seen = {}

    def eng(rgb, w, h):
        seen["size"] = (w, h)
        return [(10, 10, 50, 20, "  "), (10, 40, 0, 20, "x"), (10, 70, 50, 20, "가")]
    d = pymupdf.open()
    p = d.new_page(width=4000, height=4000)  # 큰 쪽: 그림 한 변이 한도를 넘지 않게 해상도를 낮춤
    words = ocr.recognize_page(p, eng)
    assert max(seen["size"]) <= ocr.MAX_PIXELS
    assert [t for _, t in words] == ["가"]


def test_render_leaves_out_own_annotations():
    """이 프로그램이 넣은 글자 상자 등은 읽지 않는다(그림에 빼고 그림)."""
    d = picture_page()
    plain = d[0].get_pixmap(dpi=72, annots=False).samples
    d[0].add_freetext_annot(pymupdf.Rect(60, 300, 300, 340), "메모 글자", fontsize=20)
    got = {}

    def eng(rgb, w, h):
        got["rgb"] = rgb
        return []
    ocr.recognize_page(d[0], eng, dpi=72)
    assert got["rgb"] == plain


def test_bad_page_number():
    d = picture_page()
    with pytest.raises(DocOpError):
        ocr.add_text_layer(d, {3: [(pymupdf.Rect(0, 0, 10, 10), "가")]})


def _outline_glyph(page, x, y, s=6.0):
    """채우기만 한 '口' 모양 도형(글자를 곡선 그림으로 바꾼 문서의 글자 획 흉내)."""
    sh = page.new_shape()
    sh.draw_polyline([(x, y), (x + s, y), (x + s, y + s), (x, y + s), (x, y)])
    sh.draw_polyline([(x + 1.2, y + 1.2), (x + 1.2, y + s - 1.2), (x + s - 1.2, y + s - 1.2),
                      (x + s - 1.2, y + 1.2), (x + 1.2, y + 1.2)])
    sh.finish(color=None, fill=(0, 0, 0), even_odd=True)
    sh.commit()


def _two_by_two(page):
    for y in (100, 140, 180):
        page.draw_line((50, y), (350, y), color=(0, 0, 0), width=0.6)
    for x in (50, 200, 350):
        page.draw_line((x, 100), (x, 180), color=(0, 0, 0), width=0.6)


def test_glyph_strokes_are_not_table_lines():
    """채우기만 한 글자 도형의 직선은 표 선이 아니다: 칸 수가 글자 없을 때와 같다."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    _two_by_two(p)
    plain = len(page_cells(p))
    for i in range(12):
        _outline_glyph(p, 60 + i * 9, 112)
        _outline_glyph(p, 210 + i * 9, 152)
    assert plain == 4
    assert len(page_cells(p)) == 4


def test_filled_thin_bars_still_count_as_lines():
    """선을 채운 얇은 막대(직선으로 그린 도형)로 그린 표는 그대로 칸을 찾는다."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    sh = p.new_shape()
    for y in (100, 140, 180):
        sh.draw_polyline([(50, y), (350, y), (350, y + 0.8), (50, y + 0.8), (50, y)])
        sh.finish(color=None, fill=(0, 0, 0))
    for x in (50, 200, 350):
        sh.draw_polyline([(x, 100), (x + 0.8, 100), (x + 0.8, 180.8), (x, 180.8), (x, 100)])
        sh.finish(color=None, fill=(0, 0, 0))
    sh.commit()
    assert len(page_cells(p)) == 4


def test_windows_engine_off_windows_says_why():
    if sys.platform == "win32":
        pytest.skip("윈도우에서는 실제 엔진을 씀")
    engine, why = ocr.windows_engine()
    assert engine is None and "윈도우" in why


def test_thin_bars_in_one_filled_path_count_as_lines():
    """얇은 막대 여러 개를 한 도형으로 채워 그린 표도 칸을 찾는다(막대마다 얇은지 봄)."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    sh = p.new_shape()
    for y in (100, 140, 180):
        sh.draw_polyline([(50, y), (350, y), (350, y + 0.8), (50, y + 0.8), (50, y)])
    for x in (50, 200, 350):
        sh.draw_polyline([(x, 100), (x + 0.8, 100), (x + 0.8, 180.8), (x, 180.8), (x, 100)])
    sh.finish(color=None, fill=(0, 0, 0))
    sh.commit()
    assert len(page_cells(p)) == 4


def test_glyphs_in_one_filled_path_are_not_lines():
    """글자 여러 개를 한 도형으로 채워 그려도(굵은 '口' 윤곽) 표 선으로 보지 않는다."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    _two_by_two(p)
    sh = p.new_shape()
    for i in range(10):
        x, y, s = 60 + i * 9, 112, 6.0
        sh.draw_polyline([(x, y), (x + s, y), (x + s, y + s), (x, y + s), (x, y)])
        sh.draw_polyline([(x + 1.2, y + 1.2), (x + 1.2, y + s - 1.2), (x + s - 1.2, y + s - 1.2),
                          (x + s - 1.2, y + 1.2), (x + 1.2, y + 1.2)])
    sh.finish(color=None, fill=(0, 0, 0), even_odd=True)
    sh.commit()
    assert len(page_cells(p)) == 4


def test_short_thin_glyph_strokes_are_not_lines():
    """채운 얇은 획이라도 짧으면(글자 'ㅁ'의 획) 표 선이 아니다: 칸 안에 작은 네모 칸이 생기지 않음."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    _two_by_two(p)
    sh = p.new_shape()
    def bar(x0, y0, x1, y1):
        sh.draw_polyline([(x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0)])
    for i in range(8):  # 'ㅁ': 8pt 길이·0.8pt 두께 획 네 개가 네모를 이룸
        x, y = 60 + i * 16, 110
        bar(x, y, x + 8, y + 0.8)
        bar(x, y + 7.2, x + 8, y + 8)
        bar(x, y, x + 0.8, y + 8)
        bar(x + 7.2, y, x + 8, y + 8)
    sh.finish(color=None, fill=(0, 0, 0))
    sh.commit()
    assert len(page_cells(p)) == 4


def test_short_filled_rect_strokes_are_not_lines():
    """글자 획을 채운 사각형으로 그린 문서도 짧은 획('ㅁ')은 표 선이 아니다."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    _two_by_two(p)
    for i in range(3):
        x, y = 60 + i * 16, 110
        for r in ((x, y, x + 8, y + 0.8), (x, y + 7.2, x + 8, y + 8), (x, y, x + 0.8, y + 8), (x + 7.2, y, x + 8, y + 8)):
            p.draw_rect(pymupdf.Rect(r), color=None, fill=(0, 0, 0))
    assert len(page_cells(p)) == 4


def test_long_filled_rect_lines_still_make_table():
    """채운 얇은 사각형으로 그린 긴 표 선은 그대로 칸이 된다."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    for y in (100, 140, 180):
        p.draw_rect(pymupdf.Rect(50, y, 350, y + 0.8), color=None, fill=(0, 0, 0))
    for x in (50, 200, 350):
        p.draw_rect(pymupdf.Rect(x, 100, x + 0.8, 180.8), color=None, fill=(0, 0, 0))
    assert len(page_cells(p)) == 4
