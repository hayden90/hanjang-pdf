"""8-3 쪽 작업 견본 만들기(맥에서 손으로 돌림, pytest가 모으지 않음).

android/app/src/androidTest/assets/ 에 아래를 만든다(모두 지어낸 글):
  pages_case.pdf    8쪽. 쪽마다 'P1'~'P8' 글. p2 /Rotate 90, p3 CropBox 원점 다름, p4 입력 서식(글 칸·체크 칸,
                    체크 칸은 Parent가 있는 칸), p5 형광펜·안쪽 링크(→p1)·웹 링크, p7은 /Rotate 없음(Pages 뿌리 180 물려받음),
                    목차(→p3, →p6, →p1).
  pages_insert.pdf  3쪽 'I1'~'I3'. 같은 이름 입력 칸 'name'(이름 겹침 처리 확인), I3→I1 링크.
  pages_locked.pdf  1쪽 'L1', 사용자 암호 'pw'.
  pages_scenario.json  시나리오(안드로이드 PagesTest와 맥 pages.py가 같이 씀).
쓰는 법(저장소 루트): .venv/bin/python -m tests.android_compat.make_pages_cases
"""
from __future__ import annotations

import json
import os

import pymupdf

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "android", "app", "src", "androidTest", "assets")

SCENARIOS = [
    {"name": "rot_mix", "ops": [
        {"op": "rotate", "pages": [1, 6, 6, 2], "deg": 90},
        {"op": "rotate", "pages": [6], "deg": -90},
        {"op": "rotate", "pages": [0, 5], "deg": 270},
        {"op": "rotate", "pages": [3], "deg": 180}]},
    {"name": "del_some", "ops": [{"op": "delete", "pages": [2, 4, 0, 2]}]},
    {"name": "move_fwd", "ops": [{"op": "move", "pages": [5, 1], "to": 0}]},
    {"name": "move_back", "ops": [{"op": "move", "pages": [2, 0], "to": 8}]},
    {"name": "move_mid", "ops": [{"op": "move", "pages": [6, 1, 3], "to": 4}]},
    {"name": "move_form_last", "ops": [{"op": "move", "pages": [3], "to": 8}, {"op": "move", "pages": [7], "to": 0}]},
    {"name": "insert_mid", "ops": [{"op": "insert", "file": "pages_insert.pdf", "at": 2}]},
    {"name": "insert_end_twice", "ops": [{"op": "insert", "file": "pages_insert.pdf", "at": 8},
                                          {"op": "insert", "file": "pages_insert.pdf", "at": 0}]},
    {"name": "insert_locked", "ops": [{"op": "insert", "file": "pages_locked.pdf", "at": 8, "password": "pw"}]},
    {"name": "export_some", "ops": [{"op": "export", "pages": [5, 3, 4, 0, 3]}]},
    {"name": "export_form", "ops": [{"op": "export", "pages": [3]}]},
    {"name": "combo", "ops": [
        {"op": "rotate", "pages": [0], "deg": 90},
        {"op": "move", "pages": [7], "to": 0},
        {"op": "delete", "pages": [4]},
        {"op": "insert", "file": "pages_insert.pdf", "at": 1},
        {"op": "move", "pages": [1, 2, 3], "to": 10}]},
    # 오류: PC와 같은 오류를 내고 문서는 그대로
    {"name": "err_delete_all", "ops": [{"op": "delete", "pages": [0, 1, 2, 3, 4, 5, 6, 7], "error": True}]},
    {"name": "err_rot45", "ops": [{"op": "rotate", "pages": [0], "deg": 45, "error": True}]},
    {"name": "err_move_to", "ops": [{"op": "move", "pages": [0], "to": 9, "error": True}]},
    {"name": "err_bad_page", "ops": [{"op": "delete", "pages": [8], "error": True}]},
    {"name": "err_empty", "ops": [{"op": "rotate", "pages": [], "deg": 90, "error": True}]},
    {"name": "err_locked_pw", "ops": [{"op": "insert", "file": "pages_locked.pdf", "at": 0, "password": "nope", "error": True}]},
]


def make_case(path: str) -> None:
    doc = pymupdf.open()
    for i in range(8):
        page = doc.new_page(width=595, height=842)
        page.insert_text((72, 100), f"P{i + 1}", fontsize=40)
        page.insert_text((72, 160), f"page body {i + 1}", fontsize=12)
    doc[1].set_rotation(90)
    doc[2].set_cropbox(pymupdf.Rect(100, 200, 500, 700))
    # p4 입력 서식: 글 칸 + 체크 칸
    w = pymupdf.Widget()
    w.field_type = pymupdf.PDF_WIDGET_TYPE_TEXT
    w.field_name = "name"
    w.rect = pymupdf.Rect(72, 300, 300, 330)
    w.field_value = "hello"
    doc[3].add_widget(w)
    w = pymupdf.Widget()
    w.field_type = pymupdf.PDF_WIDGET_TYPE_CHECKBOX
    w.field_name = "agree"
    w.rect = pymupdf.Rect(72, 360, 92, 380)
    w.field_value = True
    doc[3].add_widget(w)
    # p5 형광펜·링크
    p5 = doc[4]
    p5.add_highlight_annot(pymupdf.Rect(70, 70, 200, 110))
    p5.insert_link({"kind": pymupdf.LINK_GOTO, "from": pymupdf.Rect(72, 200, 200, 220), "page": 0,
                    "to": pymupdf.Point(72, 100), "zoom": 0})
    p5.insert_link({"kind": pymupdf.LINK_URI, "from": pymupdf.Rect(72, 240, 200, 260), "uri": "https://example.com/x"})
    p5.insert_link({"kind": pymupdf.LINK_GOTO, "from": pymupdf.Rect(72, 280, 200, 300), "page": 6,
                    "to": pymupdf.Point(50, 60), "zoom": 0})
    doc.set_toc([[1, "to P3", 3], [1, "to P6", 6], [2, "to P1", 1]])
    # p7: /Rotate 없이 Pages 뿌리의 180을 물려받게. 나머지 쪽은 /Rotate를 직접 둔다.
    pages_root = doc.pdf_catalog()
    root_pages = int(doc.xref_get_key(pages_root, "Pages")[1].split()[0])
    doc.xref_set_key(root_pages, "Rotate", "180")
    for i in range(8):
        if i == 6:
            doc.xref_set_key(doc[i].xref, "Rotate", "null")
        elif doc.xref_get_key(doc[i].xref, "Rotate")[0] == "null":
            doc.xref_set_key(doc[i].xref, "Rotate", "0")
    doc.save(path, garbage=3, deflate=True)
    doc.close()


def make_insert(path: str) -> None:
    doc = pymupdf.open()
    for i in range(3):
        page = doc.new_page(width=420, height=595)
        page.insert_text((60, 90), f"I{i + 1}", fontsize=40)
    w = pymupdf.Widget()
    w.field_type = pymupdf.PDF_WIDGET_TYPE_TEXT
    w.field_name = "name"
    w.rect = pymupdf.Rect(60, 200, 300, 230)
    w.field_value = "inserted"
    doc[1].add_widget(w)
    doc[2].insert_link({"kind": pymupdf.LINK_GOTO, "from": pymupdf.Rect(60, 300, 200, 320), "page": 0,
                        "to": pymupdf.Point(60, 90), "zoom": 0})
    doc.save(path, garbage=3, deflate=True)
    doc.close()


def make_locked(path: str) -> None:
    doc = pymupdf.open()
    page = doc.new_page(width=300, height=300)
    page.insert_text((40, 80), "L1", fontsize=40)
    doc.save(path, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="pw", owner_pw="owner-pw")
    doc.close()


def main() -> None:
    make_case(os.path.join(ASSETS, "pages_case.pdf"))
    make_insert(os.path.join(ASSETS, "pages_insert.pdf"))
    make_locked(os.path.join(ASSETS, "pages_locked.pdf"))
    with open(os.path.join(ASSETS, "pages_scenario.json"), "w", encoding="utf-8") as f:
        json.dump({"base": "pages_case.pdf", "scenarios": SCENARIOS}, f, ensure_ascii=False, indent=1)
    d = pymupdf.open(os.path.join(ASSETS, "pages_case.pdf"))
    print("pages_case", d.page_count, [p.rotation for p in d], "form", d.is_form_pdf, "toc", d.get_toc())
    d.close()


if __name__ == "__main__":
    main()
