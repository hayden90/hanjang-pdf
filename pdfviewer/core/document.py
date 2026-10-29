"""열린 PDF 하나: 편집 전 사본을 쌓아 되돌리기·다시 하기를 하고, 안전하게 저장한다."""
from __future__ import annotations

import itertools
import os

import pymupdf

from . import doc_ops
from .doc_ops import DocOpError

UNDO_LIMIT = 20
UNDO_BYTES = 400 * 1024 * 1024  # 되돌리기·다시 하기 사본을 합친 크기 한도(큰 스캔 PDF에서 메모리 폭주 방지)


def same_file(a: str | None, b: str | None) -> bool:
    if not a or not b:
        return False
    if os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b)):
        return True
    try:  # 링크·정션·별칭 경로로 같은 파일을 가리키는 경우
        return os.path.samefile(a, b)
    except OSError:
        return False


class Document:
    def __init__(self, path: str, password: str | None = None):
        with open(path, "rb") as f:
            data = f.read()
        self.doc = doc_ops.open_pdf_bytes(data, password)
        self.path = os.path.abspath(path)
        self.password = password
        # 저장하면 암호·권한 제한이 풀림을 알리려고(권한 암호만 걸린 문서는 is_encrypted가 False라 metadata로 본다)
        self.was_encrypted = bool(password or self.doc.is_encrypted or (self.doc.metadata or {}).get("encryption"))
        # 사본 = (PDF 내용, 상태 번호). 상태 번호로 '저장한 그 상태'에 돌아왔는지 안다
        self._undo: list[tuple[bytes, int]] = []
        self._redo: list[tuple[bytes, int]] = []  # 되돌린 편집(새 편집을 하면 비운다)
        self._ids = itertools.count(1)
        self._state = 0
        self._saved_state = 0

    # ── 이름 ──
    @property
    def name(self) -> str:
        return os.path.basename(self.path)

    @property
    def page_count(self) -> int:
        return self.doc.page_count

    # ── 수정 표시: 저장한 상태와 지금 상태가 다르면 수정됨 ──
    @property
    def modified(self) -> bool:
        return self._state != self._saved_state

    @modified.setter
    def modified(self, value: bool) -> None:
        self._saved_state = -1 if value else self._state

    # ── 편집 ──
    def _trim(self, keep: list) -> None:
        """사본 개수·합친 크기 한도를 지킨다.

        가장 오래된 되돌리기부터, 그다음 가장 먼 다시 하기부터 버린다. 방금 한 일을 거꾸로 돌릴
        사본(keep 목록의 맨 끝)만은 남겨, 한도를 넘는 것은 그 사본 하나만 남았을 때뿐이다.
        """
        while len(self._undo) > UNDO_LIMIT:
            self._undo.pop(0)
        total = sum(len(b) for b, _ in self._undo) + sum(len(b) for b, _ in self._redo)
        while total > UNDO_BYTES:
            if len(self._undo) > (1 if keep is self._undo else 0):
                total -= len(self._undo.pop(0)[0])
            elif len(self._redo) > (1 if keep is self._redo else 0):
                total -= len(self._redo.pop(0)[0])
            else:
                break

    def edit(self, fn, *args):
        """doc_ops 함수를 실행한다. 실패하면 문서를 편집 전으로 돌려놓고 이력도 그대로 둔다."""
        before = self.doc.tobytes()
        try:
            result = fn(self.doc, *args)
        except Exception as e:
            self._restore(before)
            if isinstance(e, DocOpError):
                raise
            raise DocOpError(f"편집하지 못했습니다: {e}") from e
        self._undo.append((before, self._state))
        self._redo.clear()
        self._state = next(self._ids)
        self._trim(self._undo)
        return result

    def can_undo(self) -> bool:
        return bool(self._undo)

    def can_redo(self) -> bool:
        return bool(self._redo)

    def undo(self) -> bool:
        return self._step(self._undo, self._redo)

    def redo(self) -> bool:
        return self._step(self._redo, self._undo)

    def _step(self, src: list, dst: list) -> bool:
        if not src:
            return False
        data, state = src.pop()
        dst.append((self.doc.tobytes(), self._state))
        self._restore(data)
        self._state = state
        self._trim(dst)
        return True

    def _restore(self, data: bytes) -> None:
        old = self.doc
        self.doc = pymupdf.open(stream=data, filetype="pdf")
        old.close()

    # ── 저장 ──
    def save_to(self, out_path: str) -> None:
        """도중에 실패해도 기존 파일이 깨지지 않게 저장한다(doc_ops.atomic_save)."""
        out_path = os.path.abspath(out_path)
        doc_ops.atomic_save(self.doc, out_path)
        self.path = out_path
        self.modified = False
        self.was_encrypted = False  # 저장한 파일에는 암호가 없으므로 다음 저장부터는 묻지 않는다

    def export_pages(self, indices, out_path: str) -> int:
        if same_file(out_path, self.path):
            raise DocOpError("지금 열린 원본 파일 이름으로는 내보낼 수 없습니다. 다른 이름을 골라 주세요.")
        try:
            return doc_ops.export_pages(self.doc, indices, out_path)
        except DocOpError:
            raise
        except Exception as e:
            raise DocOpError(f"내보내지 못했습니다: {e}") from e

    def close(self) -> None:
        self.doc.close()
