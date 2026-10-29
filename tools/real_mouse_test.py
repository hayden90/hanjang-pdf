"""윈도우 실제 마우스 시험(화면이 켜진 로그인 세션에서만 돈다).

뷰어 창을 띄우고 윈도우 SendInput으로 진짜 마우스를 움직여
0) '형광펜' 단추를 누르고 본문 1쪽의 'P1' 글자 위를 끌어 칠한 뒤 '보기' 단추로 돌아오고
1) 왼쪽 쪽 목록에서 1쪽을 끌어 4쪽 앞에 놓고
2) 도구 막대의 '↻ 시계 90°', '↺ 반시계 90°' 단추를 누른 뒤
문서의 실제 쪽 순서·회전을 결과 파일(JSON)에 적는다. 끄는 도중 화면도 한 장 찍는다.

쓰는 법: python real_mouse_test.py <결과 폴더>
"""
import ctypes
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import pymupdf  # noqa: E402
from PySide6.QtCore import QPoint, QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication, QToolBar, QToolButton  # noqa: E402

from pdfviewer.ui.main_window import MainWindow  # noqa: E402

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
user32 = ctypes.windll.user32
DOWN, UP = 0x0002, 0x0004


def mouse_to(x, y):
    user32.SetCursorPos(int(x), int(y))


def drag(p0, p1, steps=40):
    mouse_to(*p0); time.sleep(0.3)
    user32.mouse_event(DOWN, 0, 0, 0, 0); time.sleep(0.2)
    for i in range(1, steps + 1):  # 사람처럼 천천히 움직여야 끌기가 시작됨
        mouse_to(p0[0] + (p1[0] - p0[0]) * i / steps, p0[1] + (p1[1] - p0[1]) * i / steps)
        time.sleep(0.03)
    time.sleep(0.6)
    user32.mouse_event(UP, 0, 0, 0, 0)


def click(p):
    mouse_to(*p); time.sleep(0.2)
    user32.mouse_event(DOWN, 0, 0, 0, 0); time.sleep(0.05)
    user32.mouse_event(UP, 0, 0, 0, 0); time.sleep(0.4)


def main():
    pdf = os.path.join(OUT, "drag_test.pdf")
    d = pymupdf.open()
    for i in range(1, 7):
        d.new_page().insert_text((120, 470), f"P{i}", fontsize=200)
    d.save(pdf)
    d.close()

    app = QApplication(sys.argv)
    w = MainWindow()
    w.resize(1100, 900)
    w.show(); w.raise_(); w.activateWindow()
    w.open_path(pdf)
    result = {"steps": []}
    done = threading.Event()
    poll = QTimer(interval=200)
    poll.timeout.connect(lambda: (poll.stop(), finish()) if done.is_set() else None)
    poll.start()

    def phys(widget, pt):
        g = widget.mapToGlobal(pt)
        r = widget.devicePixelRatioF()  # SetCursorPos는 실제 화면 점 단위
        return (g.x() * r, g.y() * r)

    def plan():
        t = w.tab()
        th = t.thumbs
        th.clearSelection()
        r0 = th.visualItemRect(th.item(0))
        r3 = th.visualItemRect(th.item(3))
        src = phys(th.viewport(), r0.center())
        dst = phys(th.viewport(), QPoint(r3.center().x(), r3.top() + 4))
        bars = w.findChildren(QToolBar)
        btn = {}
        for b in bars:
            for a in (w.a_rot_r, w.a_rot_l, w.a_mode["highlight"], w.a_mode["view"]):
                wb = b.widgetForAction(a)
                if isinstance(wb, QToolButton):
                    btn[a.text()] = phys(wb, wb.rect().center())
        page = t.d.doc[0]
        (r,) = page.search_for("P1")
        r = r * page.rotation_matrix
        z = t.view.zoom
        pw = t.view.pages[0]
        hl = (phys(pw, QPoint(int(r.x0 * z) - 4, int(r.y0 * z) - 4)),
              phys(pw, QPoint(int(r.x1 * z) + 4, int(r.y1 * z) + 4)))
        result["coords"] = {"src": src, "dst": dst, "buttons": btn, "hl": hl}
        threading.Thread(target=run, args=(src, dst, btn, hl), daemon=True).start()

    def shot(name):
        QApplication.primaryScreen().grabWindow(0).save(os.path.join(OUT, name))

    def run(src, dst, btn, hl):
        click(btn["형광펜"])
        drag(hl[0], hl[1], steps=20)
        result["steps"].append("highlight")
        click(btn["보기"])
        time.sleep(0.5)
        drag(src, dst)
        result["steps"].append("drag")
        time.sleep(1.0)
        click(btn["↻ 시계 90°"]); result["steps"].append("rot_r")
        click(btn["↺ 반시계 90°"]); click(btn["↺ 반시계 90°"]); result["steps"].append("rot_l2")
        time.sleep(0.8)
        done.set()  # 다른 스레드에서는 Qt 타이머를 걸 수 없으니 깃발만 세우고 화면 스레드가 확인한다

    def finish():
        t = w.tab()
        result["order"] = [p.get_text().strip() for p in t.d.doc]
        result["annots"] = {p.get_text().strip(): [a.type[1] for a in p.annots()] for p in t.d.doc}
        result["rotation"] = [p.rotation for p in t.d.doc]
        result["selected"] = t.thumbs.selected_pages()
        th = t.thumbs  # 목록이 흐트러지지 않았는지: 칸 위치가 위에서 아래로 겹치지 않고 늘어서야 함
        tops = [th.visualItemRect(th.item(i)).top() for i in range(th.count())]
        result["thumb_tops_increasing"] = all(b > a for a, b in zip(tops, tops[1:]))
        result["thumb_heights"] = sorted({th.visualItemRect(th.item(i)).height() for i in range(th.count())})
        shot("after.png")
        with open(os.path.join(OUT, "result.json"), "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False)
        t.d.modified = False
        app.quit()

    QTimer.singleShot(2500, plan)
    # 끄는 도중 화면(끌리는 쪽 그림·주황 줄 확인용). 끌기 중에도 Qt 타이머는 돈다
    QTimer.singleShot(2500 + 2200, lambda: shot("during_drag.png"))
    QTimer.singleShot(40000, lambda: (shot("timeout.png"), app.quit()))
    app.exec()
    os._exit(0)  # 마우스 스레드가 남아 있어도 프로세스를 확실히 끝낸다(예약 작업이 '실행 중'에 묶이지 않게)


if __name__ == "__main__":
    main()
