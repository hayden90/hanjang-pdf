"""시험용 끌기 출처(다른 프로그램 역할): 창을 누르면 PDF 파일 하나를 OLE 끌기로 내보낸다.

탐색기처럼 다른 프로세스에서 파일(CF_HDROP)을 끌어오는 상황을 만든다.
쓰는 법: python drag_source.py <pdf 경로> <x> <y>
"""
import sys

from PySide6.QtCore import QMimeData, Qt, QUrl
from PySide6.QtGui import QDrag
from PySide6.QtWidgets import QApplication, QLabel

PATH, X, Y = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])


class Source(QLabel):
    def mousePressEvent(self, e):
        mime = QMimeData()
        mime.setUrls([QUrl.fromLocalFile(PATH)])
        drag = QDrag(self)
        drag.setMimeData(mime)
        # 탐색기처럼 이동·복사·바로가기를 다 허용하고 기본은 이동
        drag.exec(Qt.DropAction.CopyAction | Qt.DropAction.MoveAction | Qt.DropAction.LinkAction,
                  Qt.DropAction.MoveAction)


app = QApplication(sys.argv)
w = Source("끌기 출처: insert.pdf")
w.setAlignment(Qt.AlignmentFlag.AlignCenter)
w.setStyleSheet("background:#ffe066; font-size:16px;")
w.setWindowFlag(Qt.WindowType.WindowStaysOnTopHint)
w.setGeometry(X, Y, 240, 160)
w.show()
app.exec()
