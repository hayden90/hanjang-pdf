"""윈도우 실제 마우스 시험(빌드된 PDFViewer.exe 그대로): 탐색기에서 PDF를 끌어 쪽 목록 3쪽 위 가장자리에 놓는다.

쓰는 법: python exe_drop_test.py <PDFViewer.exe> <결과 폴더>
결과: 놓기 전·후 탭 수와 쪽 목록 항목 수(UI 자동화로 셈), 화면 사진.
"""
import ctypes
import json
import os
import subprocess
import sys
import time

import pymupdf

EXE, OUT = sys.argv[1], sys.argv[2]
HERE = os.path.dirname(os.path.abspath(__file__))
PS = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File"]
user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
DOWN, UP = 0x0002, 0x0004


def make(path, labels):
    d = pymupdf.open()
    for s in labels:
        d.new_page().insert_text((120, 470), s, fontsize=200)
    d.save(path)
    d.close()


def ps(script, *args):
    return subprocess.run(PS + [os.path.join(HERE, script), *args], capture_output=True, text=True,
                          timeout=90).stdout.strip()


def drag(p0, p1, steps=50):
    user32.SetCursorPos(int(p0[0]), int(p0[1])); time.sleep(0.4)
    user32.mouse_event(DOWN, 0, 0, 0, 0); time.sleep(0.3)
    for i in range(1, steps + 1):
        user32.SetCursorPos(int(p0[0] + (p1[0] - p0[0]) * i / steps), int(p0[1] + (p1[1] - p0[1]) * i / steps))
        time.sleep(0.04)
    time.sleep(1.0)
    user32.mouse_event(UP, 0, 0, 0, 0)


def main():
    os.makedirs(OUT, exist_ok=True)
    main_pdf = os.path.join(OUT, "main.pdf")
    ins_dir = os.path.join(OUT, "drag_from")
    os.makedirs(ins_dir, exist_ok=True)
    make(main_pdf, [f"P{i}" for i in range(1, 7)])
    make(os.path.join(ins_dir, "insert.pdf"), ["X1", "X2"])
    res = {}
    ps("explorer_item.ps1", ins_dir, "x", "-Close")
    viewer = subprocess.Popen([EXE, main_pdf])
    time.sleep(6)
    res["tabs_before"] = ps("uia_rect.ps1", "PDF", "TabItem", "", "-Count")
    res["list_before"] = ps("uia_rect.ps1", "PDF", "ListItem", "", "-Count")
    item3 = ps("uia_rect.ps1", "PDF", "ListItem", "3").split()
    res["item3"] = item3
    subprocess.Popen(["explorer.exe", ins_dir])
    src = ps("explorer_item.ps1", ins_dir, "insert.pdf").split()
    res["explorer_item"] = src
    if len(item3) == 4 and len(src) == 2 and item3[0].lstrip("-").isdigit():
        x, y, w, h = map(int, item3)
        drag((int(src[0]), int(src[1])), (x + w // 2, y + 4))
        time.sleep(2.5)
        res["tabs_after_thumbs"] = ps("uia_rect.ps1", "PDF", "TabItem", "", "-Count")
        # 오른쪽 큰 쪽 화면에도 놓아 본다(rev5부터 새 탭 대신 그 자리에 넣기)
        res["view_target"] = (x + w + 400, y + 100)
        drag((int(src[0]), int(src[1])), res["view_target"])
        time.sleep(2.5)
    res["tabs_after"] = ps("uia_rect.ps1", "PDF", "TabItem", "", "-Count")
    # Ctrl+S(rev5 '저장': 지금 이름에 바로 저장)로 저장하고 파일의 쪽 순서를 읽는다
    if len(item3) == 4 and item3[0].lstrip("-").isdigit():
        x, y, w, h = map(int, item3)
        user32.SetCursorPos(x + w // 2, y + h // 2); time.sleep(0.2)
        user32.mouse_event(DOWN, 0, 0, 0, 0); time.sleep(0.05); user32.mouse_event(UP, 0, 0, 0, 0)
        time.sleep(0.5)
        VK_CONTROL, VK_S, KEYUP = 0x11, 0x53, 0x0002
        user32.keybd_event(VK_CONTROL, 0, 0, 0); user32.keybd_event(VK_S, 0, 0, 0)
        user32.keybd_event(VK_S, 0, KEYUP, 0); user32.keybd_event(VK_CONTROL, 0, KEYUP, 0)
        time.sleep(2.0)
        with pymupdf.open(main_pdf) as d:
            res["saved_order"] = [p.get_text().strip() for p in d]
    res["list_after"] = ps("uia_rect.ps1", "PDF", "ListItem", "", "-Count")
    res["tab_names"] = ps("uia_rect.ps1", "PDF", "TabItem", "?")
    with open(os.path.join(OUT, "result.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    ps("explorer_item.ps1", ins_dir, "x", "-Close")
    viewer.kill()


if __name__ == "__main__":
    main()
