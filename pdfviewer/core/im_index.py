"""매각·브리핑자료 폴더 목록: 건물 폴더마다 PDF를 모으고 최신 rev를 맨 위에 둔다.

자료 폴더는 사용자가 한 번 고르면 설정에 남는다. 자동 찾기 후보(드라이브 안 상대 경로)는 기본으로 두지 않는다
(공개 배포판에 회사 폴더 구조를 넣지 않기 위해, 2026-10-29).
"""
from __future__ import annotations

import os
import re
import string
from ..i18n import tr

REV_RE = re.compile(r"rev\s*(\d+)", re.IGNORECASE)
# 드라이브 안에서 자료 폴더가 있을 만한 자리(앞에 있는 것이 먼저)
SUBPATHS: list[tuple[str, ...]] = []
DRIVE_NAMES = ("내 드라이브", "My Drive")


def rev_of(name: str) -> int | None:
    """파일 이름의 rev 번호(여럿이면 마지막). 없으면 None."""
    found = REV_RE.findall(name)
    return int(found[-1]) if found else None


def drive_roots() -> list[str]:
    """이 컴퓨터에서 구글드라이브 '내 드라이브'가 있을 만한 폴더들."""
    roots = []
    if os.name == "nt":
        for letter in string.ascii_uppercase[3:]:  # D: 이후(구글드라이브 데스크톱은 보통 G:)
            for dn in DRIVE_NAMES:
                roots.append(f"{letter}:\\{dn}")
    home = os.path.expanduser("~")
    cloud = os.path.join(home, "Library", "CloudStorage")
    if os.path.isdir(cloud):  # 맥(개발·시험용)
        for d in sorted(os.listdir(cloud)):
            if d.startswith("GoogleDrive"):
                for dn in DRIVE_NAMES:
                    roots.append(os.path.join(cloud, d, dn))
    for dn in DRIVE_NAMES:
        roots.append(os.path.join(home, "Google Drive", dn))
    return roots


def find_root(roots: list[str] | None = None, subpaths: list[tuple[str, ...]] | None = None) -> str | None:
    for r in roots if roots is not None else drive_roots():
        for sub in SUBPATHS if subpaths is None else subpaths:
            p = os.path.join(r, *sub)
            if os.path.isdir(p):
                return p
    return None


def _doc_key(d: dict):
    # 최신 rev → rev 큰 순 → rev 없는 파일은 이름순
    return (not d["latest"], d["rev"] is None, -(d["rev"] or 0), d["name"].lower())


def scan(root: str) -> list[dict]:
    """[{name, path, docs:[{name, path, rev, latest}]}] - 건물 폴더(바로 아래 폴더)마다 PDF 목록.

    루트에 바로 있는 PDF는 '(폴더 밖)' 묶음으로. 숨김·임시 파일은 뺀다.
    """
    groups = []
    try:
        entries = sorted(os.scandir(root), key=lambda e: e.name.lower())
    except OSError:
        return []
    loose = []
    for e in entries:
        if e.name.startswith((".", "~$")):
            continue
        if e.is_dir():
            docs = _pdfs(e.path)
            if docs:
                groups.append({"name": e.name, "path": e.path, "docs": docs})
        elif e.name.lower().endswith(".pdf"):
            loose.append(e)
    if loose:
        groups.append({"name": tr("(폴더 밖)"), "path": root, "docs": _pdfs(root, only=loose)})
    return groups


def _pdfs(folder: str, only=None) -> list[dict]:
    try:
        items = only if only is not None else list(os.scandir(folder))
    except OSError:
        return []
    docs = [{"name": e.name, "path": e.path, "rev": rev_of(e.name), "latest": False}
            for e in items
            if e.is_file() and e.name.lower().endswith(".pdf") and not e.name.startswith((".", "~$"))]
    revs = [d["rev"] for d in docs if d["rev"] is not None]
    if revs:
        top = max(revs)
        for d in docs:
            d["latest"] = d["rev"] == top
    return sorted(docs, key=_doc_key)


def previous_rev(docs: list[dict], doc: dict) -> dict | None:
    """같은 폴더에서 doc 바로 앞 rev 파일(비교용). 없으면 None."""
    if doc.get("rev") is None:
        return None
    older = [d for d in docs if d["rev"] is not None and d["rev"] < doc["rev"]]
    return max(older, key=lambda d: d["rev"]) if older else None
