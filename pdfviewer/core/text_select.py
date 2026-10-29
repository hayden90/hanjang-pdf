"""글자 고르기: 쪽 위를 끈 시작점·끝점 사이의 낱말을 읽는 순서대로 고르고 글로 만든다.

좌표는 모두 '돌리기 전 쪽' 기준이다(annots와 같음). 아크로뱃처럼 줄을 따라 흐르게 고른다:
첫 줄은 시작 낱말부터 줄 끝까지, 가운데 줄은 통째로, 끝 줄은 처음부터 끝 낱말까지.
읽는 순서는 PDF 파일에 적힌 순서가 아니라 '글자가 쓰인 방향'을 기준으로 한다. 글줄이 흐르는
방향(쪽에서 가장 많은 방향)을 오른쪽으로 놓고 보면, 글 덩어리는 위→아래·왼→오른, 덩어리 안의
줄은 위→아래 순이다. 줄 안의 낱말은 그 줄이 쓰인 방향대로 읽는다(거꾸로 쓰인 줄도 처음 낱말부터).
그래서 쪽을 돌려 보아도(90·180·270도) 글을 읽는 순서는 같다.

표(등기부 등)는 칸별로 고른다. 등기부는 표 한 줄 전체가 글 덩어리 하나여서 줄 순서대로만 고르면
주소 칸을 위아래로 끌어도 옆 칸(순위번호 등) 글자가 섞인다. 그래서 선으로 그린 표의 칸을 찾아
같은 칸 안에서 끌면 그 칸 글자만, 여러 칸에 걸쳐 끌면 끈 사각형에 닿은 칸의 글자만 고른다.
"""
from __future__ import annotations

import math
from collections import Counter

import pymupdf

Word = tuple  # (x0, y0, x1, y1, 글자, 블록, 줄, 낱말)


def _reading_frame(page: pymupdf.Page) -> pymupdf.Matrix:
    """가장 많은 글줄의 방향이 오른쪽(+x)이 되도록 돌리는 행렬(돌리기 전 쪽 좌표에 곱함)."""
    dirs = Counter()
    for b in page.get_text("dict", flags=0)["blocks"]:
        for ln in b.get("lines", []):
            c, sn = ln["dir"]
            dirs[(round(c, 2), round(sn, 2))] += 1  # 비스듬한 글줄(예: 20도)도 그 각도 그대로
    if not dirs:
        return pymupdf.Matrix(1, 0, 0, 1, 0, 0)
    c, sn = dirs.most_common(1)[0][0]
    return pymupdf.Matrix(-math.degrees(math.atan2(sn, c)))


def page_words(page: pymupdf.Page) -> list[Word]:
    """읽는 순서대로. 스캔 쪽처럼 글자 정보가 없으면 빈 목록."""
    words = [w for w in page.get_text("words") if w[4].strip()]
    frame = _reading_frame(page)
    blocks: dict[int, pymupdf.Rect] = {}
    lines: dict[tuple, pymupdf.Rect] = {}
    for w in words:
        r = (pymupdf.Rect(w[:4]) * frame).normalize()  # 글줄이 오른쪽으로 흐르게 놓은 자리
        blocks[w[5]] = blocks[w[5]] | r if w[5] in blocks else r
        k = (w[5], w[6])
        lines[k] = lines[k] | r if k in lines else r

    def key(w):
        b, ln = blocks[w[5]], lines[(w[5], w[6])]
        # 줄 안 낱말 번호(w[7])는 PyMuPDF가 그 줄이 쓰인 방향대로 매긴다
        return (round(b.y0), round(b.x0), w[5], round(ln.y0), round(ln.x0), w[6], w[7])

    return sorted(words, key=key)


def _dist(w: Word, pt: pymupdf.Point) -> float:
    r = pymupdf.Rect(w[:4])
    dx = max(r.x0 - pt.x, 0, pt.x - r.x1)
    dy = max(r.y0 - pt.y, 0, pt.y - r.y1)
    return dx * dx + dy * dy


def _nearest(words: list[Word], pt: pymupdf.Point) -> int:
    return min(range(len(words)), key=lambda i: _dist(words[i], pt))


def page_cells(page: pymupdf.Page) -> list[pymupdf.Rect]:
    """선으로 그린 표의 칸(돌리기 전 좌표). 표가 없거나 찾다가 실패하면 빈 목록.

    PyMuPDF의 표 찾기(find_tables)는 판에 따라 그린 선을 무시하고 글줄로 칸을 나누므로(2.0에서 확인),
    쪽에 그린 가로·세로 선으로 칸을 직접 만든다(_grid_cells). 회전에 따라 좌표가 달라지지 않게
    돌린 쪽은 그 쪽만 떼어 회전 0으로 놓고 읽는다(원래 문서는 건드리지 않음).
    """
    tmp = None
    try:
        if page.rotation:
            tmp = pymupdf.open()
            tmp.insert_pdf(page.parent, from_page=page.number, to_page=page.number, annots=False)
            tmp[0].set_rotation(0)
            drawings = tmp[0].get_drawings()
        else:
            drawings = page.get_drawings()
        return _grid_cells(_segments(drawings))
    except Exception:  # 표 찾기는 덤: 실패해도 보통 고르기로
        return []
    finally:
        if tmp is not None:
            tmp.close()


_TOL = 2.0  # 같은 선으로 보는 좌표 차이·선이 칸 가장자리를 덮었다고 보는 여유(pt)
_THIN = 3.0  # 이보다 얇은 사각형은 선으로 본다
_MAX_SLOTS = 40000  # 선이 너무 많은 쪽(그림·도표)은 표로 보지 않음
_BAR_MIN = 12.0  # 채운 막대를 선으로 보는 최소 길이(글자 획 'ㅡ·ㅣ'는 대장 실측 12pt 미만)


def _segments(drawings) -> tuple[dict, dict]:
    """그림에서 가로선 {y: [(x0,x1)...]}, 세로선 {x: [(y0,y1)...]}을 모은다(좌표는 _TOL로 묶음)."""
    hs: list[tuple] = []
    vs: list[tuple] = []

    def seg(a, b, least=4.0):
        if abs(a.y - b.y) <= 1 and abs(a.x - b.x) >= least:
            hs.append(((a.y + b.y) / 2, min(a.x, b.x), max(a.x, b.x)))
        elif abs(a.x - b.x) <= 1 and abs(a.y - b.y) >= least:
            vs.append(((a.x + b.x) / 2, min(a.y, b.y), max(a.y, b.y)))

    for dr in drawings:
        stroked = dr.get("color") is not None
        # 채우기만 한 도형 속 직선은 글자 획일 수 있다(세움터 건축물대장처럼 글자를 곡선 그림으로 바꾼 문서).
        # 이어진 조각(윤곽)마다 보아, 윤곽 전체가 얇을 때(선을 채운 막대)만 선으로 본다
        thin = set() if stroked else _thin_contour_items(dr["items"])
        for n, it in enumerate(dr["items"]):
            if it[0] == "l":
                if stroked:
                    seg(it[1], it[2])
                elif n in thin:
                    seg(it[1], it[2], _BAR_MIN)
            elif it[0] in ("re", "qu"):
                r = it[1].rect if it[0] == "qu" else it[1]
                least = 4.0 if stroked else _BAR_MIN  # 채우기만 한 짧은 막대는 글자 획일 수 있음
                if r.width < _THIN and r.height >= least:  # 얇은 사각형 = 세로선
                    vs.append(((r.x0 + r.x1) / 2, r.y0, r.y1))
                elif r.height < _THIN and r.width >= least:  # = 가로선
                    hs.append(((r.y0 + r.y1) / 2, r.x0, r.x1))
                elif stroked:  # 테두리를 그린 사각형(칸마다 그린 표): 네 변
                    seg(r.tl, r.tr); seg(r.bl, r.br); seg(r.tl, r.bl); seg(r.tr, r.br)
    return _bucket(hs), _bucket(vs)


def _thin_contour_items(items) -> set:
    """이어진 직선·곡선 조각(앞 조각 끝 = 다음 조각 시작)을 한 윤곽으로 묶어, 윤곽이 얇은 막대인 조각 번호들."""
    out: set = set()
    group: list = []
    last = None

    def close():
        if not group:
            return
        pts = [p for _, it in group for p in (it[1], it[-1])]
        xs, ys = [p.x for p in pts], [p.y for p in pts]
        if min(max(xs) - min(xs), max(ys) - min(ys)) < _THIN:
            out.update(n for n, _ in group)
        group.clear()

    for n, it in enumerate(items):
        if it[0] in ("l", "c"):
            a = it[1]
            if last is not None and abs(a.x - last.x) + abs(a.y - last.y) > 0.01:
                close()
            group.append((n, it))
            last = it[-1]
        else:
            close()
            last = None
    close()
    return out


def _bucket(segs: list[tuple]) -> dict:
    """같은 좌표(±_TOL)의 선을 한 줄로 묶어 구간을 합친다."""
    out: dict = {}
    for c, a, b in sorted(segs):
        key = next((k for k in out if abs(k - c) <= _TOL), c)
        out.setdefault(key, []).append((a, b))
    for k, iv in out.items():
        iv.sort()
        merged = [list(iv[0])]
        for a, b in iv[1:]:
            if a <= merged[-1][1] + _TOL:
                merged[-1][1] = max(merged[-1][1], b)
            else:
                merged.append([a, b])
        out[k] = [tuple(m) for m in merged]
    return out


def _covered(lines: dict, at: float, a: float, b: float) -> bool:
    """좌표 at의 선이 a~b 구간을 덮는가."""
    return any(x0 <= a + _TOL and x1 >= b - _TOL for x0, x1 in lines.get(at, ()))


def _grid_cells(segs: tuple[dict, dict]) -> list[pymupdf.Rect]:
    """가로·세로 선으로 나뉜 가장 작은 칸들을 만들고, 선이 없는 경계는 합친다(합친 칸).
    네 변이 모두 선으로 둘러싸인 네모 칸만 돌려준다(표 밖 빈 곳·선 하나뿐인 밑줄은 버림)."""
    hs, vs = segs
    ys, xs = sorted(hs), sorted(vs)
    if len(xs) < 2 or len(ys) < 2 or len(xs) * len(ys) > _MAX_SLOTS:
        return []
    nx, ny = len(xs) - 1, len(ys) - 1
    parent = list(range(nx * ny))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for j in range(ny):
        for i in range(nx):
            k = j * nx + i
            if i + 1 < nx and not _covered(vs, xs[i + 1], ys[j], ys[j + 1]):  # 오른쪽 경계에 선 없음
                parent[find(k + 1)] = find(k)
            if j + 1 < ny and not _covered(hs, ys[j + 1], xs[i], xs[i + 1]):  # 아래 경계에 선 없음
                parent[find(k + nx)] = find(k)
    groups: dict = {}
    for j in range(ny):
        for i in range(nx):
            groups.setdefault(find(j * nx + i), []).append((i, j))
    cells = []
    for slots in groups.values():
        i0, i1 = min(i for i, _ in slots), max(i for i, _ in slots) + 1
        j0, j1 = min(j for _, j in slots), max(j for _, j in slots) + 1
        if len(slots) != (i1 - i0) * (j1 - j0):  # 네모가 아닌 덩어리(표 바깥 빈 곳 등)
            continue
        x0, x1, y0, y1 = xs[i0], xs[i1], ys[j0], ys[j1]
        if (_covered(hs, y0, x0, x1) and _covered(hs, y1, x0, x1)
                and _covered(vs, x0, y0, y1) and _covered(vs, x1, y0, y1)):
            cells.append(pymupdf.Rect(x0, y0, x1, y1))
    return cells


def _cell_of(cells: list[pymupdf.Rect], pt: pymupdf.Point) -> int | None:
    """그 점이 든 가장 작은 칸의 번호(합친 칸 안에 작은 칸이 있어도 작은 쪽)."""
    best = None
    for i, c in enumerate(cells):
        if c.contains(pt) and (best is None or c.get_area() < cells[best].get_area()):
            best = i
    return best


def _center(w: Word) -> pymupdf.Point:
    return pymupdf.Point((w[0] + w[2]) / 2, (w[1] + w[3]) / 2)


def words_between(words: list[Word], p0, p1, cells: list[pymupdf.Rect] | None = None,
                  frame: pymupdf.Matrix | None = None) -> list[Word]:
    """끈 시작점·끝점 사이의 낱말을 읽는 순서대로 돌려준다.

    cells(표 칸)를 주면 표 안에서는 칸별로 고른다:
    - 시작·끝이 같은 칸이면 그 칸 글자만(줄 따라 흐르게).
    - 여러 칸이면 끈 사각형에 닿은 칸들을 칸 단위로(위 줄부터, 같은 줄은 왼쪽부터) 고른다.
      시작 칸은 시작 낱말부터, 끝 칸은 끝 낱말까지, 가운데 칸은 통째로.
    - 표 밖 글자는 예전처럼 시작·끝 사이를 줄 따라, 그리고 끈 사각형 안에 든 것.
    frame은 글줄이 오른쪽으로 흐르게 돌리는 행렬(page_frame). 칸 순서를 화면에서 읽는 방향으로 정한다.
    """
    if not words:
        return []
    p0, p1 = pymupdf.Point(p0), pymupdf.Point(p1)
    if not cells:
        return _flow(words, p0, p1)
    fr = frame or pymupdf.Matrix(1, 0, 0, 1, 0, 0)
    where = [_cell_of(cells, _center(w)) for w in words]
    c0, c1 = _cell_of(cells, p0), _cell_of(cells, p1)
    one = c0 is not None and c0 == c1  # 한 칸 안에서 끌었음: 그 칸 글자만

    # 칸 안에서 끈 점의 앞뒤는 거리가 아니라 읽는 순서로 정한다(읽는 방향으로 놓고 봄).
    # 낱말 높이 띠가 점 높이를 품으면 같은 줄로 보고 가로로, 아니면 위·아래로 견준다.
    # (가장 가까운 낱말로 정하면 칸보다 큰 글자 상자가 있는 칸에서 엉뚱한 낱말을 잡음)
    def after(w: Word, q: pymupdf.Point) -> bool:  # 낱말이 점보다 뒤(점에서 시작하면 들어감)
        r = (pymupdf.Rect(w[:4]) * fr).normalize()
        return r.y0 > q.y or (r.y0 <= q.y <= r.y1 and r.x1 >= q.x)

    def before(w: Word, q: pymupdf.Point) -> bool:  # 낱말이 점보다 앞(점에서 끝내면 들어감)
        r = (pymupdf.Rect(w[:4]) * fr).normalize()
        return r.y1 < q.y or (r.y0 <= q.y <= r.y1 and r.x0 <= q.x)

    def span(ws: list[Word], a, b) -> tuple[int, int]:
        """a부터 b까지의 낱말 번호 범위(없으면 lo > hi). a·b가 None이면 칸 처음·끝."""
        lo = 0 if a is None else next((n for n, w in enumerate(ws) if after(w, a * fr)), len(ws))
        hi = len(ws) - 1 if b is None else max((n for n, w in enumerate(ws) if before(w, b * fr)), default=-1)
        return lo, hi

    def pos(r: pymupdf.Rect) -> tuple:  # 읽는 방향 기준 (위, 왼쪽)
        q = (r * fr).normalize()
        return round(q.y0), round(q.x0)

    def pt_pos(pt: pymupdf.Point, c) -> tuple:
        return pos(cells[c]) if c is not None else pos(pymupdf.Rect(pt, pt))

    if pt_pos(p1, c1) < pt_pos(p0, c0):  # 거꾸로 끌었으면 시작·끝을 바꿈
        p0, p1, c0, c1 = p1, p0, c1, c0
    drag = pymupdf.Rect(p0, p1).normalize()
    # 칸 선에 겨우 닿은 옆 칸은 빼려고 칸을 조금 줄여 견준다
    touched = {c0} if one else {i for i, c in enumerate(cells) if _touches(c, drag)} | {c0, c1}
    picked: list[tuple] = []  # (정렬 키, 낱말)
    for i in sorted((i for i in touched if i is not None), key=lambda i: pos(cells[i])):
        ws = [w for w, c in zip(words, where) if c == i]  # 칸 안은 읽는 순서 그대로
        if not ws:
            continue
        # 시작 칸은 끈 시작점 뒤 낱말부터, 끝 칸은 끝점 앞 낱말까지, 가운데 칸은 통째로
        lo, hi = span(ws, p0 if i == c0 else None, p1 if i == c1 else None)
        if one and lo > hi:  # 한 칸 안에서 거꾸로(아래→위·오른→왼) 끌었음
            lo, hi = span(ws, p1, p0)
        picked += [(pos(cells[i]) + (n,), w) for n, w in enumerate(ws[lo:hi + 1])]
    if one:
        return [w for _, w in sorted(picked, key=lambda t: t[0])]
    # 표 밖 글자: 끈 사각형 안에 든 것(표와 표 사이 제목 등). 시작이나 끝이 표 밖이면 그 사이를
    # 줄 따라 흐르는 글도. 두 끝이 모두 칸 안이면 줄 따라 흐르는 글은 넣지 않는다(등기부는 한 행이
    # 글 덩어리 하나라 줄 순서로 보면 표 밖 머리말·꼬리말까지 사이에 들어옴)
    flow = {id(w) for w in _flow(words, p0, p1)} if c0 is None or c1 is None else set()
    outside = [w for w, c in zip(words, where)
               if c is None and (id(w) in flow or drag.contains(_center(w)))]
    picked += [(pos(pymupdf.Rect(w[:4])) + (n,), w) for n, w in enumerate(outside)]
    return [w for _, w in sorted(picked, key=lambda t: t[0])]


def _touches(cell: pymupdf.Rect, drag: pymupdf.Rect) -> bool:
    """끈 사각형이 칸에 닿았나. 칸 선에 겨우 걸친 옆 칸은 빼려고 칸을 1pt 줄여 견주고,
    수평·수직으로 곧게 끈 높이(폭) 0인 사각형도 닿은 것으로 친다(intersects는 빈 사각형을 빼므로)."""
    c = cell + (1, 1, -1, -1)
    return c.x0 <= drag.x1 and drag.x0 <= c.x1 and c.y0 <= drag.y1 and drag.y0 <= c.y1


def page_frame(page: pymupdf.Page) -> pymupdf.Matrix:
    """그 쪽 글줄이 오른쪽으로 흐르게 돌리는 행렬(표 칸 순서에 씀)."""
    return _reading_frame(page)


def _flow(words: list[Word], p0, p1) -> list[Word]:
    if not words:
        return []
    a = _nearest(words, pymupdf.Point(p0))
    b = _nearest(words, pymupdf.Point(p1))
    if a > b:
        a, b = b, a
    return words[a:b + 1]


def words_text(words: list[Word]) -> str:
    """같은 줄 낱말은 띄어 쓰고, 줄이 바뀌면 줄을 바꾼다."""
    lines: list[list[str]] = []
    key = None
    for w in words:
        k = (w[5], w[6])
        if k != key:
            lines.append([])
            key = k
        lines[-1].append(w[4])
    return "\n".join(" ".join(ws) for ws in lines)
