"""안드로이드판 글자 폭 표 만들기.

PC판 annots._text_width()는 0x1100 이상은 PyMuPDF Font("cjk"), 그 아래는 Font("helv")로
한 글자씩 폭을 재어 더한다. 안드로이드는 같은 글꼴이 없으므로(시스템 글꼴을 씀) 이 값을 표로 넣어
줄 바꾸기·상자 크기를 PC와 글자 하나까지 같게 한다.

표 값은 MuPDF가 돌려주는 float32 advance(1pt 기준)를 그대로 적는다(repr로 적어 Kotlin
toDouble()이 같은 double을 얻음). 글리프가 없는 글자에서 MuPDF가 대체 글꼴로 잰 값도 그대로 적는다.

파일 형식(줄마다):
  # 머리말
  L <시작 16진> <끝 16진> <advance>   0x1100 미만 구간(끝 포함), 같은 값이 이어지는 구간
  C <시작 16진> <끝 16진> <advance>   0x1100 이상에서 1.0이 아닌 글자 구간(끝 포함, 나머지는 모두 1.0)

파일은 gzip으로 묶어 쓴다(약 150KB, mtime 0으로 고정해 같은 입력이면 같은 바이트).

쓰는 법: .venv/bin/python tools/gen_android_widths.py [출력 경로]
"""
from __future__ import annotations

import gzip
import hashlib
import os
import sys

import pymupdf

OUT = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "assets", "hs_widths.txt.gz")


def advance(font: pymupdf.Font, cp: int) -> float:
    """PC _text_width가 쓰는 값과 같은 1pt 폭(text_length(ch, fontsize=1))."""
    return font.text_length(chr(cp), fontsize=1)


def build() -> str:
    cjk, helv = pymupdf.Font("cjk"), pymupdf.Font("helv")
    lines = ["# 한장PDF 글자 폭 표(PC annots._text_width와 같은 값). tools/gen_android_widths.py가 만듦",
             f"# PyMuPDF {pymupdf.VersionBind} / MuPDF {pymupdf.VersionFitz}"]
    start, cur = 0, advance(helv, 0)
    for cp in range(1, 0x1100):
        a = advance(helv, cp)
        if a != cur:
            lines.append(f"L {start:x} {cp - 1:x} {cur!r}")
            start, cur = cp, a
    lines.append(f"L {start:x} {0x10ff:x} {cur!r}")
    start, cur = 0x1100, advance(cjk, 0x1100)
    for cp in range(0x1101, 0x110001):
        a = advance(cjk, cp) if cp < 0x110000 else None  # 끝 표시
        if a != cur:
            if cur != 1.0:
                lines.append(f"C {start:x} {cp - 1:x} {cur!r}")
            start, cur = cp, a
    return "\n".join(lines) + "\n"


def main() -> None:
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else OUT)
    data = build()
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as f:
        f.write(data.encode("utf-8"))
    n_l = sum(1 for ln in data.splitlines() if ln.startswith("L "))
    n_c = sum(1 for ln in data.splitlines() if ln.startswith("C "))
    print(f"{out}: {os.path.getsize(out)} bytes(gz), 원문 {len(data.encode())} bytes, L {n_l}줄, C {n_c}줄, sha256 {hashlib.sha256(data.encode()).hexdigest()[:16]}")


if __name__ == "__main__":
    main()
