"""안드로이드판 화면 글 한국어·영어 검사(8-5).

- values/strings.xml(한국어)와 values-en/strings.xml(영어)의 이름이 빠짐없이 짝을 이루고, 자리표시(%1$s 등)가 같다.
- 영어 글에 한글이 남지 않는다. 한국어 글은 한글이 있다(이름·기호만인 것은 허용 목록).
- 화면 코드(ui/, MainActivity)에 글자 리터럴 Text("…")를 쓰지 않는다(숫자·기호만 허용, 글은 strings 리소스로).
- core가 던지는 한국어 글(코드 속 한글 문자열)은 모두 values/의 core_* 틀 하나에 맞는다(화면에서 ui/CoreText.kt가
  그 틀로 알아보고 영어로 바꾼다). 틀이 없는 새 한국어 글을 넣으면 이 시험이 실패한다.
- CoreText.kt의 IDS 목록이 strings.xml의 core_* 이름과 같다.

검사할 폴더는 환경변수 HANJANG_ANDROID_MAIN(app/src/main)으로 바꿀 수 있다(일부러 고친 사본에서 실패하는지 볼 때).
"""
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from tests.android_compat.test_fitz_refdrop import _strip_code

ROOT = Path(__file__).resolve().parents[2]
MAIN = Path(os.environ.get("HANJANG_ANDROID_MAIN") or ROOT / "android/app/src/main")
HANGUL = re.compile(r"[가-힣]")
ARG = re.compile(r"%(\d)\$[sd]")

# 한국어 쪽에도 한글이 없어도 되는 글(앱 이름의 영문·기호·숫자 틀)
KO_NO_HANGUL = {"ok", "about_copyright"}
# 화면에 나가지 않는 코드 속 한글(주석 작성자 값·내부 예외·시험용)
INTERNAL_KO = [
    re.compile(r"^PDF 뷰어$"),  # 주석 작성자(T) 값: PC판과 같아야 한다(annots.py)
    re.compile(r"^짧음$"),  # HSTextStyle 읽기 내부 예외(잡혀서 기본값으로)
    re.compile(r"^역행렬 없음$"),
    re.compile(r"^폴더 없음$"),
    re.compile(r"^폭 표"),  # 앱에 넣은 폭 표 형식 오류(빌드 결함)
    re.compile(r"^시험용 "),
    re.compile(r"^ \[번호\]$"),  # PageOps 이름 붙이기 꼬리표(문서 안 글)
    re.compile(r"^: 이진 값"),  # Geo.kt 설명 문자열 조각
]


def _strings(path):
    out = {}
    for e in ET.parse(path).getroot():
        if e.tag == "string":
            # 안드로이드가 읽을 때처럼 \' \" 풀기
            out[e.get("name")] = "".join(e.itertext()).replace("\\'", "'").replace('\\"', '"')
    return out


def _ko():
    return _strings(MAIN / "res/values/strings.xml")


def _en():
    return _strings(MAIN / "res/values-en/strings.xml")


def test_keys_pair_and_args_match():
    ko, en = _ko(), _en()
    assert not (set(ko) - set(en)), f"영어에 빠진 이름: {sorted(set(ko) - set(en))}"
    assert not (set(en) - set(ko)), f"한국어에 빠진 이름: {sorted(set(en) - set(ko))}"
    bad = [k for k in ko if sorted(ARG.findall(ko[k])) != sorted(ARG.findall(en[k]))]
    assert not bad, f"자리표시가 다른 글: {bad}"


def test_english_has_no_hangul_and_korean_has_hangul():
    ko, en = _ko(), _en()
    left = [f"{k}: {v}" for k, v in en.items() if HANGUL.search(v)]
    assert not left, "영어 글에 한글이 남음:\n" + "\n".join(left)
    untranslated = [k for k, v in ko.items() if not HANGUL.search(v) and k not in KO_NO_HANGUL and k != "app_name"]
    assert not untranslated, f"한국어 쪽에 한글이 없는 글(옮기지 않은 영어?): {untranslated}"


def _ui_sources():
    java = MAIN / "java/com/bubigter/hanjangpdf"
    return sorted((java / "ui").glob("*.kt")) + [java / "MainActivity.kt"]


def test_no_literal_text_in_ui():
    """Text("…")에 글자(한글·영문)를 바로 쓰지 않는다. 기호·숫자·문자열 템플릿만 허용."""
    bad = []
    for p in _ui_sources():
        src = p.read_text(encoding="utf-8")
        for m in re.finditer(r'\bText\(\s*"((?:[^"\\]|\\.)*)"', src):
            lit = re.sub(r"\$\{[^}]*\}|\$\w+", "", m.group(1))
            if re.search(r"[A-Za-z가-힣]", lit):
                bad.append(f"{p.name}:{src.count(chr(10), 0, m.start()) + 1}: {m.group(0)}")
    assert not bad, "화면 글은 strings 리소스로:\n" + "\n".join(bad)


def _korean_literals():
    """코드 속 한글 문자열 리터럴(주석 제외). 템플릿 ${…}·$이름 자리는 X로 바꾼 견본."""
    out = []
    for p in sorted((MAIN / "java").rglob("*.kt")):
        src = p.read_text(encoding="utf-8")
        code = _strip_code(src)
        # 주석을 지운 판에서 문자열이 시작하는 따옴표 자리를 찾고, 원문에서 그 문자열을 읽는다
        i = 0
        while True:
            j = code.find('"', i)
            if j < 0:
                break
            if code.startswith('"""', j):
                k = code.find('"""', j + 3)
                lit = src[j + 3:k]
                i = k + 3
            else:
                k = j + 1
                while k < len(src) and src[k] != '"':
                    if src[k] == "\\":
                        k += 1
                    elif src[k:k + 2] == "${":
                        depth = 1
                        k += 2
                        while depth and k < len(src):
                            depth += {"{": 1, "}": -1}.get(src[k], 0)
                            k += 1
                        continue
                    k += 1
                lit = src[j + 1:k]
                i = k + 1
            if HANGUL.search(lit):
                sample = re.sub(r"\$\{(?:[^{}]|\{[^{}]*\})*\}|\$[A-Za-z_]\w*", "X", lit).replace("\\'", "'")
                out.append((p.name, src.count("\n", 0, j) + 1, sample))
    return out


def _templates():
    ko = _ko()
    out = []
    for k, v in ko.items():
        if not k.startswith("core_"):
            continue
        parts = ARG.split(v)
        lit = [re.escape(x) for i, x in enumerate(parts) if i % 2 == 0]
        out.append((k, re.compile("(.*)".join(lit), re.S)))
    return out


def test_core_korean_messages_have_templates():
    tpl = _templates()
    assert tpl, "core_* 틀이 없습니다"
    lits = _korean_literals()
    assert lits, "코드 속 한글 문자열을 하나도 못 찾음(검사가 고장)"
    missing = []
    for name, line, s in lits:
        if any(r.search(s) for r in INTERNAL_KO):
            continue
        if not any(r.fullmatch(s) for _, r in tpl):
            missing.append(f"{name}:{line}: {s}")
    assert not missing, ("화면에 나갈 수 있는 한국어 글에 core_* 틀이 없음(영어 화면에 한글이 섞임). "
                         "values/·values-en/에 core_ 글을 더하고 ui/CoreText.kt IDS에 넣으세요:\n" + "\n".join(missing))


def test_coretext_ids_match_strings():
    src = (MAIN / "java/com/bubigter/hanjangpdf/ui/CoreText.kt").read_text(encoding="utf-8")
    ids = set(re.findall(r"R\.string\.(core_\w+)", src))
    names = {k for k in _ko() if k.startswith("core_")}
    assert ids == names, f"CoreText.IDS와 strings.xml core_*가 다름: 목록에만 {sorted(ids - names)}, xml에만 {sorted(names - ids)}"
