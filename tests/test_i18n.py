"""화면 언어(한국어·영어): 감싸지 않은 한국어 문구·사전에 없는 문구가 없는지, 영어 화면이 실제로 영어로 뜨는지."""
import ast
import os
import pathlib
import re
import string
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from PySide6.QtCore import QLocale  # noqa: E402

from pdfviewer import i18n  # noqa: E402
from pdfviewer.i18n_en import EN  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = ROOT / "pdfviewer"
HANGUL = re.compile("[가-힣]")
# 일부러 번역하지 않는 한국어: 예전 주석을 알아보는 작성자 표식, 드라이브 폴더 이름, 언어 메뉴(두 말로 적음)
KEEP = {
    ("core/annots.py", "PDF 뷰어"),
    ("core/im_index.py", "내 드라이브"),
    ("ui/main_window.py", "Language / 언어"),
    ("ui/main_window.py", "한국어"),
    ("ui/main_window.py", "다시 시작하면 한국어로 바뀝니다."),
}


def _sources():
    for p in sorted(PKG.rglob("*.py")):
        rel = p.relative_to(PKG).as_posix()
        if rel in ("i18n.py", "i18n_en.py"):
            continue
        yield rel, ast.parse(p.read_text(encoding="utf-8"))


def _docstrings(tree):
    out = set()
    for n in ast.walk(tree):
        if isinstance(n, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and n.body:
            first = n.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant):
                out.add(id(first.value))
    return out


def _tr_keys():
    keys = {}
    for rel, tree in _sources():
        for n in ast.walk(tree):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "tr":
                assert len(n.args) == 1 and isinstance(n.args[0], ast.Constant), f"{rel}:{n.lineno} tr()에는 글자 그대로만"
                keys.setdefault(n.args[0].value, f"{rel}:{n.lineno}")
    return keys


def test_every_korean_ui_string_goes_through_tr():
    loose = []
    for rel, tree in _sources():
        docs = _docstrings(tree)
        wrapped = set()
        for n in ast.walk(tree):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "tr":
                wrapped.update(id(a) for a in n.args)
        for n in ast.walk(tree):
            if isinstance(n, ast.JoinedStr) and any(
                    isinstance(v, ast.Constant) and HANGUL.search(str(v.value)) for v in n.values):
                loose.append(f"{rel}:{n.lineno} f-문자열")
            elif (isinstance(n, ast.Constant) and isinstance(n.value, str) and HANGUL.search(n.value)
                  and id(n) not in docs and id(n) not in wrapped and (rel, n.value) not in KEEP):
                loose.append(f"{rel}:{n.lineno} {n.value[:40]!r}")
    # f-문자열 안의 글자 조각은 ast.walk가 따로 내놓으므로 위 f-문자열 줄로 함께 잡힌다
    assert not loose, "tr()로 감싸지 않은 한국어:\n" + "\n".join(loose)


def _fields(s):
    return sorted(f for _, f, _, _ in string.Formatter().parse(s) if f is not None)


def test_dictionary_matches_code_exactly():
    keys = _tr_keys()
    missing = [f"{where} {k!r}" for k, where in keys.items() if k not in EN]
    extra = [k for k in EN if k not in keys]
    assert not missing, "영어 사전에 없는 문구:\n" + "\n".join(missing)
    assert not extra, "코드에서 안 쓰는 사전 항목:\n" + "\n".join(map(repr, extra))
    for k, v in EN.items():
        assert _fields(k) == _fields(v), f"자리표시가 다름: {k!r} → {v!r}"
        assert not HANGUL.search(v), f"영어 번역에 한국어가 남음: {v!r}"


def test_pick_lang(monkeypatch):
    monkeypatch.delenv("PDFV_LANG", raising=False)
    assert i18n.pick_lang(saved="en", system=QLocale.Language.Korean) == "en"
    assert i18n.pick_lang(saved="ko", system=QLocale.Language.English) == "ko"
    assert i18n.pick_lang(saved="", system=QLocale.Language.Korean) == "ko"
    assert i18n.pick_lang(saved="", system=QLocale.Language.English) == "en"
    assert i18n.pick_lang(saved="fr", system=QLocale.Language.Japanese) == "en"
    monkeypatch.setenv("PDFV_LANG", "ko")
    assert i18n.pick_lang(saved="en", system=QLocale.Language.English) == "ko"


def test_save_lang_is_read_back(tmp_path, monkeypatch):
    monkeypatch.setenv("PDFV_SETTINGS", str(tmp_path / "s.ini"))
    monkeypatch.delenv("PDFV_LANG", raising=False)
    i18n.save_lang("en")
    assert i18n.pick_lang(system=QLocale.Language.Korean) == "en"
    i18n.save_lang("ko")
    assert i18n.pick_lang(system=QLocale.Language.English) == "ko"


ENGLISH_PROBE = r"""
import os, re, sys
sys.path.insert(0, sys.argv[1])
from PySide6.QtWidgets import QApplication, QMenu, QToolBar
from pdfviewer import i18n
assert i18n.LANG == "en", i18n.LANG
from pdfviewer.ui.main_window import MainWindow, ABOUT_TEXT
from pdfviewer.ui.thumbs import ThumbList  # noqa: F401  (불러오기만 해도 모듈 상수가 영어인지 본다)
app = QApplication([])
w = MainWindow()
w.show()
seen = [w.windowTitle(), ABOUT_TEXT, w.statusBar().currentMessage()]
def walk(menu):
    for a in menu.actions():
        seen.extend([a.text(), a.toolTip()])
        if a.menu():
            walk(a.menu())
for a in w.menuBar().actions():
    seen.append(a.text())
    walk(a.menu())
for tb in w.findChildren(QToolBar):
    for a in tb.actions():
        seen.extend([a.text(), a.toolTip()])
for m in w.findChildren(QMenu):
    seen.append(m.title())
    walk(m)
allowed = {"한국어", "Language / 언어"}
bad = [s for s in seen if s and re.search("[가-힣]", s) and s not in allowed]
print("CHECKED", len(seen))
print("BAD", bad)
print("TITLE", w.windowTitle())
"""


def test_english_window_has_no_korean(tmp_path):
    env = {**os.environ, "PDFV_LANG": "en", "QT_QPA_PLATFORM": "offscreen",
           "PDFV_SETTINGS": str(tmp_path / "s.ini")}
    r = subprocess.run([sys.executable, "-c", ENGLISH_PROBE, str(ROOT)], env=env,
                       capture_output=True, text=True, encoding="utf-8", timeout=120)
    assert r.returncode == 0, r.stderr[-2000:]
    out = r.stdout
    assert "BAD []" in out, out
    assert "TITLE HanjangPDF" in out
    assert int(re.search(r"CHECKED (\d+)", out).group(1)) > 80
