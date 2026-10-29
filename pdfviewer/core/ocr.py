"""글자 인식(OCR): 글자가 그림·선으로만 된 쪽에 보이지 않는 글자층을 넣어 고르기·복사·찾기가 되게 한다.

세움터 건축물대장처럼 'Microsoft Print to PDF'로 저장한 문서는 글자가 곡선 그림이라 글자 정보가 없다.
쪽을 그림으로 그려 윈도우 내장 한국어 글자 인식(Windows.Media.Ocr)에 넘기고, 읽은 낱말마다 같은 자리·크기로
보이지 않는 글자(render_mode=3)를 넣는다. 인식은 PC 안에서만 하며 밖으로 보내지 않는다.

엔진은 (RGB 바이트, 폭, 높이) → [(x, y, w, h, 글자), ...](그림 픽셀 좌표)인 함수다. 시험에서는 가짜 엔진을 넘긴다.
좌표는 다른 모듈과 같이 '돌리기 전 쪽' 기준으로 주고받는다.
"""
from __future__ import annotations

from typing import Callable

import pymupdf

from .doc_ops import DocOpError

OCR_DPI = 300  # 7~9pt 글자에서 가장 정확했던 해상도(메인컴 합성 문서 98~99%)
MAX_PIXELS = 9000  # 윈도우 글자 인식이 받는 그림 한 변 한도(10000)보다 조금 작게
FONT = "korea"  # 보이지 않는 글자의 글꼴(파일에 넣지 않는 CJK 기본 글꼴이라 크기가 거의 늘지 않음)
_ASC, _DESC = 1.0, 0.2  # MuPDF가 이 글꼴 낱말 칸을 잡는 위·아래 비율(기준선에서 글자 크기 배수)

Engine = Callable[[bytes, int, int], list]


def needs_ocr(page: pymupdf.Page) -> bool:
    """글자 정보가 하나도 없는 쪽인가(이미 글자가 있는 쪽은 인식하지 않는다)."""
    return not page.get_text("text").strip()


def windows_engine() -> tuple[Engine | None, str]:
    """윈도우 내장 한국어 글자 인식. 못 쓰면 (None, 이유)."""
    try:
        import asyncio

        from winrt.windows.globalization import Language
        from winrt.windows.graphics.imaging import BitmapAlphaMode, BitmapPixelFormat, SoftwareBitmap
        from winrt.windows.media.ocr import OcrEngine
        from winrt.windows.storage.streams import DataWriter
    except Exception:
        return None, "글자 인식은 윈도우에서만 쓸 수 있습니다."
    try:
        engine = OcrEngine.try_create_from_language(Language("ko"))
    except Exception:
        engine = None
    if engine is None:
        return None, ("윈도우에 한국어 글자 인식이 깔려 있지 않습니다. "
                      "설정 > 시간 및 언어 > 언어에서 한국어의 '광학 문자 인식'을 설치해 주세요.")

    def run(rgb: bytes, w: int, h: int) -> list:
        bgra = bytearray(w * h * 4)
        bgra[0::4], bgra[1::4], bgra[2::4] = rgb[2::3], rgb[1::3], rgb[0::3]
        bgra[3::4] = b"\xff" * (w * h)  # 불투명 흰 바탕(투명이면 검은 글자가 안 읽힘)
        writer = DataWriter()
        writer.write_bytes(bytes(bgra))
        bmp = SoftwareBitmap(BitmapPixelFormat.BGRA8, w, h, BitmapAlphaMode.PREMULTIPLIED)
        bmp.copy_from_buffer(writer.detach_buffer())

        async def go():
            return await engine.recognize_async(bmp)

        result = asyncio.run(go())
        out = []
        for line in result.lines:
            for word in line.words:
                r = word.bounding_rect
                out.append((r.x, r.y, r.width, r.height, word.text))
        return out

    return run, ""


def recognize_page(page: pymupdf.Page, engine: Engine, dpi: int = OCR_DPI) -> list[tuple[pymupdf.Rect, str]]:
    """쪽을 그림으로 그려 읽고, 낱말마다 (돌리기 전 쪽 좌표 칸, 글자)를 돌려준다."""
    side = max(page.rect.width, page.rect.height)
    dpi = max(72, min(dpi, int(MAX_PIXELS * 72 / side)))
    # 화면에 보이는 방향(회전 적용) 그대로, 이 프로그램이 넣은 주석은 빼고 그린다
    pix = page.get_pixmap(dpi=dpi, alpha=False, annots=False, colorspace=pymupdf.csRGB)
    s = page.rect.width / pix.width  # 그림 픽셀 → 화면(돌린 쪽) pt
    to_page = page.derotation_matrix
    words = []
    for x, y, w, h, text in engine(bytes(pix.samples), pix.width, pix.height):
        text = (text or "").strip()
        if not text or w <= 0 or h <= 0:
            continue
        shown = pymupdf.Rect(x * s, y * s, (x + w) * s, (y + h) * s)
        words.append((shown * to_page, text))
    return words


def add_text_layer(doc: pymupdf.Document, pages: dict) -> int:
    """{쪽 번호: [(돌리기 전 칸, 글자)...]}를 보이지 않는 글자로 넣고, 넣은 낱말 수를 돌려준다.

    낱말 칸 높이에 맞춰 글자 크기를, 폭에 맞춰 가로 배율을 정해 MuPDF가 읽는 낱말 칸이 인식한 칸과 겹치게 한다.
    """
    count = 0
    for pno, words in pages.items():
        if not 0 <= pno < doc.page_count:
            raise DocOpError("쪽 번호가 맞지 않습니다.")
        page = doc[pno]
        rot = page.rotation
        for rect, text in words:
            shown = pymupdf.Rect(rect) * page.rotation_matrix
            size = shown.height / (_ASC + _DESC)
            if size < 1 or shown.width <= 0:
                continue
            natural = pymupdf.get_text_length(text, fontname=FONT, fontsize=size)
            if natural <= 0:
                continue
            base = pymupdf.Point(shown.x0, shown.y1 - _DESC * size) * page.derotation_matrix
            # 가로 배율은 돌리기 전 쪽 축에 걸리므로, 옆으로 돌린 쪽(90·270)은 세로 축에 건다
            k = shown.width / natural
            scale = pymupdf.Matrix(1, k) if rot % 180 else pymupdf.Matrix(k, 1)
            # 끝에 띄어쓰기를 붙여야 MuPDF가 옆 낱말과 한 낱말로 붙여 읽지 않는다(배율은 띄어쓰기 앞까지로 잼)
            page.insert_text(base, text + " ", fontsize=size, fontname=FONT, render_mode=3, rotate=rot,
                             morph=(base, scale))
            count += 1
    return count
