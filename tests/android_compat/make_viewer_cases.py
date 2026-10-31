"""8-2(열기·보기·찾기) 계측 시험 입력과 PC판 기대값을 만든다.

쓰는 법(저장소 루트): .venv/bin/python -m tests.android_compat.make_viewer_cases

만드는 것(android/app/src/androidTest/assets/):
  viewer_case.pdf    4쪽. p0 보통 쪽(영문·한글), p1 /Rotate 90, p2 CropBox 원점이 (100,200), p3 /Rotate 270 + CropBox 원점 다름.
                     쪽마다 빨간 네모 하나(렌더 방향·위치 확인용)와 낱말 "apple"(여러 쪽 찾기).
  viewer_locked.pdf  viewer_case.pdf를 사용자 암호 LOCK_PW로 잠근 것(AES-256).
  viewer_case.json   PC판(PyMuPDF)이 낸 기대값: 쪽 크기(돌린 뒤), 1배 렌더에서 빨간 네모 픽셀 범위,
                     찾기 결과(pdfviewer.core.doc_ops.search와 같은 화면 방향 사각형).
  viewer_long.pdf    2쪽. p0 아주 긴 쪽 100x14000, p1 아주 넓은 쪽 14000x100. 끝 가까이에 빨간 네모
                     (p0 (20,13000)-(80,13050), p1 (13000,20)-(13050,80)). 기본 그림 픽셀 한도·타일 시험용.
                     이것만 다시 만들려면 make_long()만 부른다(다른 견본의 바이트를 바꾸지 않게).
  viewer_crop.pdf    2쪽. CropBox 경계에 걸친 낱말 찾기(판 2 검토 HIGH). p0 MediaBox 400x300·CropBox (100,40,300,300),
                     p1 /Rotate 90 + CropBox. 낱말 "apple"을 경계에 걸침(첫·끝 글자가 완전히 밖 / 일부만 밖)·완전히 안·
                     완전히 밖에 둔다. viewer_crop.json에 PC판 기대값(doc_ops.search)을 둔다. make_crop()만 부른다.
  viewer_hyphen.pdf  3쪽. 하이픈으로 이어진 줄 찾기(판 3 검토 HIGH). viewer_hyphen.json에 줄 플래그와 PC판 기대값.
                     make_hyphen()만 부른다.
"""
from __future__ import annotations

import json
import os

import pymupdf

from pdfviewer.core import doc_ops

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "android", "app", "src", "androidTest", "assets")
LOCK_PW = "hanjang-8-2"
QUERIES = ["apple", "APPLE", "찾기", "한장 피디에프", "없는낱말xyz", "two words"]


def red_bbox(page: pymupdf.Page) -> list[int]:
    """1배 렌더(알파 없음 RGB)에서 빨간 픽셀(r>200, g<60, b<60)의 범위 [x0, y0, x1, y1](x1·y1 포함 안 함)."""
    pix = page.get_pixmap(matrix=pymupdf.Matrix(1, 1), alpha=False)
    xs, ys = [], []
    s = pix.samples
    for y in range(pix.height):
        row = y * pix.stride
        for x in range(pix.width):
            r, g, b = s[row + 3 * x], s[row + 3 * x + 1], s[row + 3 * x + 2]
            if r > 200 and g < 60 and b < 60:
                xs.append(x)
                ys.append(y)
    return [min(xs), min(ys), max(xs) + 1, max(ys) + 1]


def build() -> pymupdf.Document:
    doc = pymupdf.open()
    cjk = pymupdf.Font("cjk")

    def text(page, at, s, size=14):
        page.insert_font(fontname="HS", fontbuffer=cjk.buffer)
        page.insert_text(at, s, fontname="HS", fontsize=size)

    # p0 보통 쪽
    p = doc.new_page(width=595, height=842)
    text(p, (72, 100), "Hello apple world")
    text(p, (72, 140), "한장 피디에프 찾기 시험")
    text(p, (72, 180), "two words and Apple again")
    p.draw_rect(pymupdf.Rect(400, 600, 500, 650), color=None, fill=(1, 0, 0))
    # p1 /Rotate 90 (돌리기 전 300x500)
    p = doc.new_page(width=300, height=500)
    text(p, (40, 60), "rotated apple 찾기")
    p.draw_rect(pymupdf.Rect(20, 400, 80, 440), color=None, fill=(1, 0, 0))
    p.set_rotation(90)
    # p2 CropBox 원점 (100,200), 크기 300x400
    p = doc.new_page(width=600, height=800)
    text(p, (130, 260), "cropped apple")
    p.draw_rect(pymupdf.Rect(300, 500, 360, 540), color=None, fill=(1, 0, 0))
    text(p, (20, 40), "apple outside crop")  # CropBox 밖: 보이지 않고 찾기에도 안 걸려야 함(PC판 기준)
    p.set_cropbox(pymupdf.Rect(100, 200, 400, 600))
    # p3 /Rotate 270 + CropBox 원점 다름
    p = doc.new_page(width=400, height=600)
    text(p, (90, 120), "last apple page")
    p.draw_rect(pymupdf.Rect(250, 450, 330, 480), color=None, fill=(1, 0, 0))
    p.set_cropbox(pymupdf.Rect(50, 50, 380, 560))
    p.set_rotation(270)
    doc.subset_fonts()
    return doc


def expected(doc: pymupdf.Document) -> dict:
    pages = []
    for page in doc:
        r = page.rect  # 돌린 뒤 크기(원점 0)
        pages.append({"w": r.width, "h": r.height, "rotation": page.rotation, "red": red_bbox(page)})
    search = {}
    for q in QUERIES:
        search[q] = [[pno, [h.x0, h.y0, h.x1, h.y1]] for pno, h in doc_ops.search(doc, q)]
    return {"pages": pages, "search": search, "password": LOCK_PW}


def make_long() -> str:
    doc = pymupdf.open()
    p = doc.new_page(width=100, height=14000)
    p.draw_rect(pymupdf.Rect(20, 13000, 80, 13050), color=None, fill=(1, 0, 0))
    p = doc.new_page(width=14000, height=100)
    p.draw_rect(pymupdf.Rect(13000, 20, 13050, 80), color=None, fill=(1, 0, 0))
    out = os.path.join(OUT, "viewer_long.pdf")
    doc.save(out, garbage=3, deflate=True, no_new_id=True)
    doc.close()
    return out


def make_crop() -> dict:
    """CropBox 경계 찾기 견본과 PC판 기대값. 글자는 helv 20pt("apple" 폭 약 49pt, a 약 11pt)."""
    doc = pymupdf.open()

    def text(page, at):
        page.insert_text(at, "apple", fontname="helv", fontsize=20)

    # p0 CropBox (100,40,300,300). 왼쪽 경계 x=100, 오른쪽 x=300, 위 y=40
    p = doc.new_page(width=400, height=300)
    text(p, (80, 100))   # 경계 걸침: a(80~91)가 완전히 밖 → PC 0
    text(p, (96, 140))   # 경계 걸침: a(96~107)가 일부만 밖 → PC 1(사각형 x0<0)
    text(p, (150, 180))  # 완전히 안 → 1
    text(p, (10, 220))   # 완전히 밖 → 0
    text(p, (270, 260))  # 오른쪽 경계 걸침: e가 완전히 밖 → 0
    text(p, (200, 45))   # 위 경계 걸침: 모든 글자가 일부만 밖 → 1(사각형 y0<0)
    p.set_cropbox(pymupdf.Rect(100, 40, 300, 300))
    # p1 /Rotate 90 + CropBox (100,50,300,380)
    p = doc.new_page(width=300, height=400)
    text(p, (80, 100))   # 경계 걸침, a 완전히 밖 → 0
    text(p, (96, 250))   # 경계 걸침, a 일부만 밖 → 1
    text(p, (150, 200))  # 완전히 안 → 1
    text(p, (10, 300))   # 완전히 밖 → 0
    p.set_cropbox(pymupdf.Rect(100, 50, 300, 380))
    p.set_rotation(90)
    out = os.path.join(OUT, "viewer_crop.pdf")
    doc.save(out, garbage=3, deflate=True, no_new_id=True)
    doc.close()
    doc = pymupdf.open(out)
    exp = {"apple": [[pno, [h.x0, h.y0, h.x1, h.y1]] for pno, h in doc_ops.search(doc, "apple")]}
    doc.close()
    with open(os.path.join(OUT, "viewer_crop.json"), "w", encoding="utf-8") as f:
        json.dump(exp, f, ensure_ascii=False, indent=1)
    return exp


HYPHEN_QUERIES = ["method", "meth-od", "meth", "od", "international", "nation", "national", "l apple",
                  "well-known", "wellknown", "known", "hyphenated", "hyphen", "ated", "splitword", "split",
                  "word", "apple", "pie"]


def _line_flags(page: pymupdf.Page) -> list[tuple[int, str, int]]:
    """flags 83 글자 쪽의 줄마다 (줄 플래그, 글, JSON 출력의 flags). 둘이 같아야 안드로이드가 asJSON으로 읽을 수 있다."""
    m = pymupdf.mupdf
    tp = page.get_textpage(flags=83)
    buf = m.FzBuffer(256)
    out = m.FzOutput(buf)
    m.fz_print_stext_page_as_json(out, tp.this, 1.0)
    out.fz_close_output()
    js = json.loads(buf.fz_buffer_extract().decode())
    from_json = [l["bbox"].get("flags", 0) for b in js["blocks"] if b.get("type") == "text" for l in b["lines"]]
    real = []
    for block in tp.this:
        if block.m_internal.type != m.FZ_STEXT_BLOCK_TEXT:
            continue
        for line in block:
            ln = line.m_internal
            s, ch = "", ln.first_char
            while ch:
                s += chr(ch.c)
                ch = ch.next
            real.append((ln.flags, s))
    assert len(real) == len(from_json), (real, from_json)
    return [(f, s, j) for (f, s), j in zip(real, from_json)]


def make_hyphen() -> dict:
    """하이픈으로 이어진 줄(FZ_STEXT_LINE_FLAGS_JOINED) 찾기 견본과 PC판 기대값(판 3 검토 HIGH). make_hyphen()만 부른다.

    p0: 줄 끝 하이픈 이어짐(meth-/od), 줄 안 하이픈 낱말(well-known), 이어진 줄 둘(inter-/nation-/al),
        그 뒤 블록의 apple(앞 블록의 이어진 줄 때문에 PC 칸이 밀리는 것까지 같아야 함).
    p1: 하이픈 뒤 다음 줄이 다른 블록(hyphen- / 멀리 떨어진 ated), 같은 블록의 split-/word.
    p2: /Rotate 90 + meth-/od.
    """
    doc = pymupdf.open()

    def text(page, at, s):
        page.insert_text(at, s, fontname="helv", fontsize=20)

    p = doc.new_page(width=400, height=500)
    text(p, (72, 72), "meth-\nod")
    text(p, (72, 200), "a well-known fact")
    text(p, (72, 300), "inter-\nnation-\nal apple")
    text(p, (72, 450), "apple pie")
    p = doc.new_page(width=400, height=500)
    text(p, (72, 72), "hyphen-")
    text(p, (250, 400), "ated word")
    text(p, (72, 150), "split-")
    text(p, (72, 172), "word here")
    p = doc.new_page(width=300, height=400)
    text(p, (60, 80), "meth-\nod apple")
    p.set_rotation(90)
    out = os.path.join(OUT, "viewer_hyphen.pdf")
    doc.save(out, garbage=3, deflate=True, no_new_id=True)
    doc.close()
    doc = pymupdf.open(out)
    lines = []
    for page in doc:
        for f, s, j in _line_flags(page):
            assert f == j, (page.number, s, f, j)
            lines.append([page.number, s, f])
    assert sum(f for _, _, f in lines) == 6, lines  # 이어진 줄 여섯(meth- ×2, inter-, nation-, hyphen-, split-)
    exp = {"lines": lines, "search": {}}
    for q in HYPHEN_QUERIES:
        exp["search"][q] = [[pno, [h.x0, h.y0, h.x1, h.y1]] for pno, h in doc_ops.search(doc, q)]
    doc.close()
    with open(os.path.join(OUT, "viewer_hyphen.json"), "w", encoding="utf-8") as f:
        json.dump(exp, f, ensure_ascii=False, indent=1)
    return exp


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    doc = build()
    case = os.path.join(OUT, "viewer_case.pdf")
    doc.save(case, garbage=3, deflate=True)
    doc.close()
    doc = pymupdf.open(case)
    exp = expected(doc)
    doc.save(os.path.join(OUT, "viewer_locked.pdf"), encryption=pymupdf.PDF_ENCRYPT_AES_256,
             user_pw=LOCK_PW, owner_pw=LOCK_PW + "-owner", permissions=-1)
    doc.close()
    with open(os.path.join(OUT, "viewer_case.json"), "w", encoding="utf-8") as f:
        json.dump(exp, f, ensure_ascii=False, indent=1)
    lk = pymupdf.open(os.path.join(OUT, "viewer_locked.pdf"))
    assert lk.needs_pass and lk.authenticate(LOCK_PW) and lk.page_count == 4
    lk.close()
    for q, hits in exp["search"].items():
        print(q, len(hits))
    for i, p in enumerate(exp["pages"]):
        print(i, p)
    print("크기", os.path.getsize(case), os.path.getsize(os.path.join(OUT, "viewer_locked.pdf")))
    print("긴 쪽", os.path.getsize(make_long()))


if __name__ == "__main__":
    main()
