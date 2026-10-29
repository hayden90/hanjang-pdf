"""한장PDF 시작점. 명령줄로 받은 PDF(윈도우 '연결 프로그램')를 바로 연다."""
import os
import sys

from PySide6.QtCore import QTimer
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication

from pdfviewer.ui.main_window import APP_NAME, MainWindow


def icon_path() -> str:
    """앱 아이콘 파일 위치(PyInstaller로 묶으면 _MEIPASS 아래 assets/)."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, "assets", "hanjang.png")


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    if os.path.exists(icon_path()):
        app.setWindowIcon(QIcon(icon_path()))
    w = MainWindow()
    w.show()
    w.open_paths([a for a in sys.argv[1:] if a.lower().endswith(".pdf")])
    selftest = os.environ.get("PDFV_SELFTEST")
    if selftest:  # 빌드 확인용: 창을 띄워 문서를 연 뒤 쪽 수를 적고 스스로 끝냄
        def report():
            t = w.tab()
            with open(selftest, "w", encoding="utf-8") as f:
                f.write(f"ok pages={t.d.page_count if t else 0} tabs={w.tabs.count()} icon={not app.windowIcon().isNull()}\n")
                if os.environ.get("PDFV_SELFTEST_OCR") and t is not None:  # 묶음 안에서 글자 인식 엔진이 불러지는지
                    from pdfviewer.core import ocr
                    engine, why = ocr.windows_engine()
                    words = ocr.recognize_page(t.d.doc[0], engine) if engine else []
                    f.write(f"ocr engine={engine is not None} words={len(words)}\n")
            app.quit()
        QTimer.singleShot(1500, report)
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
