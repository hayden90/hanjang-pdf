"""안드로이드판 CJK 대체 글꼴 뽑기.

PC판은 PyMuPDF에 들어 있는 CJK 글꼴(pymupdf.Font("cjk") = "Droid Sans Fallback Regular",
TrueType, Apache License 2.0)로 한글을 그린다. 안드로이드 fitz는 이 글꼴을 갖고 있지 않아 폰의
/system/fonts CJK 글꼴(OTF 수 MB~19MB)을 통째로 넣으므로, 같은 바이트를 앱 assets에 넣어
fitz 글꼴 로더(core/CjkFont.kt)가 돌려주게 한다.

뽑은 바이트의 sha256은 아래 EXPECT_SHA256과 core/CjkFont.kt의 SHA256에 같이 적는다. PyMuPDF 판을
올려 값이 바뀌면 이 도구가 멈추므로, 두 곳을 함께 고치고 호환 시험을 다시 돌린다.

쓰는 법: .venv/bin/python tools/gen_android_cjk_font.py [출력 경로]
"""
from __future__ import annotations

import hashlib
import os
import sys

import pymupdf

OUT = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "assets",
                   "droid_sans_fallback.ttf")
EXPECT_NAME = "Droid Sans Fallback Regular"
EXPECT_SIZE = 3556308
EXPECT_SHA256 = "ee38813ea00c3e32add4268fff7fff9e39417b4913cb13be2415164a47807cc2"


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else OUT
    font = pymupdf.Font("cjk")
    data = bytes(font.buffer)
    sha = hashlib.sha256(data).hexdigest()
    print(f"PyMuPDF {pymupdf.VersionBind} / MuPDF {pymupdf.VersionFitz}")
    print(f"글꼴 {font.name}, {len(data)} 바이트, sha256 {sha}")
    if (font.name, len(data), sha) != (EXPECT_NAME, EXPECT_SIZE, EXPECT_SHA256):
        sys.exit("기록된 값과 다릅니다. PyMuPDF 판이 바뀌었으면 EXPECT_*와 CjkFont.kt를 같이 고치세요.")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "wb") as f:
        f.write(data)
    print(f"썼음: {os.path.abspath(out)}")


if __name__ == "__main__":
    main()
