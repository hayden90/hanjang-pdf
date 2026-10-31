"""검토 판 1 지적 1·3 재현용 시험 입력(android/app/src/androidTest/assets/fixnames_case.pdf)을 만든다.

쓰는 법(저장소 루트): .venv/bin/python -m tests.android_compat.make_fixnames_case

한 쪽의 Resources /Font에 네 글꼴을 둔다. 이름은 모두 안드로이드 앱 경로 이름(BAD)으로 맞춘다.
  FA  간접 Type0, 남의 글꼴(Courier = Nimbus Mono 임베드)      → 바뀌면 안 됨
  FB  직접 사전, 남의 단순 글꼴(Type1, 글꼴 파일 없음)           → 바뀌면 안 됨
  FC  간접 Type0, Droid Sans Fallback 임베드(앱 글꼴과 같은 바이트) → 바뀌어야 함
  FD  직접 사전 사슬(Type0→직접 배열→직접 CIDFont→직접 FontDescriptor) + FC와 같은 FontFile2 → 바뀌어야 함
  FE  FD와 같은 직접 사슬이지만 FontFile2가 Droid 바이트에서 한 바이트만 바꾼 스트림(크기 같음·sha256 다름) → 바뀌면 안 됨
계측 시험(CompatTest.g_fixNames)은 앱이 실제로 얻은 이름이 BAD와 다르면 문서 안에서 BAD를 그 이름으로 바꿔 쓴다.
"""
from __future__ import annotations

import os

import pymupdf

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "android", "app", "src", "androidTest", "assets", "fixnames_case.pdf")
BAD = "/data/user/0/com.bubigter.hanja"  # fitz Java Font(경로)가 이름으로 삼는 앞 31자
BAD_PDF = "/" + BAD.replace("/", "#2F")


def font_xref(doc: pymupdf.Document, page: pymupdf.Page, name: str) -> int:
    for f in page.get_fonts(full=True):
        if f[4] == name:
            return f[0]
    raise SystemExit(f"글꼴 {name} 없음")


def chain(doc: pymupdf.Document, type0: int) -> tuple[int, int]:
    """Type0 → (CIDFont xref, FontDescriptor xref)."""
    desc = doc.xref_get_key(type0, "DescendantFonts")[1]  # "[n 0 R]"
    cid = int(desc.strip("[]").split()[0])
    fd = int(doc.xref_get_key(cid, "FontDescriptor")[1].split()[0])
    return cid, fd


def main() -> None:
    doc = pymupdf.open()
    page = doc.new_page(width=300, height=200)
    page.insert_font(fontname="FA", fontbuffer=pymupdf.Font("cour").buffer)
    page.insert_font(fontname="FC", fontbuffer=pymupdf.Font("cjk").buffer)
    page.insert_text((20, 50), "Courier", fontname="FA", fontsize=12)
    page.insert_text((20, 90), "한글", fontname="FC", fontsize=12)
    fa, fc = font_xref(doc, page, "FA"), font_xref(doc, page, "FC")
    for x in (fa, fc):
        cid, fd = chain(doc, x)
        doc.xref_set_key(x, "BaseFont", BAD_PDF)
        doc.xref_set_key(cid, "BaseFont", BAD_PDF)
        doc.xref_set_key(fd, "FontName", BAD_PDF)
    cid_c, fd_c = chain(doc, fc)
    ff2 = doc.xref_get_key(fd_c, "FontFile2")[1]
    assert ff2.endswith(" R"), ff2
    # 쪽 Resources는 간접 객체이고 그 안 /Font는 직접 사전. 그 /Font 안에 직접 사전 둘을 넣는다.
    res = doc.xref_get_key(page.xref, "Resources")[1]
    assert res.endswith(" R"), res
    rx = int(res.split()[0])
    doc.xref_set_key(rx, "Font/FB",
                     f"<</Type/Font/Subtype/Type1/BaseFont{BAD_PDF}/Encoding/WinAnsiEncoding>>")
    def direct_chain(ff: str) -> str:
        return (f"<</Type/Font/Subtype/Type0/BaseFont{BAD_PDF}/Encoding/Identity-H/DescendantFonts["
                f"<</Type/Font/Subtype/CIDFontType2/BaseFont{BAD_PDF}"
                f"/CIDSystemInfo<</Registry(Adobe)/Ordering(Identity)/Supplement 0>>"
                f"/FontDescriptor<</Type/FontDescriptor/FontName{BAD_PDF}/Flags 4/FontBBox[0 0 1000 1000]"
                f"/ItalicAngle 0/Ascent 1000/Descent 0/CapHeight 1000/StemV 80/FontFile2 {ff}>>>>]>>")

    doc.xref_set_key(rx, "Font/FD", direct_chain(ff2))
    fake = bytearray(pymupdf.Font("cjk").buffer)
    fake[-1] ^= 0xFF
    fx = doc.get_new_xref()
    doc.update_object(fx, "<<>>")
    doc.update_stream(fx, bytes(fake))
    doc.xref_set_key(rx, "Font/FE", direct_chain(f"{fx} 0 R"))
    doc.save(OUT, garbage=1, deflate=True)
    doc.close()
    # 확인: 직접 사전이 그대로 직접으로 남았는지, 이름이 BAD인지
    d = pymupdf.open(OUT)
    p = d[0]
    rx2 = int(d.xref_get_key(p.xref, "Resources")[1].split()[0])
    print(d.xref_object(rx2, compressed=True))
    print(OUT, os.path.getsize(OUT), "bytes")
    d.close()


if __name__ == "__main__":
    main()
