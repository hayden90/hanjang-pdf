"""인쇄: 쪽마다 형광펜·메모·글자 상자까지 그린 그림을 종이에 맞춰 찍는다."""
from __future__ import annotations

import pymupdf
from PySide6.QtCore import QRectF
from PySide6.QtGui import QImage, QPainter, QPageLayout
from PySide6.QtPrintSupport import QAbstractPrintDialog, QPrintDialog, QPrinter
from ..i18n import tr

MAX_DPI = 300  # 이보다 곱게 그려도 종이에서는 차이가 없고 메모리만 든다


def print_pages(printer: QPrinter, doc: pymupdf.Document, pages: list[int]) -> int:
    """pages(0부터)를 차례로 찍고 찍은 쪽 수를 돌려준다. 쪽 비율은 지키고 종이 가운데에 놓는다."""
    if not pages:
        return 0
    dpi = max(72, min(MAX_DPI, printer.resolution()))
    painter = QPainter()
    if not painter.begin(printer):
        raise RuntimeError(tr("프린터를 열지 못했습니다."))
    try:
        for n, pno in enumerate(pages):
            if n:
                printer.newPage()
            pix = doc[pno].get_pixmap(dpi=dpi, alpha=False, annots=True)  # 주석(글자 상자 등)까지
            img = QImage(pix.samples, pix.width, pix.height, pix.stride, QImage.Format.Format_RGB888)
            area = QRectF(painter.viewport())
            scale = min(area.width() / img.width(), area.height() / img.height())
            w, h = img.width() * scale, img.height() * scale
            painter.drawImage(QRectF(area.x() + (area.width() - w) / 2, area.y() + (area.height() - h) / 2, w, h), img)
    finally:
        painter.end()
    return len(pages)


def ask_and_print(parent, doc: pymupdf.Document, current: int) -> int:
    """인쇄 창을 띄워 고른 범위를 찍는다. 취소하면 0."""
    printer = QPrinter(QPrinter.PrinterMode.HighResolution)
    first = doc[0].rect
    printer.setPageOrientation(QPageLayout.Orientation.Landscape if first.width > first.height
                               else QPageLayout.Orientation.Portrait)
    dlg = QPrintDialog(printer, parent)
    dlg.setWindowTitle(tr("인쇄"))
    dlg.setMinMax(1, doc.page_count)
    dlg.setOption(QAbstractPrintDialog.PrintDialogOption.PrintCurrentPage, True)
    if dlg.exec() != QPrintDialog.DialogCode.Accepted:
        return 0
    rng = printer.printRange()
    if rng == QPrinter.PrintRange.PageRange:
        pages = list(range(printer.fromPage() - 1, printer.toPage()))
    elif rng == QPrinter.PrintRange.CurrentPage:
        pages = [current]
    else:
        pages = list(range(doc.page_count))
    return print_pages(printer, doc, pages)
