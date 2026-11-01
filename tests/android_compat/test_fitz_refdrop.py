"""안드로이드 앱 코드에서 fitz Java의 참조를 내리는 호출을 막는 정적 검사(8-4 검토 판 2 지적 3).

fitz Java의 PDFDocument.addObject와 PDFObject.push(PDFObject)는 넘긴 껍데기의 참조를 내린다. 그 껍데기가
가비지 수거될 때 문서 안 객체가 한 번 더 풀려 끼워 넣기·내보내기 뒤 "cycle in page tree"나 앱 죽음이 난다
(8-4 판 1 고침). 대신 PageOps.addRef(createObject+writeObject)와 append(put 끝 번호)를 쓴다.

- addObject( 는 주석 밖에서 한 번도 쓰지 않는다. 메서드 참조(::addObject, ::push)도 쓰지 않는다(8-4 판 3 LOW).
- .push( 는 소스만으로 인자 형을 알 수 없어, 숫자를 넣는 것으로 확인한 줄만 허용 목록에 둔다.
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


def _code_lines(path):
    """주석을 뺀 (줄 번호, 줄) 목록. 블록 주석과 줄 끝 // 주석을 지운다(문자열 안의 //는 여기서 쓰지 않음)."""
    out = []
    in_block = False
    for no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw
        if in_block:
            end = line.find("*/")
            if end < 0:
                continue
            line = line[end + 2:]
            in_block = False
        while "/*" in line:
            start = line.find("/*")
            end = line.find("*/", start + 2)
            if end < 0:
                line = line[:start]
                in_block = True
                break
            line = line[:start] + line[end + 2:]
        line = re.sub(r"//.*", "", line)
        if line.strip():
            out.append((no, line))
    return out


def _sources():
    files = sorted(SRC.rglob("*.kt")) + sorted(SRC.rglob("*.java"))
    assert files, f"소스가 없습니다: {SRC}"
    return files


def test_no_add_object():
    bad = [f"{p.relative_to(SRC)}:{no}: {line.strip()}"
           for p in _sources() for no, line in _code_lines(p) if re.search(r"\baddObject\s*\(", line)]
    assert not bad, "fitz addObject는 참조를 내립니다. PageOps.addRef를 쓰세요:\n" + "\n".join(bad)


def test_no_method_reference():
    bad = [f"{p.relative_to(SRC)}:{no}: {line.strip()}"
           for p in _sources() for no, line in _code_lines(p) if re.search(r"::\s*(addObject|push)\b", line)]
    assert not bad, "addObject·push 메서드 참조는 인자 형을 가릴 수 없어 쓰지 않습니다:\n" + "\n".join(bad)


def test_push_only_numbers():
    bad = []
    for p in _sources():
        for no, line in _code_lines(p):
            if re.search(r"\.push\s*\(", line) and (p.name, line.strip()) not in NUMERIC_PUSH:
                bad.append(f"{p.relative_to(SRC)}:{no}: {line.strip()}")
    assert not bad, ("PDFObject를 넘기는 push는 참조를 내립니다. append를 쓰거나, 숫자만 넣는 줄이면 "
                     "NUMERIC_PUSH에 더하세요:\n" + "\n".join(bad))
