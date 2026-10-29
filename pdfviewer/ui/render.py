"""PDF 쪽을 Qt 그림으로 바꾼다."""
from __future__ import annotations

import pymupdf
from PySide6.QtGui import QImage, QPixmap


def page_pixmap(page: pymupdf.Page, scale: float) -> QPixmap:
    pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), alpha=False)
    img = QImage(pix.samples, pix.width, pix.height, pix.stride, QImage.Format.Format_RGB888)
    return QPixmap.fromImage(img.copy())  # copy: pix가 사라져도 그림이 남게
