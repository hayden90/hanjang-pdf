"""8-3 쪽 작업 PC ↔ 안드로이드 대조(맥에서 손으로 돌림, pytest가 모으지 않음).

안드로이드 PagesTest.a_scenarios가 files/pages/ 에 an_<시나리오>.pdf·an_<시나리오>.json을 남기면, 그 폴더를 W로 받아:
  .venv/bin/python -m tests.android_compat.pages compare W
같은 견본(assets)에 PC판 pdfviewer.core.doc_ops로 같은 작업을 해서 pc_<시나리오>.pdf를 만들고, 두 파일을 PyMuPDF로
읽어 쪽 수·쪽 순서(쪽 글)·회전값·쪽 상자·주석·링크·입력 칸(이름·종류·값·어느 쪽)·AcroForm 필드 트리
(/Fields→/Kids를 직접 따라가며 이름·종류·위젯이 든 쪽·/Parent 되가리킴, 트리에서 빠진 쪽 위젯)·목차를 견준다.
오류 시나리오는 두 쪽 모두 오류이고 문서가 그대로인지 본다. 종료 코드 0 = 모두 같음, 1 = 하나라도 다름(FAIL 줄).
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys

import pymupdf

from pdfviewer.core import doc_ops
from pdfviewer.core.doc_ops import DocOpError, atomic_save

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "android", "app", "src", "androidTest", "assets")
FAILS: list[str] = []
OKS = [0]


def check(ok: bool, what: str) -> None:
    if ok:
        OKS[0] += 1
    else:
        FAILS.append(what)
        print("FAIL " + what)


def _r(rect, nd=1):
    return [round(v, nd) for v in tuple(rect)]


def _name(n: str | None) -> str:
    """같은 이름 둘째 칸 뒤의 ' [번호]'(번호는 객체 번호라 구현마다 다름)는 '[#]'로 맞춘다."""
    return re.sub(r" \[\d+\]$", " [#]", n or "")


_REF = re.compile(r"(\d+) \d+ R")


def _refs(doc, xref: int, key: str) -> list[int] | None:
    """[xref]의 [key] 값(배열, 또는 배열을 가리키는 참조)에 든 객체 번호들. 없으면 None."""
    kind, val = doc.xref_get_key(xref, key)
    if kind == "null":
        return None
    if kind == "xref":
        val = doc.xref_object(int(val.split()[0]), compressed=True)
    return [int(m) for m in _REF.findall(val)]


def _str(doc, xref: int, key: str) -> str:
    kind, val = doc.xref_get_key(xref, key)
    return "" if kind == "null" else val


def field_tree(doc) -> tuple[list, list]:
    """AcroForm 필드 트리를 /Fields에서 /Kids로 따라 내려가며 직접 읽는다(쪽 위젯 목록으로 다시 만들지 않음).

    칸마다 [이름 경로, 종류(FT, 물려받음), 위젯인지, 어느 쪽 /Annots에 있는지(없으면 -1),
    /Parent가 실제로 그 부모를 가리키는지, 자식 수]. 둘째 값은 쪽에 있지만 트리에서 닿지 않는 위젯(쪽 번호·사각형).
    """
    cat = doc.pdf_catalog()
    on_page = {}
    widgets = []
    for p in doc:
        for x in _refs(doc, p.xref, "Annots") or []:
            on_page.setdefault(x, p.number)
            if _str(doc, x, "Subtype") == "/Widget":
                widgets.append((x, p.number))
    roots = _refs(doc, cat, "AcroForm/Fields")
    if roots is None:
        return [], sorted([pg, _str(doc, x, "Rect")] for x, pg in widgets)
    out, seen = [], set()

    def walk(x: int, parent: int | None, path: list[str], ft: str) -> None:
        if x in seen:  # 고리
            out.append(["/".join(path) + "/<고리>", "", False, -1, False, 0])
            return
        seen.add(x)
        t = _str(doc, x, "T")
        here = path + [t] if t else path
        ft = _str(doc, x, "FT") or ft
        par = _str(doc, x, "Parent")
        parent_ok = (par == "null" or par == "") if parent is None else par == f"{parent} 0 R"
        kids = _refs(doc, x, "Kids") or []
        out.append([_name(".".join(here)), ft, _str(doc, x, "Subtype") == "/Widget",
                    on_page.get(x, -1), parent_ok, len(kids)])
        for k in kids:
            walk(k, x, here, ft)

    for r in roots:
        walk(r, None, [], "")
    orphans = sorted([pg, _str(doc, x, "Rect")] for x, pg in widgets if x not in seen)
    return sorted(out), orphans


def summary(path: str) -> dict:
    doc = pymupdf.open(path)
    pages = []
    for p in doc:
        text = p.get_text().strip().split("\n")
        pages.append({
            "label": text[0] if text else "",
            "rotation": p.rotation,
            "mediabox": _r(p.mediabox), "cropbox": _r(p.cropbox),
            "annots": sorted([a.type[1], _r(a.rect)] for a in p.annots()),
            "links": sorted([l["kind"], l.get("page", -1), _r(l["from"]), l.get("uri", ""),
                             _r(l["to"]) if isinstance(l.get("to"), pymupdf.Point) else None]
                            for l in p.get_links()),
            "widgets": sorted([_name(w.field_name), w.field_type, str(w.field_value)] for w in p.widgets()),
        })
    fields, orphans = field_tree(doc)
    out = {"page_count": doc.page_count, "pages": pages, "fields": fields, "orphan_widgets": orphans,
           "form": bool(doc.is_form_pdf), "toc": doc.get_toc(), "needs_pass": doc.needs_pass}
    doc.close()
    return out


def run_pc(sc: dict, w: str) -> dict:
    """PC판으로 시나리오를 돌려 pc_<이름>.pdf(내보내기면 내보낸 파일)를 남기고 오류 여부를 돌려준다."""
    name = sc["name"]
    doc = pymupdf.open(os.path.join(ASSETS, "pages_case.pdf"))
    errors = []
    out = os.path.join(w, f"pc_{name}.pdf")
    exported = False
    for op in sc["ops"]:
        try:
            k = op["op"]
            if k == "rotate":
                doc_ops.rotate_pages(doc, op["pages"], op["deg"])
            elif k == "delete":
                doc_ops.delete_pages(doc, op["pages"])
            elif k == "move":
                doc_ops.move_pages(doc, op["pages"], op["to"])
            elif k == "insert":
                doc_ops.insert_pdf(doc, os.path.join(ASSETS, op["file"]), op["at"], op.get("password"))
            elif k == "export":
                doc_ops.export_pages(doc, op["pages"], out)
                exported = True
            errors.append(False)
        except DocOpError as e:
            errors.append(str(e))
    if not exported:
        atomic_save(doc, out)
    doc.close()
    return {"errors": errors}


def cmd_compare(w: str) -> None:
    sc_all = json.load(open(os.path.join(ASSETS, "pages_scenario.json"), encoding="utf-8"))["scenarios"]
    base = summary(os.path.join(ASSETS, "pages_case.pdf"))
    for sc in sc_all:
        name = sc["name"]
        an_pdf = os.path.join(w, f"an_{name}.pdf")
        an_json = os.path.join(w, f"an_{name}.json")
        check(os.path.exists(an_pdf) and os.path.exists(an_json), f"{name}: 안드로이드 결과 파일 있음")
        if not (os.path.exists(an_pdf) and os.path.exists(an_json)):
            continue
        pc = run_pc(sc, w)
        an = json.load(open(an_json, encoding="utf-8"))
        check(len(an["errors"]) == len(sc["ops"]), f"{name}: 안드로이드 결과 수 {len(an['errors'])} / 작업 {len(sc['ops'])}")
        for i, op in enumerate(sc["ops"]):
            want = bool(op.get("error"))
            pe = bool(pc["errors"][i])
            ae = bool(an["errors"][i]) if i < len(an["errors"]) else None
            check(pe == want, f"{name}[{i}] PC 오류 {pc['errors'][i]!r} / 기대 {want}")
            check(ae == pe, f"{name}[{i}] 오류 같음 PC={pc['errors'][i]!r} / AN={an['errors'][i] if i < len(an['errors']) else None!r}")
            if pe and ae:
                check(pc["errors"][i] == an["errors"][i], f"{name}[{i}] 오류 글 PC={pc['errors'][i]!r} / AN={an['errors'][i]!r}")
        ps = summary(os.path.join(w, f"pc_{name}.pdf"))
        a = summary(an_pdf)
        if all(op.get("error") for op in sc["ops"]):
            check(a == base, f"{name}: 오류 뒤 안드로이드 문서가 처음과 같음")
        for key in ("page_count", "form", "fields", "orphan_widgets", "toc", "needs_pass"):
            check(ps[key] == a[key], f"{name}: {key} PC={ps[key]} / AN={a[key]}")
        for i, (pp, ap) in enumerate(zip(ps["pages"], a["pages"])):
            for key in ("label", "rotation", "mediabox", "cropbox", "annots", "links", "widgets"):
                check(pp[key] == ap[key], f"{name} p{i}: {key} PC={pp[key]} / AN={ap[key]}")
        print(f"{name}: 쪽 {a['page_count']} {[p['label'] for p in a['pages']]} 회전 {[p['rotation'] for p in a['pages']]}")


def main() -> None:
    cmd, w = sys.argv[1], sys.argv[2]
    os.makedirs(w, exist_ok=True)
    {"compare": lambda: cmd_compare(w)}[cmd]()
    print(f"ok {OKS[0]} FAIL {len(FAILS)}")
    if FAILS:
        sys.exit(1)


if __name__ == "__main__":
    main()
