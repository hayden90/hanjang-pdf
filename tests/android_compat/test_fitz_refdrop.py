"""안드로이드 앱 코드에서 fitz Java의 참조를 내리는 호출을 막는 정적 검사(8-4 검토 판 2 지적 3).

fitz Java의 PDFDocument.addObject와 PDFObject.push(PDFObject)는 넘긴 껍데기의 참조를 내린다. 그 껍데기가
가비지 수거될 때 문서 안 객체가 한 번 더 풀려 끼워 넣기·내보내기 뒤 "cycle in page tree"나 앱 죽음이 난다
(8-4 판 1 고침). 대신 PageOps.addRef(createObject+writeObject)와 append(put 끝 번호)를 쓴다.

- addObject( 는(백틱으로 감싸도) 주석 밖에서 한 번도 쓰지 않는다. 메서드 참조(::addObject, ::push)도 쓰지 않는다(8-4 판 3 LOW).
- push( 는(점 없이 부르거나 줄을 나눠도) 소스만으로 인자 형을 알 수 없어, 숫자를 넣는 것으로 확인한 줄만 허용 목록에 둔다.
  새 push를 넣으면 이 검사가 실패한다: 숫자면 목록에 더하고, 객체면 append로 바꾼다.

검사할 폴더는 환경변수 HANJANG_ANDROID_SRC로 바꿀 수 있다(일부러 고친 사본에서 실패하는지 볼 때).
"""
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = Path(os.environ.get("HANJANG_ANDROID_SRC") or ROOT / "android/app/src/main/java")

# (파일 이름, 앞뒤 공백을 뺀 줄): 숫자만 넣는 push
NUMERIC_PUSH = {
    ("Annots.kt", "c?.forEach { arr.push(it) }"),  # FloatArray 색 값
    ("PageOps.kt", "d.push(pt[0]); d.push(pt[1]); d.push(if (dest.zoom.isNaN()) 0f else dest.zoom)"),
    ("PageOps.kt", "for (v in r) rect.push(v)"),  # FloatArray 사각형
    ("PageOps.kt", "repeat(3) { c.push(0.8f) }"),
}

# 이름을 백틱으로 감싸도(pdf.`addObject`(o)) 걸리게(8-5 판 1 MEDIUM)
ADD_OBJECT = r"(?<![\w$])`?addObject`?\s*\("
PUSH_CALL = r"(?<![\w$])`?push`?\s*\("


def _strip_code(text):
    """주석과 문자열·글자 리터럴 속을 빈칸으로 바꾼 글(줄 나눔·글자 자리는 그대로, 8-5에서 고침).

    예전에는 줄마다 // 뒤를 지워 문자열 속 //("http://…") 뒤의 코드까지 지웠다. 이제는 앞에서부터 한 글자씩 읽어
    "…"·\"\"\"…\"\"\"·'…' 안(문자열 템플릿 ${…} 안의 코드는 남김)과 //·/* */(중첩 포함) 주석만 지운다.
    """
    out = list(text)
    i, n = 0, len(text)
    # 쌓기: "code"(템플릿 ${ 안, 중괄호 깊이와 함께), "str", "raw"
    stack = [["code", 0]]
    block = 0

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        mode = stack[-1][0]
        c = text[i]
        if block:
            if text.startswith("/*", i):
                block += 1; blank(i, i + 2); i += 2; continue
            if text.startswith("*/", i):
                block -= 1; blank(i, i + 2); i += 2; continue
            blank(i, i + 1); i += 1; continue
        if mode == "code":
            if text.startswith("//", i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                blank(i, j); i = j; continue
            if text.startswith("/*", i):
                block = 1; blank(i, i + 2); i += 2; continue
            if text.startswith('"""', i):
                stack.append(["raw", 0]); i += 3; continue
            if c == '"':
                stack.append(["str", 0]); i += 1; continue
            if c == "'":
                j = i + 1
                while j < n and text[j] != "'" and text[j] != "\n":
                    j += 2 if text[j] == "\\" else 1
                blank(i + 1, min(j, n)); i = j + 1; continue
            if c == "{":
                stack[-1][1] += 1
            elif c == "}":
                if stack[-1][1] == 0 and len(stack) > 1:
                    stack.pop(); i += 1; continue  # 템플릿 ${…} 끝
                stack[-1][1] -= 1
            i += 1; continue
        # 문자열 안
        if mode == "str" and c == "\\":
            blank(i, i + 2); i += 2; continue
        if mode == "str" and c == '"':
            stack.pop(); i += 1; continue
        if mode == "raw" and text.startswith('"""', i):
            j = i + 3
            while j < n and text[j] == '"':
                j += 1  # \"\"\"\" 처럼 끝 따옴표가 더 붙은 경우
            stack.pop(); i = j; continue
        if text.startswith("${", i):
            stack.append(["code", 0]); i += 2; continue
        blank(i, i + 1); i += 1
    return "".join(out)


def _matches(path, pattern):
    """[pattern]이 걸린 (줄 번호, 그 줄)을 돌려준다. 주석·문자열을 지운 파일 전체에서 찾으므로 줄을 나눠 써도 걸린다."""
    code = _strip_code(path.read_text(encoding="utf-8"))
    lines = code.splitlines()
    out = []
    for mt in re.finditer(pattern, code):
        no = code.count("\n", 0, mt.start()) + 1
        out.append((no, lines[no - 1]))
    return out


def _sources():
    files = sorted(SRC.rglob("*.kt")) + sorted(SRC.rglob("*.java"))
    assert files, f"소스가 없습니다: {SRC}"
    return files


def test_no_add_object():
    bad = [f"{p.relative_to(SRC)}:{no}: {line.strip()}"
           for p in _sources() for no, line in _matches(p, ADD_OBJECT)]
    assert not bad, "fitz addObject는 참조를 내립니다. PageOps.addRef를 쓰세요:\n" + "\n".join(bad)


def test_no_method_reference():
    bad = [f"{p.relative_to(SRC)}:{no}: {line.strip()}"
           for p in _sources() for no, line in _matches(p, r"::\s*`?(addObject|push)\b")]
    assert not bad, "addObject·push 메서드 참조는 인자 형을 가릴 수 없어 쓰지 않습니다:\n" + "\n".join(bad)


def test_push_only_numbers():
    bad = []
    for p in _sources():
        # 점 없이 부르는 push(with·apply 안), 이름을 `push`로 감싼 것, 줄을 나눈 push( 도 잡는다(8-5)
        for no, line in _matches(p, PUSH_CALL):
            if (p.name, line.strip()) not in NUMERIC_PUSH:
                bad.append(f"{p.relative_to(SRC)}:{no}: {line.strip()}")
    assert not bad, ("PDFObject를 넘기는 push는 참조를 내립니다. append를 쓰거나, 숫자만 넣는 줄이면 "
                     "NUMERIC_PUSH에 더하세요:\n" + "\n".join(bad))


def test_patterns_catch_backticks(tmp_path):
    """8-5 판 1 MEDIUM: 이름을 백틱으로 감싼 호출(pdf.`addObject`(o), a.`push`(o))도 걸린다."""
    f = tmp_path / "X.kt"
    f.write_text("fun f() {\n    pdf.`addObject`(obj)\n    a.`push`(o)\n    pdf.addObject (o)\n}\n", encoding="utf-8")
    assert [no for no, _ in _matches(f, ADD_OBJECT)] == [2, 4]
    assert [no for no, _ in _matches(f, PUSH_CALL)] == [3]
