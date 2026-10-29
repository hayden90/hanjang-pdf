"""윈도우 실제 마우스 시험: 다른 프로그램에서 PDF를 끌어 왼쪽 쪽 목록의 쪽 사이에 놓기.

뷰어에 6쪽 PDF(P1~P6)를 열고, 다른 프로세스(drag_source.py)가 띄운 창에서 2쪽짜리 PDF(X1, X2)를
진짜 마우스로 끌어 쪽 목록의 3쪽 위쪽 가장자리(2쪽과 3쪽 사이)에 놓는다.
기대: 새 탭이 생기지 않고 같은 문서가 P1 P2 X1 X2 P3 … 순서가 된다.
쓰는 법: python ext_drop_test.py <결과 폴더> [explorer]
  explorer를 붙이면 끌기 출처로 진짜 윈도우 탐색기 창을 띄워 그 안의 insert.pdf를 끈다.
"""
import ctypes
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import pymupdf  # noqa: E402
from PySide6.QtCore import QPoint, QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from pdfviewer.ui.main_window import MainWindow  # noqa: E402
from pdfviewer.ui.thumbs import ThumbList  # noqa: E402

OUT = sys.argv[1]
USE_EXPLORER = len(sys.argv) > 2 and sys.argv[2] == "explorer"
os.makedirs(OUT, exist_ok=True)
user32 = ctypes.windll.user32
DOWN, UP = 0x0002, 0x0004
events = []


def make(path, labels):
    d = pymupdf.open()
    for s in labels:
        d.new_page().insert_text((120, 470), s, fontsize=200)
    d.save(path)
    d.close()


def drag(p0, p1, steps=50):
    user32.SetCursorPos(int(p0[0]), int(p0[1])); time.sleep(0.4)
    user32.mouse_event(DOWN, 0, 0, 0, 0); time.sleep(0.3)
    for i in range(1, steps + 1):
        user32.SetCursorPos(int(p0[0] + (p1[0] - p0[0]) * i / steps), int(p0[1] + (p1[1] - p0[1]) * i / steps))
        time.sleep(0.04)
    time.sleep(1.0)
    user32.mouse_event(UP, 0, 0, 0, 0)


def main():
    main_pdf = os.path.join(OUT, "main.pdf")
    ins_dir = os.path.join(OUT, "drag_from")
    os.makedirs(ins_dir, exist_ok=True)
    ins_pdf = os.path.join(ins_dir, "insert.pdf")
    make(main_pdf, [f"P{i}" for i in range(1, 7)])
    make(ins_pdf, ["X1", "X2"])

    # 어느 위젯이 끌기를 받았는지 적는다
    for name in ("dragEnterEvent", "dragMoveEvent", "dropEvent"):
        orig = getattr(ThumbList, name)

        def wrap(self, e, _o=orig, _n=name):
            if _n != "dragMoveEvent" or not any(x.startswith("thumbs.dragMove") for x in events):
                events.append(f"thumbs.{_n} urls={e.mimeData().hasUrls()} "
                              f"fmts={list(e.mimeData().formats())[:6]} act={int(e.proposedAction().value)} "
                              f"poss={int(e.possibleActions().value)}")
            _o(self, e)
            if _n != "dragMoveEvent":
                events.append(f"  -> accepted={e.isAccepted()}")
        setattr(ThumbList, name, wrap)
    orig_open = MainWindow.open_paths

    def open_paths(self, paths, *a, **k):
        events.append(f"MainWindow.open_paths {[os.path.basename(p) for p in paths]}")
        return orig_open(self, paths, *a, **k)
    MainWindow.open_paths = open_paths

    app = QApplication(sys.argv)
    w = MainWindow()
    w.setGeometry(60, 60, 1000, 850)
    w.show(); w.raise_(); w.activateWindow()
    w.open_path(main_pdf)
    result = {}
    done = threading.Event()
    poll = QTimer(interval=200)
    poll.timeout.connect(lambda: (poll.stop(), finish()) if done.is_set() else None)
    poll.start()
    r = w.devicePixelRatioF()
    sx, sy = 1200, 200  # 끌기 출처 창 위치(논리 점)
    here = os.path.dirname(os.path.abspath(__file__))
    ps = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(here, "explorer_item.ps1")]
    if USE_EXPLORER:
        subprocess.run(ps + [ins_dir, "x", "-Close"], capture_output=True, timeout=30)
        src_proc = subprocess.Popen(["explorer.exe", ins_dir])
    else:
        src_proc = subprocess.Popen([sys.executable, os.path.join(here, "drag_source.py"),
                                     ins_pdf, str(sx), str(sy)])

    def phys(widget, pt):
        g = widget.mapToGlobal(pt)
        return (g.x() * r, g.y() * r)

    def plan():
        th = w.tab().thumbs
        r2 = th.visualItemRect(th.item(2))
        dst = phys(th.viewport(), QPoint(r2.center().x(), r2.top() + 4))
        src = ((sx + 120) * r, (sy + 80) * r)
        if USE_EXPLORER:
            out = subprocess.run(ps + [ins_dir, "insert.pdf"],
                                 capture_output=True, text=True, timeout=60).stdout.strip().split()
            result["explorer_item"] = out
            if len(out) != 2 or not out[0].lstrip("-").isdigit():
                done.set()
                return
            src = (int(out[0]), int(out[1]))
        result["coords"] = {"src": src, "dst": dst, "dpr": r}
        threading.Thread(target=lambda: (drag(src, dst), time.sleep(2.0), done.set()), daemon=True).start()

    def finish():
        QApplication.primaryScreen().grabWindow(0).save(os.path.join(OUT, "after.png"))
        result["tabs"] = w.tabs.count()
        result["tab_names"] = [w.tabs.tabText(i) for i in range(w.tabs.count())]
        t = w.tabs.widget(0)
        result["order"] = [p.get_text().strip() for p in t.d.doc]
        result["events"] = events
        with open(os.path.join(OUT, "result.json"), "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        for i in range(w.tabs.count()):
            x = w.tabs.widget(i)
            if hasattr(x, "d"):
                x.d.modified = False
        src_proc.kill()
        if USE_EXPLORER:
            subprocess.run(ps + [ins_dir, "x", "-Close"], capture_output=True, timeout=30)
        app.quit()

    QTimer.singleShot(5000 if USE_EXPLORER else 3500, plan)
    QTimer.singleShot(3500 + 2500, lambda: QApplication.primaryScreen().grabWindow(0).save(
        os.path.join(OUT, "during_drag.png")))
    QTimer.singleShot(90000, lambda: (src_proc.kill(), app.quit()))
    app.exec()
    os._exit(0)


if __name__ == "__main__":
    main()
