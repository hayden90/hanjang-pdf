"""매각·브리핑자료 폴더 목록 시험(가짜 폴더)."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pdfviewer.core import im_index  # noqa: E402


def touch(p):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "wb").close()


def test_rev_of():
    assert im_index.rev_of("가나빌딩_브리핑자료_rev2.pdf") == 2
    assert im_index.rev_of("다라동_매각자료(IM)_REV11.pdf") == 11
    assert im_index.rev_of("a_rev3_사본_rev 12.pdf") == 12
    assert im_index.rev_of("[샘플] 마바동 C동 IM_260731.pdf") is None


def test_find_root_prefers_new_name(tmp_path):
    drive = tmp_path / "내 드라이브"
    subs = [("회사", "2_브리핑자료"), ("회사", "매각자료")]
    old = drive / "회사" / "매각자료"
    new = drive / "회사" / "2_브리핑자료"
    old.mkdir(parents=True)
    assert im_index.find_root([str(drive)], subs) == str(old)
    new.mkdir(parents=True)
    assert im_index.find_root([str(tmp_path / "없음"), str(drive)], subs) == str(new)
    assert im_index.find_root([str(tmp_path / "없음")], subs) is None
    assert im_index.find_root([str(drive)]) is None  # 기본 후보 없음: 사용자가 고름


def test_scan_groups_and_latest_first(tmp_path):
    root = tmp_path / "root"
    a = root / "가나빌딩 브리핑자료"
    touch(str(a / "가나빌딩_브리핑자료_rev1.pdf"))
    touch(str(a / "가나빌딩_브리핑자료_rev10.pdf"))
    touch(str(a / "가나빌딩_브리핑자료_rev2.pdf"))
    touch(str(a / "건축물 대장.pdf"))
    touch(str(a / "~$임시.pdf"))
    touch(str(a / "메모.docx"))
    touch(str(root / "빈 폴더" / "설명.txt"))
    touch(str(root / "따로.pdf"))
    g = im_index.scan(str(root))
    assert [x["name"] for x in g] == ["가나빌딩 브리핑자료", "(폴더 밖)"]
    docs = g[0]["docs"]
    assert [d["name"] for d in docs] == [
        "가나빌딩_브리핑자료_rev10.pdf", "가나빌딩_브리핑자료_rev2.pdf",
        "가나빌딩_브리핑자료_rev1.pdf", "건축물 대장.pdf"]
    assert [d["latest"] for d in docs] == [True, False, False, False]
    assert im_index.previous_rev(docs, docs[0])["name"] == "가나빌딩_브리핑자료_rev2.pdf"
    assert im_index.previous_rev(docs, docs[2]) is None
    assert im_index.previous_rev(docs, docs[3]) is None


def test_scan_missing_root(tmp_path):
    assert im_index.scan(str(tmp_path / "없음")) == []
