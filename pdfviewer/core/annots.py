"""형광펜·메모·글자 상자: PDF 표준 주석으로 넣어 크롬·아크로뱃에서도 보이게 한다.

글자 상자는 FreeText 주석이다. 모양(글자 그림)은 MuPDF가 글꼴을 파일 안에 넣어 만들고,
인쇄 표시(Print)를 붙여 인쇄에도 그대로 나온다. 모양을 만든 뒤 annot.update()를 부르면
MuPDF가 색·크기·정렬 없는 기본 모양으로 다시 만들어 버리므로, 표식은 PDF 값을 직접 쓴다.

좌표는 모두 '돌리기 전 쪽' 기준이다(PyMuPDF 글자·주석 좌표와 같음).
화면 좌표는 to_page()로 바꿔 넘긴다. 함수는 쪽 번호를 받아 그때마다 쪽을 다시 읽는다
(실패 때 Document가 문서를 사본으로 바꿔 끼우므로 쪽 객체를 들고 있지 않는다).
"""
from __future__ import annotations

import html
import math
import re
import unicodedata

import pymupdf

from .doc_ops import DocOpError

HL_COLORS = {
    "yellow": (1.0, 0.92, 0.23),
    "green": (0.55, 0.9, 0.45),
    "pink": (1.0, 0.6, 0.8),
}
# 지우개·메모 고치기가 건드리는 것은 이 프로그램이 넣은 형광펜·메모뿐이다.
# 종류만으로는 원래 문서(다른 사람)의 형광펜·메모와 구별이 안 되므로 작성자·주제 표식을 함께 본다.
OWN_TYPES = (pymupdf.PDF_ANNOT_HIGHLIGHT, pymupdf.PDF_ANNOT_TEXT, pymupdf.PDF_ANNOT_FREE_TEXT,
             pymupdf.PDF_ANNOT_SQUARE)
AUTHOR = "PDF 뷰어"  # 이름이 한장PDF로 바뀌어도 그대로: 이 값으로 예전에 칠한 형광펜·메모를 알아본다
MARK = "pdfviewer-highstar"  # 주석 '주제'에 넣는 표식
MIN_DRAG = 3  # 이보다 작게 끌면 실수로 보고 칠하지 않음(쪽 좌표 단위)
TEXT_ALIGNS = ("left", "center", "right")
TEXT_MIN, TEXT_MAX = 6, 72  # 글자 크기(pt)
STYLE_KEY = "HSTextStyle"  # 글자 상자 주석에 '크기|정렬|색|누른 곳 x|y'(끌어 만든 상자는 뒤에 '|box|x0|y0|x1|y1')
_COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
MIN_BOX_W = 60
# MuPDF는 글 앞에 보이지 않는 기본 크기 줄을 둬 글이 상자 위에서 약 30pt 아래부터 그려진다.
# 끌어 만든 상자는 주석 사각형을 이만큼 위로 올려, 글이 끈 자리 맨 위에서 시작하게 한다.
BOX_LEAD = 26
_TOO_LONG = "글자가 너무 많아 이 쪽에 다 들어가지 않습니다. 글자를 줄이거나 크기를 작게 해 주세요."
_CJK_FONT = pymupdf.Font("cjk")
_LATIN_FONT = pymupdf.Font("helv")


def to_page(page: pymupdf.Page, x: float, y: float, zoom: float) -> pymupdf.Point:
    """화면(돌린 뒤 쪽, 배율 적용) 좌표 → 돌리기 전 쪽 좌표."""
    return pymupdf.Point(x / zoom, y / zoom) * page.derotation_matrix


def _bounds(page: pymupdf.Page) -> pymupdf.Rect:
    """돌리기 전 쪽 좌표의 보이는 영역. cropbox는 MediaBox 기준이라 원점이 달라 쓰지 않는다."""
    return (page.rect * page.derotation_matrix).normalize()


def _is_own(annot) -> bool:
    info = annot.info
    return annot.type[0] in OWN_TYPES and info.get("title") == AUTHOR and info.get("subject") == MARK


def _page(doc: pymupdf.Document, pno: int) -> pymupdf.Page:
    if not 0 <= pno < doc.page_count:
        raise DocOpError(f"없는 쪽 번호입니다: {pno + 1}")
    return doc[pno]


def add_highlight(doc: pymupdf.Document, pno: int, rect, color: str = "yellow") -> int:
    """rect와 겹치는 글자에 형광펜을 칠한다. 글자가 없으면(그림·스캔) 끈 영역 자체를 칠한다."""
    page = _page(doc, pno)
    rect = pymupdf.Rect(rect).normalize() & _bounds(page)  # 쪽 밖으로 나간 부분은 자름
    if rect.is_empty or (rect.width < MIN_DRAG and rect.height < MIN_DRAG):
        raise DocOpError("형광펜을 칠할 곳을 마우스로 끌어서 골라 주세요.")
    if color not in HL_COLORS:
        raise DocOpError(f"모르는 형광펜 색입니다: {color}")
    words = [pymupdf.Rect(w[:4]) for w in page.get_text("words")]
    hit = [w for w in words if w.intersects(rect)]
    quads = [w.quad for w in hit] if hit else [rect.quad]
    annot = page.add_highlight_annot(quads=quads)
    annot.set_colors(stroke=HL_COLORS[color])
    annot.set_info(title=AUTHOR, subject=MARK)
    annot.update()
    return annot.xref


def add_note(doc: pymupdf.Document, pno: int, point, text: str) -> int:
    text = (text or "").strip()
    if not text:
        raise DocOpError("메모 내용이 비어 있습니다.")
    page = _page(doc, pno)
    pt = pymupdf.Point(point)
    if not _bounds(page).contains(pt):
        raise DocOpError("쪽 안을 눌러 메모를 붙여 주세요.")
    annot = page.add_text_annot(pt, text, icon="Note")
    annot.set_info(title=AUTHOR, subject=MARK, content=text)
    annot.update()
    return annot.xref


def annot_at(doc: pymupdf.Document, pno: int, point, prefer: int | None = None) -> dict | None:
    """그 자리의 이 프로그램 형광펜·메모(맨 위 것). 원래 문서의 주석은 돌려주지 않는다.

    prefer: 이 종류가 그 자리에 있으면 위에 다른 것이 덮여 있어도 그것을 돌려준다
    (글자 상자 위에 상자 도형을 그려도 글자를 고칠 수 있게).
    """
    page = _page(doc, pno)
    pt = pymupdf.Point(point)
    found = pick = None
    for a in page.annots(types=OWN_TYPES):
        if _is_own(a) and _hits(a, pt):
            found = {"xref": a.xref, "type": a.type[0], "text": a.info.get("content", "")}
            if found["type"] == prefer:
                pick = found
    return pick or found


def box_at(doc: pymupdf.Document, pno: int, point) -> int | None:
    """그 자리를 품은 이 프로그램 상자 도형(맨 위 것)의 xref. 채우기 없는 상자도 안쪽 어디든,
    위에 다른 주석이 덮여 있어도(상자 도구로 눌러 색 바꾸기용)."""
    page = _page(doc, pno)
    pt = pymupdf.Point(point)
    found = None
    for a in page.annots(types=[pymupdf.PDF_ANNOT_SQUARE]):
        if _is_own(a) and a.rect.contains(pt):
            found = a.xref
    return found


def _hits(annot, pt: pymupdf.Point) -> bool:
    """형광펜은 실제로 칠한 칸(Quad)만, 메모는 아이콘 사각형으로 판정한다.

    여러 줄 형광펜의 바깥 사각형으로 판정하면 줄 사이 빈 곳을 눌러도 지워지기 때문.
    """
    if annot.type[0] == pymupdf.PDF_ANNOT_FREE_TEXT:
        return _shown_rect(annot).contains(pt)
    if _hollow(annot):  # 채우기 없는 상자는 테두리 띠만(안쪽 빈 곳의 형광펜·글자를 누를 수 있게)
        outer, inner = _band(annot)
        return outer.contains(pt) and not inner.contains(pt)
    if annot.type[0] != pymupdf.PDF_ANNOT_HIGHLIGHT:
        return annot.rect.contains(pt)
    v = annot.vertices or []
    quads = [pymupdf.Quad(*v[i:i + 4]) for i in range(0, len(v) - 3, 4)]
    if not quads:
        return annot.rect.contains(pt)
    return any(q.rect.contains(pt) for q in quads)


def _shown_rect(annot) -> pymupdf.Rect:
    """글자 상자에서 글이 보이는 영역(끌어 만든 상자는 위로 올린 빈 줄을 뺌)."""
    kind, raw = annot.parent.parent.xref_get_key(annot.xref, STYLE_KEY)
    parts = (raw.strip("()") if kind == "string" else raw).split("|")
    if len(parts) == 14 and parts[5] == "box":
        try:
            return pymupdf.Rect(*map(float, parts[10:14]))
        except ValueError:
            pass
    return pymupdf.Rect(annot.rect)


def _own_annot(page: pymupdf.Page, xref: int):
    for a in page.annots(types=OWN_TYPES):
        if a.xref == xref and _is_own(a):
            return a
    raise DocOpError("이 프로그램으로 넣은 형광펜·메모만 지우거나 고칠 수 있습니다.")


def delete_annot(doc: pymupdf.Document, pno: int, xref: int) -> None:
    page = _page(doc, pno)
    page.delete_annot(_own_annot(page, xref))


def set_note_text(doc: pymupdf.Document, pno: int, xref: int, text: str) -> None:
    text = (text or "").strip()
    if not text:
        raise DocOpError("메모 내용이 비어 있습니다. 지우려면 지우개를 쓰세요.")
    page = _page(doc, pno)
    a = _own_annot(page, xref)
    if a.type[0] != pymupdf.PDF_ANNOT_TEXT:
        raise DocOpError("메모가 아닙니다.")
    a.set_info(content=text)
    a.update()


# ── 글자 상자 ──
def _check_text(text: str, size, align: str, color: str) -> str:
    text = (text or "").replace("\r\n", "\n").replace("\r", "\n")  # 끝의 빈 줄·칸도 쓴 그대로 둔다
    if not text.strip():
        raise DocOpError("넣을 글자가 비어 있습니다.")
    if not isinstance(size, int) or not TEXT_MIN <= size <= TEXT_MAX:
        raise DocOpError(f"글자 크기는 {TEXT_MIN}~{TEXT_MAX} 사이로 골라 주세요.")
    if align not in TEXT_ALIGNS:
        raise DocOpError(f"모르는 정렬입니다: {align}")
    if not isinstance(color, str) or not _COLOR_RE.match(color):
        raise DocOpError(f"모르는 색입니다: {color}")
    return text


def _text_width(line: str, size: int) -> float:
    """한 줄 폭(pt). 한글·한자는 CJK 글꼴, 나머지는 산세리프 폭으로 잰다."""
    w = 0.0
    for ch in line:
        font = _CJK_FONT if ord(ch) >= 0x1100 else _LATIN_FONT
        w += font.text_length(ch, fontsize=size)
    return w


def _wrap(line: str, size: int, inner: float) -> list[str]:
    """한 줄을 폭 inner(pt) 안에 들어가게 나눈다. 띄어쓰기에서 먼저 나누고, 없으면 글자 단위로."""
    rows, cur = [], ""
    for ch in line:
        if cur and _text_width(cur + ch, size) * 1.15 > inner:
            cut = cur.rfind(" ")
            if cut > 0:  # 띄어쓰기에서 나눔(그 칸은 줄 끝에서 버림)
                rows.append(cur[:cut])
                cur = cur[cut + 1:] + ch
            else:
                rows.append(cur)
                cur = ch
        else:
            cur += ch
    rows.append(cur)
    return rows


def _rows_ok(rows, text: str, size: int, inner: float) -> bool:
    """쓰는 칸이 보여 준 줄들을 그대로 써도 되나: 글자가 그대로이고(띄어쓰기만 다를 수 있음) 줄마다 폭 안."""
    return (bool(rows) and all(isinstance(r, str) for r in rows)
            and _squash("".join(rows)) == _squash(text)
            and all(_text_width(r, size) <= inner for r in rows))


def _layout(text: str, size: int, max_width: float, fixed: bool = False,
            rows: list[str] | None = None) -> tuple[list[str], float, float]:
    """보여 줄 줄들과 상자 크기(화면 방향). 줄 바꾸기는 MuPDF에 맡기지 않고 여기서 한다.

    MuPDF는 띄어쓰기 없는 긴 영문을 나누지 못해 잘라 버리므로, 쪽 폭에 맞춰 미리 나눈다.
    높이는 MuPDF가 그린 그림이 잘리지 않는 최소 높이를 크기·줄 수별로 재어(첫 줄 약 28+1.15×크기,
    다음 줄마다 약 1.2×크기) 여유를 더했다.
    """
    pad = size + 8
    inner = max(max_width - pad, size * 2)
    # 쓰는 칸에서 본 줄 그대로 넣는다(미리보기와 결과의 줄 바뀜을 같게). 맞지 않으면 여기서 다시 나눈다
    if not (rows is not None and _rows_ok(rows, text, size, inner)):
        rows = [r for ln in text.split("\n") for r in _wrap(ln, size, inner)]
    # MuPDF는 글자 앞에 보이지 않는 기본 크기(12pt) 줄을 하나 두는데, 상자가 좁으면 그 줄이
    # 여러 줄로 꺾여 높이를 먹고 뒤 글자가 잘린다. 그래서 폭을 적어도 60pt로 잡는다.
    width = max_width if fixed else min(max(max(_text_width(r, size) * 1.15 for r in rows) + pad, MIN_BOX_W), max_width)
    return rows, width, box_need_height(size, len(rows))


def box_need_height(size: int, n_rows: int) -> float:
    """글자 상자 주석에 필요한 높이(숨은 앞 줄 BOX_LEAD 포함, pt)."""
    return 30 + size * 1.25 + (max(1, n_rows) - 1) * size * 1.3


def _box_rect(page: pymupdf.Page, anchor: pymupdf.Point, text: str, size: int, align: str):
    """누른 곳(돌리기 전 좌표)과 정렬로 상자 자리를 정한다. (보여 줄 줄들, 돌리기 전 사각형)을 돌려준다.

    화면에서 왼쪽 정렬은 누른 곳이 상자 왼쪽 끝, 가운데는 가운데, 오른쪽은 오른쪽 끝이다.
    쪽 밖으로 넘치면 쪽 안으로 밀어 넣는다.
    """
    vis = anchor * page.rotation_matrix
    pw, ph = page.rect.width, page.rect.height
    rows, w, h = _layout(text, size, pw)
    if h > ph:
        raise DocOpError(_TOO_LONG)
    x0 = vis.x - {"left": 0, "center": w / 2, "right": w}[align]
    x0 = min(max(x0, 0), pw - w)
    y0 = min(max(vis.y, 0), ph - h)
    return rows, (pymupdf.Rect(x0, y0, x0 + w, y0 + h) * page.derotation_matrix).normalize()


def box_min_width(size: int) -> float:
    """끌어 만든 상자의 가장 작은 폭(pt). 더 좁게 끌면 이 폭으로 넓힌다(좁으면 MuPDF가 글을 자름)."""
    return max(MIN_BOX_W, size * 3 + 8)


def _fixed_rect(page: pymupdf.Page, box: pymupdf.Rect, text: str, size: int, rows: list[str] | None = None):
    """끌어 정한 상자(돌리기 전 좌표)에 글을 넣을 자리. 폭은 끈 그대로(너무 좁으면 최소 폭),
    높이는 끈 높이와 글에 필요한 높이 가운데 큰 쪽. 쪽 밖으로 넘치면 쪽 안으로 밀어 넣는다.

    (보여 줄 줄들, 주석 사각형, 글이 보이는 영역)을 돌려준다. 주석 사각형은 BOX_LEAD만큼 위로
    올라가 있고, 누르기·지우개는 글이 보이는 영역으로만 판정한다(돌리기 전 좌표).
    """
    vis = (box * page.rotation_matrix).normalize()
    pw, ph = page.rect.width, page.rect.height
    w = min(max(vis.width, box_min_width(size)), pw)
    rows, w, need = _layout(text, size, w, fixed=True, rows=rows)
    h = max(vis.height + BOX_LEAD, need)
    if h > ph:
        raise DocOpError(_TOO_LONG)
    x0 = min(max(vis.x0, 0), pw - w)
    # 숨은 첫 줄(BOX_LEAD)은 쪽 위로 나가도 되므로 보이는 글이 쪽 맨 위(0)까지 올라올 수 있게 한다
    y0 = min(max(vis.y0 - BOX_LEAD, -BOX_LEAD), ph - h)
    rect = pymupdf.Rect(x0, y0, x0 + w, y0 + h)
    # 글은 늘 주석 사각형 위에서 BOX_LEAD 아래부터 보인다(쪽 아래 끝이라 위로 밀었을 때도)
    shown = pymupdf.Rect(x0, min(y0 + BOX_LEAD, rect.y1), rect.x1, rect.y1)
    der = page.derotation_matrix
    return rows, (rect * der).normalize(), (shown * der).normalize()


def _text_html(rows: list[str], size: int, align: str, color: str) -> str:
    style = f"font-family:sans-serif;font-size:{size}pt;color:{color};text-align:{align};margin:0"
    paras = []
    for line in rows:
        body = html.escape(line, quote=False)
        # HTML은 이어진 띄어쓰기를 하나로 줄이므로 줄 앞·이어진 칸은 줄바꿈 없는 칸으로
        body = re.sub(r"(?<= ) |^ ", "\u00a0", body) or "\u00a0"
        paras.append(f"<p style='{style}'>{body}</p>")
    return "".join(paras)


def add_text(doc: pymupdf.Document, pno: int, anchor, text: str, size: int = 12,
             align: str = "left", color: str = "#000000") -> int:
    """누른 곳에 글자 상자를 넣는다. 인쇄에도 나오고, 저장하면 다른 프로그램에서도 보인다."""
    text = _check_text(text, size, align, color)
    page = _page(doc, pno)
    pt = pymupdf.Point(anchor)
    if not _bounds(page).contains(pt):
        raise DocOpError("쪽 안을 눌러 글자를 넣어 주세요.")
    rows, rect = _box_rect(page, pt, text, size, align)
    return _put_text(doc, page, rect, rows, text, size, align, color, f"{pt.x:.2f}|{pt.y:.2f}")


def add_text_box(doc: pymupdf.Document, pno: int, box, text: str, size: int = 12,
                 align: str = "left", color: str = "#000000", rows: list[str] | None = None) -> int:
    """끌어서 정한 상자 안에 글을 넣는다(정렬은 상자 안에서). 글이 많으면 상자가 아래로 늘어난다.

    rows: 쓰는 칸이 화면에 보여 준 줄들. 글자가 같고 폭 안에 들면 그 줄 그대로 넣는다.
    """
    text = _check_text(text, size, align, color)
    page = _page(doc, pno)
    box = pymupdf.Rect(box).normalize()
    if box.is_empty or not box.intersects(_bounds(page)):
        raise DocOpError("쪽 안을 끌어 글자 상자를 그려 주세요.")
    rows, rect, shown = _fixed_rect(page, box, text, size, rows)
    return _put_text(doc, page, rect, rows, text, size, align, color,
                     f"{rect.x0:.2f}|{rect.y0:.2f}|box|{box.x0:.2f}|{box.y0:.2f}|{box.x1:.2f}|{box.y1:.2f}"
                     f"|{shown.x0:.2f}|{shown.y0:.2f}|{shown.x1:.2f}|{shown.y1:.2f}")


def _put_text(doc, page, rect, rows, text, size, align, color, where: str) -> int:
    annot = page.add_freetext_annot(rect, _text_html(rows, size, align, color), richtext=True,
                                    border_width=0, rotate=page.rotation)
    x = annot.xref
    doc.xref_set_key(x, "T", pymupdf.get_pdf_str(AUTHOR))
    doc.xref_set_key(x, "Subj", pymupdf.get_pdf_str(MARK))
    doc.xref_set_key(x, "Contents", pymupdf.get_pdf_str(text))
    doc.xref_set_key(x, "F", str(pymupdf.PDF_ANNOT_IS_PRINT))
    doc.xref_set_key(x, STYLE_KEY, pymupdf.get_pdf_str(f"{size}|{align}|{color}|{where}"))
    # 실제로 그려진 모양에 글자가 빠짐없이 들어갔는지 확인한다(인쇄도 이 모양을 쓴다).
    # 넘쳐서 잘렸으면 오류를 내고, Document.edit가 문서를 넣기 전으로 돌려놓는다.
    if _read_key(annot.get_textpage().extractText()) != _reference(rows, size, align, color, rect, page.rotation):
        raise DocOpError(_TOO_LONG)
    return x


def _drawn(annot: pymupdf.Annot) -> str:
    """주석 모양에서 읽은 글자(띄어쓰기 뺌). get_text()는 상자 밖 획을 잘라 읽어 쓰지 않는다."""
    return _squash(annot.get_textpage().extractText())


def _reference(rows: list[str], size: int, align: str, color: str, rect: pymupdf.Rect, rotation: int) -> tuple:
    """같은 폭에 높이만 아주 넉넉한 상자에 그렸을 때 읽히는 글자(잘릴 수 없는 기준).

    원래 글과 바로 비교하지 않는 까닭: MuPDF 글자 읽기는 오른쪽 정렬의 "ff" 같은 몇몇 경우
    그려진 글자를 빼고 읽는다. 같은 방식으로 읽은 기준과 견주면 그런 차이는 상쇄되고 높이 부족만 남는다.
    """
    w = rect.height if rotation % 180 else rect.width
    tmp = pymupdf.open()
    try:
        page = tmp.new_page(width=w + 20, height=6000)
        ref = page.add_freetext_annot(pymupdf.Rect(10, 10, 10 + w, 5990), _text_html(rows, size, align, color),
                                      richtext=True, border_width=0)
        return _read_key(ref.get_textpage().extractText())
    finally:
        tmp.close()


def _squash(s: str) -> str:
    """띄어쓰기·줄바꿈을 뺀 글자만(자동 줄바꿈 위치와 상관없이 비교하려고)."""
    return re.sub(r"\s+", "", s.replace("\u00a0", " "))


def _read_key(s: str) -> tuple:
    """그려진 글자 비교용 (줄마다 글자). MuPDF는 넘친 줄을 통째로 그리지 않으므로 줄 수로 잘림을 잡는다.
    또 글자 읽기는 상자 자리에 따라 'ff'·'ffi' 같은 이음 글자를 빼고 읽기도 해서(그림은 온전함)
    이음 글자를 풀고 f·i·l은 견주지 않는다."""
    out = []
    for line in s.splitlines():
        k = re.sub(r"[fFil]", "", _squash(unicodedata.normalize("NFKC", line)))
        if _squash(line):
            out.append(k)
    return tuple(out)


def text_style(doc: pymupdf.Document, pno: int, xref: int) -> dict:
    """이 프로그램이 넣은 글자 상자의 글자·크기·정렬·색·누른 곳."""
    page = _page(doc, pno)  # 쪽을 잡아 두어야 주석 객체가 살아 있음
    a = _own_annot(page, xref)
    if a.type[0] != pymupdf.PDF_ANNOT_FREE_TEXT:
        raise DocOpError("글자 상자가 아닙니다.")
    kind, raw = doc.xref_get_key(xref, STYLE_KEY)
    try:
        parts = (raw.strip("()") if kind == "string" else raw).split("|")
        size, align, color, ax, ay = parts[:5]
        st = {"size": int(size), "align": align, "color": color,
              "anchor": pymupdf.Point(float(ax), float(ay)), "box": None, "shown": None}
        if len(parts) == 14 and parts[5] == "box":
            st["box"] = pymupdf.Rect(*map(float, parts[6:10]))
            st["shown"] = pymupdf.Rect(*map(float, parts[10:14]))
    except (ValueError, TypeError):  # 표식이 깨졌으면 상자 왼쪽 위·기본값으로
        st = {"size": 12, "align": "left", "color": "#000000",
              "anchor": (a.rect * page.rotation_matrix).normalize().tl * page.derotation_matrix,
              "box": None, "shown": None}
    st["text"] = a.info.get("content", "")
    st["rect"] = pymupdf.Rect(a.rect)
    rot, der = page.rotation_matrix, page.derotation_matrix
    # 예전 판(누른 곳 기준) 상자도 숨은 앞 줄(BOX_LEAD) 아래부터 글이 보이므로 고치기 칸은 그 아래에서 시작
    vr = (st["rect"] * rot).normalize()
    st["edit_box"] = (pymupdf.Rect(vr.x0, min(vr.y0 + BOX_LEAD, vr.y1), vr.x1, vr.y1) * der).normalize()
    if st["shown"] is not None:
        st["rect"] = st["shown"]  # 누르기·지우개는 글이 보이는 자리로
        # 고치기 칸: 글이 지금 보이는 위치에서 시작하고 높이는 처음 끈 높이(화면 방향으로 계산).
        # 고친 글도 이 상자로 넣으므로, 쪽 아래에서 밀려 올라갔던 글을 짧게 고쳐도 보던 자리에 남는다
        vs, vb = (st["shown"] * rot).normalize(), (st["box"] * rot).normalize()
        st["edit_box"] = (pymupdf.Rect(vs.x0, vs.y0, vs.x1, vs.y0 + vb.height) * der).normalize()
    return st


def annot_at_xref_text(doc: pymupdf.Document, pno: int, xref: int) -> str | None:
    """그 쪽에 이 프로그램의 글자 상자 xref가 아직 있으면 그 글, 없으면 None."""
    try:
        return text_style(doc, pno, xref)["text"]
    except DocOpError:
        return None


def set_text(doc: pymupdf.Document, pno: int, xref: int, text: str, size: int,
             align: str, color: str, rows: list[str] | None = None, box=None) -> int:
    """글자 상자를 고친다(같은 자리에 새로 만들어 바꿔 끼움). 새 상자의 xref를 돌려준다.

    box: 쓰는 칸이 떠 있던 상자(돌리기 전 좌표, 보통 text_style의 edit_box). 주면 그 자리에 넣어
    미리보기와 같게 한다(쪽 아래에서 밀려 올라갔던 글을 짧게 고쳐도 보던 자리에 남음).
    """
    text = _check_text(text, size, align, color)
    st = text_style(doc, pno, xref)
    page = _page(doc, pno)
    page.delete_annot(_own_annot(page, xref))
    if box is not None:  # 쓰는 칸에서 고침: 칸이 떠 있던 자리·줄 그대로(예전 판 눌러 만든 상자도 상자로 바뀜)
        return add_text_box(doc, pno, box, text, size, align, color, rows)
    if st["box"] is not None:  # 끌어 만든 상자는 끈 상자 그대로
        return add_text_box(doc, pno, st["box"], text, size, align, color, rows)
    return add_text(doc, pno, st["anchor"], text, size, align, color)


# ── 지우개로 끌어서 여러 개 지우기 ──
def _hollow(annot) -> bool:
    """채우기 없는 상자 도형인가."""
    return annot.type[0] == pymupdf.PDF_ANNOT_SQUARE and not (annot.colors or {}).get("fill")


def _band(annot) -> tuple[pymupdf.Rect, pymupdf.Rect]:
    """테두리 띠의 바깥·안쪽 사각형(선 굵기 절반 + 누르기 여유 3pt)."""
    pad = (annot.border or {}).get("width", 1) / 2 + 3
    r = annot.rect
    return pymupdf.Rect(r.x0 - pad, r.y0 - pad, r.x1 + pad, r.y1 + pad), \
        pymupdf.Rect(r.x0 + pad, r.y0 + pad, r.x1 - pad, r.y1 - pad)


def _meets(a: pymupdf.Rect, b: pymupdf.Rect) -> bool:
    """닿음(높이나 폭이 0인 끌기도 포함: Rect.intersects는 빈 사각형을 빼므로 경계로 견줌)."""
    return a.x0 <= b.x1 and b.x0 <= a.x1 and a.y0 <= b.y1 and b.y0 <= a.y1


def _touches(annot, rect: pymupdf.Rect) -> bool:
    if annot.type[0] == pymupdf.PDF_ANNOT_FREE_TEXT:
        return _meets(_shown_rect(annot), rect)
    if _hollow(annot):  # 끈 사각형이 테두리 띠에 닿아야(안쪽 빈 곳에만 있으면 아님)
        outer, inner = _band(annot)
        return _meets(outer, rect) and not inner.contains(rect)
    if annot.type[0] == pymupdf.PDF_ANNOT_HIGHLIGHT:
        v = annot.vertices or []
        quads = [pymupdf.Quad(*v[i:i + 4]) for i in range(0, len(v) - 3, 4)]
        if quads:
            return any(_meets(q.rect, rect) for q in quads)
    return _meets(annot.rect, rect)


def own_in_rect(doc: pymupdf.Document, pno: int, rect) -> list[int]:
    """끈 사각형에 닿은 이 프로그램의 주석 xref들(원래 문서의 주석은 빼고)."""
    page = _page(doc, pno)
    rect = pymupdf.Rect(rect).normalize()
    return [a.xref for a in page.annots(types=OWN_TYPES) if _is_own(a) and _touches(a, rect)]


def delete_in_rect(doc: pymupdf.Document, pno: int, rect) -> int:
    """끈 사각형에 닿은 이 프로그램의 형광펜·메모·글자·상자를 모두 지운다(되돌리기 한 번에 돌아옴)."""
    page = _page(doc, pno)
    rect = pymupdf.Rect(rect).normalize()
    if rect.width < MIN_DRAG and rect.height < MIN_DRAG:
        raise DocOpError("지울 곳을 마우스로 끌어서 골라 주세요.")
    xrefs = own_in_rect(doc, pno, rect)
    if not xrefs:
        raise DocOpError("끈 곳에 지울 형광펜·메모·글자·상자가 없습니다.")
    for x in xrefs:
        page.delete_annot(_own_annot(page, x))
    return len(xrefs)


# ── 상자 도형 ──
SHAPE_WIDTHS = (0.5, 1, 2, 3, 5)


def _rgb(hexv: str | None):
    if hexv is None:
        return None
    if not isinstance(hexv, str) or not _COLOR_RE.match(hexv):
        raise DocOpError(f"모르는 색입니다: {hexv}")
    return tuple(int(hexv[i:i + 2], 16) / 255 for i in (1, 3, 5))


def _style_box(annot, line: str | None, fill: str | None, width: float, opacity: float) -> None:
    if line is None and fill is None:
        raise DocOpError("선 색과 채우기 색이 둘 다 '없음'이면 상자가 보이지 않습니다.")
    if width not in SHAPE_WIDTHS:
        raise DocOpError(f"모르는 선 굵기입니다: {width}")
    if not 0.1 <= opacity <= 1:
        raise DocOpError("투명도는 10~100% 사이로 골라 주세요.")
    annot.set_colors(stroke=_rgb(line) or [], fill=_rgb(fill) or [])
    annot.set_border(width=width if line is not None else 0)
    annot.set_opacity(opacity)
    annot.set_flags(pymupdf.PDF_ANNOT_IS_PRINT)
    annot.update()


def add_box(doc: pymupdf.Document, pno: int, rect, line: str | None = "#d00000", fill: str | None = None,
            width: float = 2, opacity: float = 1.0) -> int:
    """끈 사각형에 상자 도형을 넣는다. line·fill은 '#rrggbb' 또는 None(없음). 인쇄에도 나온다."""
    page = _page(doc, pno)
    rect = pymupdf.Rect(rect).normalize() & _bounds(page)
    if rect.is_empty or rect.width < MIN_DRAG or rect.height < MIN_DRAG:
        raise DocOpError("상자를 그릴 곳을 마우스로 끌어서 정해 주세요.")
    annot = page.add_rect_annot(rect)
    annot.set_info(title=AUTHOR, subject=MARK)
    _style_box(annot, line, fill, width, opacity)
    return annot.xref


def set_box_style(doc: pymupdf.Document, pno: int, xref: int, line: str | None, fill: str | None,
                  width: float, opacity: float) -> None:
    """이미 그린 상자의 선·채우기 색을 바꾼다."""
    page = _page(doc, pno)
    a = _own_annot(page, xref)
    if a.type[0] != pymupdf.PDF_ANNOT_SQUARE:
        raise DocOpError("상자가 아닙니다.")
    _style_box(a, line, fill, width, opacity)
