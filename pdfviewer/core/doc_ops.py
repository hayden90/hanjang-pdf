"""쪽 편집 기능: 화면과 떼어 놓아 시험할 수 있게 한다.

모든 함수는 열린 pymupdf.Document를 받아 제자리에서 바꾼다.
되돌리기용 사본은 Document 감싸개(document.py)가 맡는다.
"""
from __future__ import annotations

import os
import tempfile

import pymupdf


class DocOpError(Exception):
    """사용자에게 그대로 보여 줄 수 있는 편집 오류."""


def _check_indices(doc: pymupdf.Document, indices) -> list[int]:
    picked = sorted(set(indices))
    if not picked:
        raise DocOpError("고른 쪽이 없습니다.")
    for i in picked:
        if not 0 <= i < doc.page_count:
            raise DocOpError(f"없는 쪽 번호입니다: {i + 1}")
    return picked


def open_pdf_bytes(data: bytes, password: str | None = None) -> pymupdf.Document:
    """파일 내용을 메모리에서 연다(원본 파일을 잠그지 않아 덮어쓰기·드라이브 동기화가 막히지 않음)."""
    try:
        doc = pymupdf.open(stream=data, filetype="pdf")
    except Exception as e:  # 깨진 파일 등
        raise DocOpError(f"PDF를 열 수 없습니다: {e}") from e
    if doc.needs_pass:
        if not password or not doc.authenticate(password):
            doc.close()
            raise DocOpError("암호가 필요하거나 암호가 맞지 않습니다.")
    return doc


def atomic_save(doc: pymupdf.Document, out_path: str) -> None:
    """같은 폴더 임시 파일에 쓴 뒤 바꿔 끼운다. 실패하면 기존 파일은 그대로 두고 DocOpError를 낸다."""
    out_path = os.path.abspath(out_path)
    tmp = None
    try:
        fd, tmp = tempfile.mkstemp(suffix=".pdf.tmp", dir=os.path.dirname(out_path))
        os.close(fd)
        # 열린 문서를 바로 garbage 저장하면 그 문서 안의 객체 번호가 바뀌는데, MuPDF는 글자 상자에 쓴
        # 글꼴의 옛 번호를 기억하고 있어 저장 뒤 새로 넣은 글자 상자가 깨진 글자로 그려진다.
        # 그래서 사본을 만들어 사본만 정리해 저장한다(열린 문서는 그대로).
        copy = pymupdf.open("pdf", doc.tobytes())
        try:
            copy.save(tmp, garbage=3, deflate=True)
        finally:
            copy.close()
        os.replace(tmp, out_path)
    except Exception as e:
        if tmp and os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass
        raise DocOpError(f"저장하지 못했습니다: {e}") from e


def needs_password(path: str) -> bool:
    try:
        with pymupdf.open(path) as d:
            return bool(d.needs_pass)
    except Exception:
        return False  # 깨진 파일은 넣을 때 오류로 알린다


def insert_pdf(doc: pymupdf.Document, src_path: str, at: int, password: str | None = None) -> int:
    """src_path의 모든 쪽을 at 위치(그 쪽 앞)에 넣고, 넣은 쪽 수를 돌려준다."""
    if not 0 <= at <= doc.page_count:
        raise DocOpError(f"넣을 위치가 잘못되었습니다: {at + 1}")
    with open(src_path, "rb") as f:
        src = open_pdf_bytes(f.read(), password)
    try:
        n = src.page_count
        if n == 0:
            raise DocOpError("넣을 PDF에 쪽이 없습니다.")
        doc.insert_pdf(src, start_at=at)
        return n
    finally:
        src.close()


def export_pages(doc: pymupdf.Document, indices, out_path: str) -> int:
    """고른 쪽을 문서 순서대로 새 PDF로 저장하고, 저장한 쪽 수를 돌려준다."""
    picked = _check_indices(doc, indices)
    out = pymupdf.open()
    try:
        for i in picked:
            out.insert_pdf(doc, from_page=i, to_page=i)
        atomic_save(out, out_path)
        return len(picked)
    finally:
        out.close()


def delete_pages(doc: pymupdf.Document, indices) -> int:
    picked = _check_indices(doc, indices)
    if len(picked) >= doc.page_count:
        raise DocOpError("모든 쪽을 지울 수는 없습니다. 한 쪽 이상 남겨 주세요.")
    doc.delete_pages(picked)
    return len(picked)


def rotate_pages(doc: pymupdf.Document, indices, degrees: int) -> None:
    if degrees % 90:
        raise DocOpError("회전은 90도 단위만 됩니다.")
    for i in _check_indices(doc, indices):
        page = doc[i]
        page.set_rotation((page.rotation + degrees) % 360)


def move_pages(doc: pymupdf.Document, indices, to: int) -> int:
    """고른 쪽 묶음을 원래 번호 기준 to 쪽 앞으로 옮긴다(to=쪽 수면 맨 끝).

    옮긴 묶음의 첫 쪽이 새로 놓인 번호(0부터)를 돌려준다.
    """
    order, pos = move_order(doc.page_count, _check_indices(doc, indices), to)
    # doc.select()는 쪽 순서는 맞추지만 문서의 입력 서식(AcroForm) 연결을 잃는다.
    # move_page는 서식을 지키므로, 앞자리부터 하나씩 제자리로 옮겨 같은 순서를 만든다.
    cur = list(range(doc.page_count))
    for i, want in enumerate(order):
        j = cur.index(want)
        if j != i:  # j > i: 앞자리는 이미 맞춰 둠
            doc.move_page(j, i)
            cur.insert(i, cur.pop(j))
    return pos


def move_order(count: int, picked: list[int], to: int) -> tuple[list[int], int]:
    """옮긴 뒤의 쪽 순서와, 옮긴 묶음의 첫 쪽이 놓일 번호를 돌려준다."""
    if not 0 <= to <= count:
        raise DocOpError(f"옮길 위치가 잘못되었습니다: {to + 1}")
    rest = [i for i in range(count) if i not in set(picked)]
    pos = sum(1 for i in rest if i < to)
    return rest[:pos] + picked + rest[pos:], pos


def is_noop_move(count: int, indices, to: int) -> bool:
    picked = sorted(set(indices))
    if not picked or not 0 <= to <= count:
        return False
    return move_order(count, picked, to)[0] == list(range(count))


def search(doc: pymupdf.Document, text: str) -> list[tuple[int, pymupdf.Rect]]:
    """찾은 곳을 (쪽 번호, 화면에 보이는 방향의 사각형) 목록으로 돌려준다."""
    text = text.strip()
    if not text:
        return []
    hits = []
    for page in doc:
        mat = page.rotation_matrix
        for r in page.search_for(text):
            hits.append((page.number, r * mat))
    return hits
