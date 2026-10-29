import pymupdf
import pytest

from pdfviewer.core.text_select import page_words, words_between, words_text


@pytest.fixture
def page():
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 100), "alpha beta gamma")
    p.insert_text((72, 130), "delta epsilon")
    p.insert_text((72, 160), "zeta eta theta")
    yield p
    d.close()


def _at(words, text):
    w = next(w for w in words if w[4] == text)
    return ((w[0] + w[2]) / 2, (w[1] + w[3]) / 2)


def test_flow_selection_across_lines(page):
    ws = page_words(page)
    got = words_between(ws, _at(ws, "beta"), _at(ws, "eta"))
    assert words_text(got) == "beta gamma\ndelta epsilon\nzeta eta"


def test_backwards_drag_gives_same_text(page):
    ws = page_words(page)
    fwd = words_text(words_between(ws, _at(ws, "beta"), _at(ws, "eta")))
    assert words_text(words_between(ws, _at(ws, "eta"), _at(ws, "beta"))) == fwd


def test_single_word_and_empty_page(page):
    ws = page_words(page)
    assert words_text(words_between(ws, _at(ws, "delta"), _at(ws, "delta"))) == "delta"
    blank = pymupdf.open().new_page()
    assert page_words(blank) == [] and words_between([], (0, 0), (9, 9)) == []


def test_korean_text(tmp_path):
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 100), "서울 강남구 빌딩", fontname="korea", fontfile=None)
    ws = page_words(p)
    assert words_text(words_between(ws, _at(ws, "서울"), _at(ws, "빌딩"))) == "서울 강남구 빌딩"


def test_screen_order_not_file_order():
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 160), "zeta eta theta")  # 파일에는 아래 줄이 먼저 적힘
    p.insert_text((72, 100), "alpha beta gamma")
    p.insert_text((72, 130), "delta epsilon")
    ws = page_words(p)
    got = words_between(ws, _at(ws, "beta"), _at(ws, "eta"))
    assert words_text(got) == "beta gamma\ndelta epsilon\nzeta eta"


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_page_rotation_keeps_reading_order(rot):
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 160), "third line")
    p.insert_text((72, 100), "first line")
    p.insert_text((72, 130), "second line")
    p.set_rotation(rot)  # 쪽을 돌려 보아도 글을 읽는 순서는 같다
    ws = page_words(p)
    assert words_text(ws) == "first line\nsecond line\nthird line"
    got = words_between(ws, _at(ws, "second"), _at(ws, "first"))
    assert words_text(got) == "first line\nsecond"


def test_text_written_sideways():
    """쪽 안에 글줄이 위로 흐르게(90도) 적힌 경우도 글줄 방향을 따라 읽는다."""
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((100, 500), "alpha beta gamma", rotate=90)
    p.insert_text((130, 500), "delta epsilon", rotate=90)
    ws = page_words(p)
    assert words_text(ws).split("\n")[0] == "alpha beta gamma"
    got = words_between(ws, _at(ws, "gamma"), _at(ws, "beta"))
    assert words_text(got) == "beta gamma"


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_mixed_line_directions(rot):
    d = pymupdf.open()
    p = d.new_page()
    for i, y in enumerate((100, 130, 160)):
        p.insert_text((72, y), f"normal heading{i}")
    p.insert_text((450, 350), "alpha beta gamma", rotate=180)  # 거꾸로 쓰인 줄
    p.set_rotation(rot)
    ws = page_words(p)
    assert words_text(words_between(ws, _at(ws, "alpha"), _at(ws, "gamma"))) == "alpha beta gamma"
    assert words_text(words_between(ws, _at(ws, "normal"), _at(ws, "heading2"))).count("\n") == 2


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_tilted_text_reading_order(rot):
    src = pymupdf.open()
    sp = src.new_page(width=600, height=600)
    sp.insert_text((72, 100), "FIRST block")
    sp.insert_text((300, 130), "SECOND block")
    d = pymupdf.open()
    p = d.new_page(width=800, height=800)
    p.show_pdf_page(p.rect, src, 0, rotate=20)  # 20도 기울어진 글
    p.set_rotation(rot)
    ws = page_words(p)
    assert words_text(ws) == "FIRST block\nSECOND block"
    assert words_text(words_between(ws, _at(ws, "FIRST"), _at(ws, "SECOND"))) == "FIRST block\nSECOND"


# ── 표 칸별 고르기(등기부처럼 표 한 줄이 글 덩어리 하나인 경우) ──
def _table_page(rotation=0):
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((50, 140), "9", fontsize=11)  # 순위번호 칸
    for i, t in enumerate(["Seoul Gangnam", "Yeoksam 123", "Building A"]):  # 주소 칸(세 줄)
        p.insert_text((100, 115 + i * 14), t, fontsize=11)
    p.insert_text((50, 190), "8", fontsize=11)
    for i, t in enumerate(["Mapo Seogyo", "Street 401"]):
        p.insert_text((100, 180 + i * 14), t, fontsize=11)
    sh = p.new_shape()
    for x in (40, 90, 400):
        sh.draw_line((x, 100), (x, 220))
    for y in (100, 160, 220):
        sh.draw_line((40, y), (400, y))
    sh.finish(width=0.8, color=(0, 0, 0))
    sh.commit()
    p.set_rotation(rotation)
    return d, p


def test_table_drag_in_one_cell_skips_next_column():
    from pdfviewer.core.text_select import page_cells
    d, p = _table_page()
    ws, cells = page_words(p), page_cells(p)
    assert cells
    # 칸을 모르면 옆 칸 순위번호 '8'이 끼어든다(고치기 전 동작)
    assert "8" in words_text(words_between(ws, _at(ws, "Mapo"), _at(ws, "401")))
    assert words_text(words_between(ws, _at(ws, "Mapo"), _at(ws, "401"), cells)) == "Mapo Seogyo\nStreet 401"
    d.close()


def test_table_drag_down_one_column_across_rows():
    from pdfviewer.core.text_select import page_cells
    d, p = _table_page()
    ws, cells = page_words(p), page_cells(p)
    got = words_text(words_between(ws, _at(ws, "Yeoksam"), _at(ws, "Seogyo"), cells))
    assert got == "Yeoksam 123\nBuilding A\nMapo Seogyo"
    d.close()


def test_table_cells_same_on_rotated_page():
    from pdfviewer.core.text_select import page_cells
    d0, p0 = _table_page(0)
    d9, p9 = _table_page(90)
    assert page_cells(p0) == page_cells(p9)  # 돌린 쪽도 돌리기 전 좌표로 같은 칸
    ws = page_words(p9)
    assert words_text(words_between(ws, _at(ws, "Mapo"), _at(ws, "401"), page_cells(p9))) == "Mapo Seogyo\nStreet 401"
    assert p9.rotation == 90  # 칸을 찾느라 원래 쪽을 건드리지 않음
    d0.close()
    d9.close()


def test_no_table_selection_unchanged(page):
    from pdfviewer.core.text_select import page_cells
    ws = page_words(page)
    assert page_cells(page) == []
    assert words_between(ws, _at(ws, "beta"), _at(ws, "eta"), []) == words_between(ws, _at(ws, "beta"), _at(ws, "eta"))


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_table_drag_over_many_cells_keeps_every_word(rot):
    """여러 칸을 감싸 끌면 그 칸들의 글자가 하나도 빠지지 않아야(거꾸로 끌어도 같음)."""
    from pdfviewer.core.text_select import page_cells, page_frame
    d, p = _table_page(rot)
    ws, cells, fr = page_words(p), page_cells(p), page_frame(p)
    want = {"9", "Seoul", "Gangnam", "Yeoksam", "123", "Building", "A", "8", "Mapo", "Seogyo", "Street", "401"}
    fwd = words_between(ws, (45, 105), (395, 215), cells, fr)
    back = words_between(ws, (395, 215), (45, 105), cells, fr)
    assert {w[4] for w in fwd} == want and fwd == back
    lines = words_text(fwd).split("\n")
    assert lines.index("Seoul Gangnam") < lines.index("Mapo Seogyo")
    assert lines.index("Building A") < lines.index("8")  # 위 줄 칸 먼저
    d.close()


def test_heading_between_tables_included_when_dragged_over():
    from pdfviewer.core.text_select import page_cells, page_frame
    d, p = _table_page()
    p.insert_text((150, 240), "Heading Between", fontsize=11)
    sh = p.new_shape()
    for x in (40, 90, 400):
        sh.draw_line((x, 260), (x, 320))
    for y in (260, 320):
        sh.draw_line((40, y), (400, y))
    sh.finish(width=0.8, color=(0, 0, 0))
    sh.commit()
    p.insert_text((100, 280), "Lower Table", fontsize=11)
    ws, cells, fr = page_words(p), page_cells(p), page_frame(p)
    got = words_text(words_between(ws, (45, 105), (395, 315), cells, fr))
    assert "Heading Between" in got and "Lower Table" in got and got.index("401") < got.index("Heading")
    d.close()


def test_drag_ending_below_last_line_takes_whole_end_cell():
    """끝 칸 맨 아래 구석에서 놓으면, 가장 가까운 낱말이 앞 줄 낱말이어도 그 칸 끝까지 고른다."""
    from pdfviewer.core.text_select import page_cells, page_frame
    d = pymupdf.open()
    p = d.new_page()
    sh = p.new_shape()
    for x in (40, 90, 400):
        sh.draw_line((x, 100), (x, 220))
    for y in (100, 220):
        sh.draw_line((40, y), (400, y))
    sh.finish(width=0.8, color=(0, 0, 0))
    sh.commit()
    p.insert_text((50, 160), "7", fontsize=11)
    p.insert_text((100, 115), "A very long first line that reaches far right edge", fontsize=11)
    p.insert_text((100, 210), "short", fontsize=11)
    ws, cells, fr = page_words(p), page_cells(p), page_frame(p)
    got = words_text(words_between(ws, (45, 105), (398, 218), cells, fr))
    assert got.endswith("short") and "7" in got
    d.close()


@pytest.mark.parametrize("rot", [0, 90])
def test_straight_horizontal_drag_across_cells(rot):
    """수평으로 곧게 끌어도(높이 0) 가로지른 칸들이 빠지지 않아야."""
    from pdfviewer.core.text_select import page_cells, page_frame
    d, p = _table_page(rot)
    ws, cells, fr = page_words(p), page_cells(p), page_frame(p)
    got = words_text(words_between(ws, (50, 190), (390, 190), cells, fr))
    assert "8" in got.split("\n") and "Mapo Seogyo" in got and "Street 401" in got
    d.close()


def _sel(p, a, b):
    from pdfviewer.core.text_select import page_cells, page_frame
    ws = page_words(p)
    pa = _at(ws, a) if isinstance(a, str) else pymupdf.Point(a)
    pb = _at(ws, b) if isinstance(b, str) else pymupdf.Point(b)
    return words_text(words_between(ws, pa, pb, page_cells(p), page_frame(p))).replace("\n", " / ")


@pytest.mark.parametrize("rot", [0, 90, 180, 270])
def test_table_cells_follow_drawn_lines(rot):
    """칸은 그린 선대로(글줄로 쪼개지 않음): 행을 가로질러 끌면 순서대로 빠짐없이."""
    from pdfviewer.core.text_select import page_cells
    d, p = _table_page(rot)
    assert sorted((round(c.x0), round(c.y0), round(c.x1), round(c.y1)) for c in page_cells(p)) == \
        [(40, 100, 90, 160), (40, 160, 90, 220), (90, 100, 400, 160), (90, 160, 400, 220)]
    assert _sel(p, "9", "A") == "9 / Seoul Gangnam / Yeoksam 123 / Building A"
    assert _sel(p, "9", "123") == "9 / Seoul Gangnam / Yeoksam 123"
    assert _sel(p, "123", "9") == "9 / Seoul Gangnam / Yeoksam 123"
    assert _sel(p, "Gangnam", "123") == "Gangnam / Yeoksam 123"
    assert _sel(p, "123", "Gangnam") == "Gangnam / Yeoksam 123"
    d.close()


def test_table_blank_corners_and_horizontal_drag():
    """칸 빈 모서리끼리 끌면 칸 통째로, 한 줄 높이에서 가로로 끌면 그 줄 끝 낱말까지."""
    d, p = _table_page()
    assert _sel(p, (95, 103), (395, 157)) == "Seoul Gangnam / Yeoksam 123 / Building A"
    assert _sel(p, (45, 185), (395, 185)) == "8 / Mapo Seogyo / Street 401"
    d.close()


def _table_variant(kind):
    """같은 표를 다른 방식으로 그린 쪽: 선 먼저 / 칸마다 테두리 사각형 / 얇게 채운 사각형 선."""
    d = pymupdf.open()
    p = d.new_page()
    sh = p.new_shape()
    if kind == "rects":
        for x0, x1 in ((40, 90), (90, 400)):
            for y0, y1 in ((100, 160), (160, 220)):
                sh.draw_rect(pymupdf.Rect(x0, y0, x1, y1))
        sh.finish(width=0.8, color=(0, 0, 0))
    elif kind == "thin":
        for x in (40, 90, 400):
            sh.draw_rect(pymupdf.Rect(x - 0.4, 100, x + 0.4, 220))
        for y in (100, 160, 220):
            sh.draw_rect(pymupdf.Rect(40, y - 0.4, 400, y + 0.4))
        sh.finish(width=0, color=None, fill=(0, 0, 0))
    else:
        for x in (40, 90, 400):
            sh.draw_line((x, 100), (x, 220))
        for y in (100, 160, 220):
            sh.draw_line((40, y), (400, y))
        sh.finish(width=0.8, color=(0, 0, 0))
    sh.commit()
    p.insert_text((50, 140), "9", fontsize=11)
    for i, t in enumerate(["Seoul Gangnam", "Yeoksam 123", "Building A"]):
        p.insert_text((100, 115 + i * 14), t, fontsize=11)
    p.insert_text((50, 190), "8", fontsize=11)
    for i, t in enumerate(["Mapo Seogyo", "Street 401"]):
        p.insert_text((100, 180 + i * 14), t, fontsize=11)
    return d, p


@pytest.mark.parametrize("kind", ["lines_first", "rects", "thin"])
def test_table_drawn_other_ways(kind):
    d, p = _table_variant(kind)
    assert _sel(p, "9", "A") == "9 / Seoul Gangnam / Yeoksam 123 / Building A"
    assert _sel(p, "Mapo", "401") == "Mapo Seogyo / Street 401"
    d.close()


def test_table_both_ends_in_cells_skips_text_outside_table():
    """두 끝이 모두 칸 안이면 표 밖 머리말·꼬리말은 끈 사각형 밖이면 넣지 않는다."""
    d, p = _table_page()
    p.insert_text((50, 60), "Header outside", fontsize=11)
    p.insert_text((50, 260), "Footer outside", fontsize=11)
    p.insert_text((420, 170), "Side note", fontsize=11)  # 표 오른쪽 밖(읽는 순서로는 두 행 사이)
    out = _sel(p, "9", "401")
    assert "Header" not in out and "Footer" not in out and "Side" not in out
    assert out.startswith("9 /") and out.endswith("Street 401")
    # 표 위에서 시작하면 머리말부터 줄 따라
    assert _sel(p, "Header", "Gangnam").startswith("Header outside")
    d.close()


def test_table_big_glyph_cell_whole_and_partial():
    """칸보다 큰 글자 상자가 섞인 칸(등기부 제목 칸 등)도 모서리끼리 끌면 통째, 낱말 사이는 그 사이만."""
    d = pymupdf.open()
    p = d.new_page()
    sh = p.new_shape()
    for y in (360, 390):
        sh.draw_line((20, y), (580, y))
    for x in (20, 580):
        sh.draw_line((x, 360), (x, 390))
    sh.finish(width=0.8, color=(0, 0, 0))
    sh.commit()
    for i, ch in enumerate("ABC"):
        p.insert_text((200 + i * 80, 385), ch, fontsize=40)
    p.insert_text((40, 380), "one two", fontsize=10)
    p.insert_text((480, 380), "end", fontsize=10)
    whole = _sel(p, (21, 361), (579, 389))
    assert sorted(whole.replace("/", " ").split()) == sorted(["A", "B", "C", "one", "two", "end"])
    assert "two" in _sel(p, "one", "two") and "end" not in _sel(p, "one", "two")
    d.close()
