"""8-1b 안드로이드 ↔ PC 주석 호환 대조(맥에서 손으로 돌림. pytest가 모으지 않게 이름이 test_로 시작하지 않음).

입력은 android/app/src/androidTest/assets/compat_scenario.json(안드로이드 계측 시험과 같은 파일).
쓰는 법(저장소 루트에서, 작업 폴더 W):
  .venv/bin/python -m tests.android_compat.compat base W            # W/base.pdf
  .venv/bin/python -m tests.android_compat.compat pc-make W         # W/pc_made.pdf, pc_made.json
  .venv/bin/python -m tests.android_compat.compat compare W         # android_made.pdf/json ↔ PC 대조
  .venv/bin/python -m tests.android_compat.compat pc-edit W         # android_made.pdf를 PC로 고쳐 pc_edit_android.pdf
  .venv/bin/python -m tests.android_compat.compat verify-edits W    # 안드로이드가 고친 파일들을 PC로 확인
  .venv/bin/python -m tests.android_compat.compat sizes W           # 파일·글꼴 크기
종료 코드: 0 = 모든 확인 맞음, 1 = 하나라도 다름(출력에 FAIL 줄).
"""
from __future__ import annotations

import json
import os
import sys
from types import SimpleNamespace

import pymupdf

from pdfviewer.core import annots
from pdfviewer.core.doc_ops import atomic_save
from pdfviewer.ui.doc_tab import _same_rect
from pdfviewer.ui.page_view import PageView

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCENARIO = os.path.join(ROOT, "android", "app", "src", "androidTest", "assets", "compat_scenario.json")
FAILS: list[str] = []


def check(ok: bool, what: str) -> None:
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        FAILS.append(what)


def scenario() -> dict:
    with open(SCENARIO, encoding="utf-8") as f:
        return json.load(f)


def make_base(path: str) -> None:
    """2쪽 견본: 1쪽 400x600, 2쪽은 MediaBox·CropBox 원점이 0이 아니고 /Rotate 90."""
    d = pymupdf.open()
    p = d.new_page(width=400, height=600)
    p.insert_text((50, 330), "Hello World Sample office", fontsize=14)
    p.insert_text((50, 360), "Second line text", fontsize=12)
    p2 = d.new_page(width=500, height=700)
    d.xref_set_key(p2.xref, "MediaBox", "[-20 30 480 730]")
    d.xref_set_key(p2.xref, "CropBox", "[10 50 460 700]")
    p2 = d[1]
    p2.insert_text((50, 235), "Rotated page words here", fontsize=14)
    p2.set_rotation(90)
    d.save(path, garbage=3, deflate=True)
    d.close()


def _rect(v):
    return pymupdf.Rect(*v)


def apply(doc: pymupdf.Document, op: dict) -> dict:
    """한 작업을 PC 코드로. 결과(xref, rows 등)를 돌려준다."""
    k, pno = op["op"], op["pno"]
    out: dict = {"op": k, "pno": pno}
    if k == "add_text":
        page = doc[pno]
        pt = pymupdf.Point(*op["anchor"])
        out["rows"] = annots._box_rect(page, pt, annots._check_text(op["text"], op["size"], op["align"], op["color"]),
                                       op["size"], op["align"])[0]
        out["xref"] = annots.add_text(doc, pno, pt, op["text"], op["size"], op["align"], op["color"])
    elif k == "add_text_box":
        page = doc[pno]
        box = _rect(op["box"]).normalize()
        out["rows"] = annots._fixed_rect(page, box, annots._check_text(op["text"], op["size"], op["align"], op["color"]),
                                         op["size"])[0]
        out["xref"] = annots.add_text_box(doc, pno, _rect(op["box"]), op["text"], op["size"], op["align"], op["color"])
    elif k == "add_highlight":
        out["xref"] = annots.add_highlight(doc, pno, _rect(op["rect"]), op["color"])
    elif k == "add_note":
        out["xref"] = annots.add_note(doc, pno, pymupdf.Point(*op["point"]), op["text"])
    elif k == "add_box":
        out["xref"] = annots.add_box(doc, pno, _rect(op["rect"]), op["line"], op["fill"], op["width"], op["opacity"])
    elif k == "set_text":
        hit = annots.annot_at(doc, pno, pymupdf.Point(*op["at"]), prefer=pymupdf.PDF_ANNOT_FREE_TEXT)
        assert hit and hit["type"] == pymupdf.PDF_ANNOT_FREE_TEXT, f"글자 상자를 못 찾음: {op}"
        out["old"] = annots.text_style(doc, pno, hit["xref"])["text"]
        out["xref"] = annots.set_text(doc, pno, hit["xref"], op["text"], op["size"], op["align"], op["color"])
    elif k == "delete_in_rect":
        out["n"] = annots.delete_in_rect(doc, pno, _rect(op["rect"]))
        assert out["n"] == op["expect"], f"지운 개수 {out['n']} != {op['expect']}"
    elif k == "set_box_style":
        x = annots.box_at(doc, pno, pymupdf.Point(*op["at"]))
        assert x, f"상자를 못 찾음: {op}"
        annots.set_box_style(doc, pno, x, op["line"], op["fill"], op["width"], op["opacity"])
        out["xref"] = x
    elif k == "move_text":
        # PC판 화면 코드를 그대로 돈다(검토 판 1 지적 4: 따로 쓴 셈은 _top 처리가 빠져 있었음).
        # page_view._editor_top(쓰는 칸의 ed._top) → page_view._move_editor → doc_tab.commit_text(_same_rect·set_text).
        hit = annots.annot_at(doc, pno, pymupdf.Point(*op["at"]), prefer=pymupdf.PDF_ANNOT_FREE_TEXT)
        assert hit and hit["type"] == pymupdf.PDF_ANNOT_FREE_TEXT, f"옮길 글자 상자를 못 찾음: {op}"
        st = annots.text_style(doc, pno, hit["xref"])
        assert st["text"] == op["text"], f"옮길 상자 글 {st['text']!r} != {op['text']!r}"
        text = op.get("new_text", st["text"])  # 쓰는 칸에서 글을 고친 뒤 옮김(글이 늘어 쪽 아래 끝에서 밀릴 수 있음)
        box = _pc_move(doc, pno, hit["xref"], st["edit_box"], text, st["size"], op["dx"], op["dy"])
        if _same_rect(st["edit_box"], box) and text == st["text"]:  # commit_text: 자리·글·모양 그대로면 고치지 않음
            out["xref"], out["skipped"] = hit["xref"], True
        else:
            out["rows"] = annots._fixed_rect(doc[pno], box, annots._check_text(text, st["size"], st["align"], st["color"]),
                                             st["size"])[0]
            out["xref"] = annots.set_text(doc, pno, hit["xref"], text, st["size"], st["align"], st["color"], None, box)
    elif k == "move_new_text":
        # 새로 끈 쓰는 칸에 글을 쓰고 넣기 전에 손잡이로 옮김(쪽 아래 끝이면 칸이 밀려 보이던 자리부터) → commit_text의 add_text_box
        box = _pc_move(doc, pno, None, _rect(op["box"]).normalize(), op["text"], op["size"], op["dx"], op["dy"])
        out["rows"] = annots._fixed_rect(doc[pno], box, annots._check_text(op["text"], op["size"], op["align"], op["color"]),
                                         op["size"])[0]
        out["xref"] = annots.add_text_box(doc, pno, box, op["text"], op["size"], op["align"], op["color"])
    elif k == "set_note":
        hit = annots.annot_at(doc, pno, pymupdf.Point(*op["at"]), prefer=pymupdf.PDF_ANNOT_TEXT)
        assert hit and hit["type"] == pymupdf.PDF_ANNOT_TEXT, f"메모를 못 찾음: {op}"
        annots.set_note_text(doc, pno, hit["xref"], op["text"])
        out["xref"] = hit["xref"]
    else:
        raise ValueError(k)
    return out


def _pc_move(doc, pno, xref, box, text, size, dx, dy):
    """PC판 PageView의 쓰는 칸 옮기기를 화면 없이(배율 1) 돌려 옮긴 쓰는 칸 자리(돌리기 전)를 돌려준다.

    쓰는 칸은 쪽 아래 끝에 닿을 때만 ed._top(= _editor_top)을 구하는데(text_box_editor._fit_height),
    넣을 상자가 위로 밀리는 것도 그때뿐이라 늘 구해도 _move_editor의 결과는 같다(밀리지 않으면 _top이 상자 위끝).
    줄 나눔은 commit 쪽과 같게 rows=None(안드로이드도 같음).
    """
    view = SimpleNamespace(doc=doc, zoom=1.0, _place_editor=lambda: None)
    ed = SimpleNamespace(_top_key=None, _top=None)
    view._editor, view._editor_at = ed, (pno, pymupdf.Rect(box), xref)
    ed._top = PageView._editor_top(view, pno, view._editor_at[1], text, size, None)
    PageView._move_editor(view, ed, dx, dy)
    return view._editor_at[1]


def _key(doc, x, k):
    kind, v = doc.xref_get_key(x, k)
    return None if kind == "null" else v


def dump(path: str) -> list[dict]:
    """파일의 주석을 쪽·순서대로 PDF 값 그대로 적는다(대조용)."""
    doc = pymupdf.open(path)
    out = []
    for pno in range(doc.page_count):
        page = doc[pno]
        for a in page.annots():
            x = a.xref
            rec = {"pno": pno, "type": a.type[1], "own": annots._is_own(a), "T": a.info.get("title"),
                   "Subj": a.info.get("subject"), "Contents": a.info.get("content"),
                   "F": _key(doc, x, "F"), "Rect": _key(doc, x, "Rect"), "Rotate": _key(doc, x, "Rotate"),
                   "C": _key(doc, x, "C"), "IC": _key(doc, x, "IC"), "CA": _key(doc, x, "CA"),
                   "QuadPoints": _key(doc, x, "QuadPoints"), "rect": list(a.rect)}
            if a.type[0] == pymupdf.PDF_ANNOT_FREE_TEXT:
                rec["RC"] = _rc_text(doc, x)
                rec["HSTextStyle"] = _key(doc, x, annots.STYLE_KEY)
                rec["drawn_key"] = list(annots._read_key(a.get_textpage().extractText()))
                if rec["own"]:
                    st = annots.text_style(doc, pno, x)
                    rec["style"] = {"size": st["size"], "align": st["align"], "color": st["color"],
                                    "anchor": list(st["anchor"]), "box": st["box"] and list(st["box"]),
                                    "shown": st["shown"] and list(st["shown"])}
            if a.type[0] == pymupdf.PDF_ANNOT_SQUARE:
                rec["border_width"] = (a.border or {}).get("width")
                rec["fill"] = (a.colors or {}).get("fill")
                rec["stroke"] = (a.colors or {}).get("stroke")
                rec["opacity"] = a.opacity
            out.append(rec)
    doc.close()
    return out


def _rc_text(doc, x) -> str:
    """RC 값을 풀린 글자로(PDF 문자열 인코딩 차이 없이 비교)."""
    obj = pymupdf.mupdf.pdf_load_object(pymupdf._as_pdf_document(doc), x)
    return pymupdf.mupdf.pdf_to_text_string(pymupdf.mupdf.pdf_dict_gets(obj, "RC"))


def page_text(path: str) -> list[str]:
    """렌더 기준 글자: 주석 모양을 쪽 내용으로 굽고(bake) 읽은 것(쪽마다, 띄어쓰기 뺌)."""
    doc = pymupdf.open(path)
    doc.bake(annots=True, widgets=False)
    out = [annots._squash(p.get_text()) for p in doc]
    doc.close()
    return out


def seen(text: str, page_txt: str) -> bool:
    """글자 상자 글이 렌더 글자에 보이나(MuPDF는 ff·ffi를 이음 글자 하나로 읽기도 함)."""
    want = annots._squash(text)
    lig = want.replace("ffi", "\ufb03").replace("ff", "\ufb00")
    return want in page_txt or lig in page_txt


def cmd_pc_make(w: str) -> None:
    sc = scenario()
    doc = pymupdf.open(os.path.join(w, "base.pdf"))
    res = [apply(doc, op) for op in sc["make"]]
    atomic_save(doc, os.path.join(w, "pc_made.pdf"))
    doc.close()
    with open(os.path.join(w, "pc_made.json"), "w", encoding="utf-8") as f:
        json.dump({"results": res, "dump": dump(os.path.join(w, "pc_made.pdf"))}, f, ensure_ascii=False, indent=1)
    print("pc_made.pdf", os.path.getsize(os.path.join(w, "pc_made.pdf")), "bytes")


FT_KEYS = ("T", "Subj", "Contents", "F", "Rect", "Rotate", "RC", "HSTextStyle", "drawn_key", "style", "own")
OTHER_KEYS = ("type", "own", "T", "Subj", "Contents", "F", "Rect", "C", "IC", "CA", "border_width", "fill", "stroke", "opacity")


def cmd_compare(w: str) -> None:
    pc = json.load(open(os.path.join(w, "pc_made.json"), encoding="utf-8"))
    an = json.load(open(os.path.join(w, "android_made.json"), encoding="utf-8"))
    ad = dump(os.path.join(w, "android_made.pdf"))
    check(len(ad) == len(pc["dump"]), f"주석 수 PC {len(pc['dump'])} / 안드로이드 {len(ad)}")
    for i, (p, a) in enumerate(zip(pc["dump"], ad)):
        keys = FT_KEYS if p["type"] == "FreeText" else OTHER_KEYS
        for k in keys:
            if k in p or k in a:
                check(p.get(k) == a.get(k), f"[{i}] p{p['pno']} {p['type']} {k}: PC={p.get(k)!r} / AN={a.get(k)!r}")
        if p["type"] == "Highlight":
            # 형광펜 사각형이 PC와 다르면 실패(검토 판 1 지적 4: 예전에는 note만 냄)
            check(p["QuadPoints"] == a["QuadPoints"],
                  f"[{i}] Highlight QuadPoints PC={p['QuadPoints']} / AN={a['QuadPoints']}")
    # 안드로이드가 계산한 줄 나눔·읽은 글자도 PC와 같은지.
    # 결과 개수가 시나리오와 다르면 실패(판 2 검토 MEDIUM: zip이 짧은 쪽에 맞춰 남는 것을 견주지 않고 지나감)
    make = scenario()["make"]
    check(len(an["results"]) == len(make), f"안드로이드 make 결과 수 {len(an['results'])} / 시나리오 {len(make)}")
    check(len(pc["results"]) == len(make), f"PC make 결과 수 {len(pc['results'])} / 시나리오 {len(make)}")
    for i, (r, op) in enumerate(zip(an["results"], make)):
        pr = pc["results"][i]
        if "rows" in pr:
            check(pr["rows"] == r.get("rows"), f"make[{i}] {op['op']} 줄 나눔: PC={pr['rows']} / AN={r.get('rows')}")
    # 렌더해 글자가 보이는지(안드로이드 파일을 PC가 그렸을 때 글자 상자 글이 읽히는가)
    txt = page_text(os.path.join(w, "android_made.pdf"))
    for op in scenario()["make"]:
        if op["op"] in ("add_text", "add_text_box", "move_new_text"):
            check(seen(op["text"], txt[op["pno"]]),
                  f"렌더 글자 보임 p{op['pno']} {op['text'][:10]!r}")


def cmd_pc_edit(w: str) -> None:
    sc = scenario()
    doc = pymupdf.open(os.path.join(w, "android_made.pdf"))
    for op in sc["pc_edits_android"]:
        r = apply(doc, op)
        print("PC 고침", r)
    atomic_save(doc, os.path.join(w, "pc_edit_android.pdf"))
    doc.close()


def _find_text(recs, pno, text):
    return [r for r in recs if r["pno"] == pno and r["type"] == "FreeText" and r["Contents"] == text]


def _verify_edits(path: str, ops: list[dict], label: str) -> None:
    recs = dump(path)
    for r in recs:
        check(r["own"], f"{label}: 모든 주석이 '내 주석' p{r['pno']} {r['type']} {r.get('Contents')!r}")
        if r["type"] in ("FreeText", "Square"):
            check(r["F"] == "4", f"{label}: Print 표시 p{r['pno']} {r['type']} F={r['F']}")
    for op in ops:
        if op["op"] == "set_text":
            hit = _find_text(recs, op["pno"], op["text"])
            check(len(hit) == 1, f"{label}: 고친 글 있음 {op['text']!r}")
            if hit:
                st = hit[0]["style"]
                check((st["size"], st["align"], st["color"]) == (op["size"], op["align"], op["color"]),
                      f"{label}: 고친 글 모양 {st['size']},{st['align']},{st['color']}")
                # 그림에서 읽은 글자가 기대 원문과 같아야 통과(검토 판 1 지적 4: 비어 있지 않기만 하면 통과하던 것을 없앰).
                # 상자 폭에 따른 줄 나눔은 견주지 않고 줄들을 이어 붙여 견준다(넘쳐 안 그려진 줄·다른 원문은 실패).
                got = "".join(hit[0]["drawn_key"])
                want = "".join(annots._read_key(op["text"]))
                check(got == want, f"{label}: 고친 글 그림에서 읽힘 {hit[0]['drawn_key']} / 기대 {want!r}")
                ro = hit[0]["Rotate"]
                check((ro == "90") == (op["pno"] == 1), f"{label}: 글자 상자 Rotate={ro} (p{op['pno']})")
        if op["op"] == "set_box_style":
            doc = pymupdf.open(path)
            x = annots.box_at(doc, op["pno"], pymupdf.Point(*op["at"]))
            a = [r for r in recs if r["type"] == "Square" and r["pno"] == op["pno"] and pymupdf.Rect(r["rect"]).contains(pymupdf.Point(*op["at"]))]
            doc.close()
            check(bool(x) and len(a) >= 1, f"{label}: 상자 찾음")
            if a:
                want_fill = list(annots._rgb(op["fill"])) if op["fill"] else []
                got = a[-1]["fill"] or []
                check(all(abs(g - v) < 1e-3 for g, v in zip(got, want_fill)) and len(got) == len(want_fill),
                      f"{label}: 상자 채우기 {got} / 기대 {want_fill}")
                check(abs(a[-1]["opacity"] - op["opacity"]) < 1e-3 or (op["opacity"] == 1.0 and a[-1]["opacity"] in (-1, 1.0)),
                      f"{label}: 상자 진하기 {a[-1]['opacity']} / 기대 {op['opacity']}")
                check(a[-1]["border_width"] == (op["width"] if op["line"] else 0), f"{label}: 선 굵기 {a[-1]['border_width']}")
    print(f"{label}: 주석 {len(recs)}개")


def _same_as_pc(inp: str, ops: list[dict], got: str, label: str) -> None:
    """8-4: 안드로이드가 옮긴 글자 상자·고친 메모가 PC가 같은 입력에 같은 작업을 했을 때와 PDF 값까지 같은지."""
    doc = pymupdf.open(inp)
    for op in ops:
        apply(doc, op)
    exp = os.path.join(os.path.dirname(got), "pc_expect_" + os.path.basename(got))
    atomic_save(doc, exp)
    doc.close()
    pc, an = dump(exp), dump(got)
    for i, op in enumerate(ops):
        if op["op"] not in ("move_text", "set_note"):
            continue
        typ = "FreeText" if op["op"] == "move_text" else "Text"
        text = op.get("new_text", op["text"])
        for later in ops[i + 1:]:  # 뒤 작업이 같은 상자의 글을 바꿨으면 마지막 글로 찾는다
            if later["op"] == op["op"] and later["pno"] == op["pno"] and later["text"] == text:
                text = later.get("new_text", text)
        want = [r for r in pc if r["pno"] == op["pno"] and r["type"] == typ and r["Contents"] == text]
        have = [r for r in an if r["pno"] == op["pno"] and r["type"] == typ and r["Contents"] == text]
        check(len(want) == 1 and len(have) == 1, f"{label}: {op['op']} {text!r} PC {len(want)} / AN {len(have)}")
        if len(want) == 1 and len(have) == 1:
            for k in (FT_KEYS if typ == "FreeText" else OTHER_KEYS):
                if k in want[0] or k in have[0]:
                    check(want[0].get(k) == have[0].get(k),
                          f"{label}: {op['op']} {k}: PC={want[0].get(k)!r} / AN={have[0].get(k)!r}")


REQUIRED_EDITS = ("android_edit_pc.pdf", "android_edit2.pdf", "two_saves.pdf")


def cmd_verify_edits(w: str) -> None:
    sc = scenario()
    # 왕복 결과 PDF가 없으면 실패(판 2 검토 MEDIUM: 하나도 없어도 아무것도 안 보고 종료 0이었음)
    for name in REQUIRED_EDITS:
        check(os.path.exists(os.path.join(w, name)), f"왕복 결과 파일 있음 {name}")
    p = os.path.join(w, "android_edit_pc.pdf")
    if os.path.exists(p):
        _verify_edits(p, sc["android_edits_pc"], "안드로이드가 PC 파일 고침")
        _same_as_pc(os.path.join(w, "pc_made.pdf"), sc["android_edits_pc"], p, "안드로이드가 PC 파일 고침(PC와 같은 값)")
        n_pc = len(dump(os.path.join(w, "pc_made.pdf")))
        check(len(dump(p)) == n_pc - 1, f"안드로이드가 PC 파일 고침: 주석 수 {len(dump(p))} = {n_pc}-1")
    p = os.path.join(w, "android_edit2.pdf")
    if os.path.exists(p):
        _verify_edits(p, sc["android_edits_again"], "PC가 고친 것을 안드로이드가 또 고침")
        _same_as_pc(os.path.join(w, "pc_edit_android.pdf"), sc["android_edits_again"], p, "또 고침(PC와 같은 값)")
        n = len(dump(os.path.join(w, "pc_edit_android.pdf")))
        check(len(dump(p)) == n - 1, f"또 고침: 주석 수 {len(dump(p))} = {n}-1")
        # PC가 또 열어 고칠 수 있는지(3번째 왕복)
        doc = pymupdf.open(p)
        hit = annots.annot_at(doc, 0, pymupdf.Point(370, 140), prefer=pymupdf.PDF_ANNOT_FREE_TEXT)
        x = annots.set_text(doc, 0, hit["xref"], "PC 세 번째", 12, "left", "#000000")
        check(annots.text_style(doc, 0, x)["text"] == "PC 세 번째", "PC가 안드로이드 두 번째 판을 다시 고침")
        doc.close()
    p = os.path.join(w, "two_saves.pdf")
    if os.path.exists(p):
        recs = dump(p)
        for op in sc["two_saves"]:
            hit = _find_text(recs, op["pno"], op["text"])
            check(len(hit) == 1, f"두 번 저장: 상자 있음 {op['text']!r}")
            if hit:
                ref = list(annots._read_key("\n".join(op["text"].split("\n"))))
                check(hit[0]["drawn_key"] == ref, f"두 번 저장: 그림에서 글자 그대로 {hit[0]['drawn_key']} / {ref}")
        txt = page_text(p)[0]
        for op in sc["two_saves"]:
            check(seen(op["text"], txt), f"두 번 저장: 렌더 글자 보임 {op['text']!r}")


def cmd_sizes(w: str) -> None:
    for name in ("base.pdf", "pc_made.pdf", "android_made.pdf", "android_edit_pc.pdf", "pc_edit_android.pdf",
                 "android_edit2.pdf", "two_saves.pdf"):
        p = os.path.join(w, name)
        if not os.path.exists(p):
            continue
        doc = pymupdf.open(p)
        fonts = []
        for x in range(1, doc.xref_length()):
            try:
                t = doc.xref_get_key(x, "Type")[1]
                st = doc.xref_get_key(x, "Subtype")[1]
            except Exception:
                continue
            if t == "/Font" and st != "/Type0":
                bf = doc.xref_get_key(x, "BaseFont")[1]
                fd = doc.xref_get_key(x, "FontDescriptor")[1]
                ff = 0
                if fd and fd != "null":
                    fdx = int(fd.split()[0])
                    for k in ("FontFile", "FontFile2", "FontFile3"):
                        v = doc.xref_get_key(fdx, k)[1]
                        if v and v != "null":
                            ff = len(doc.xref_stream_raw(int(v.split()[0])))
                fonts.append(f"{bf}:{ff}")
        print(f"{name}: {os.path.getsize(p)} bytes, 글꼴 {fonts}")
        doc.close()


def main() -> None:
    cmd, w = sys.argv[1], sys.argv[2]
    os.makedirs(w, exist_ok=True)
    {"base": lambda: make_base(os.path.join(w, "base.pdf")), "pc-make": lambda: cmd_pc_make(w),
     "compare": lambda: cmd_compare(w), "pc-edit": lambda: cmd_pc_edit(w),
     "verify-edits": lambda: cmd_verify_edits(w), "sizes": lambda: cmd_sizes(w)}[cmd]()
    if FAILS:
        print(f"FAIL {len(FAILS)}건")
        sys.exit(1)


if __name__ == "__main__":
    main()
