# 한장PDF

부동산 서류를 보기 좋게 만든 윈도우용 PDF 뷰어입니다.

- 보기·찾기·쪽 넘기기, 쪽 넣기·빼기·돌리기·내보내기
- 형광펜·메모·글자 상자·네모 상자, 지우개, 인쇄
- 표 칸 단위로 끌어 고르기·복사(등기부·건축물대장 같은 표)
- **글자 인식**(도구 > 글자 인식, Ctrl+Shift+O): 세움터 건축물대장처럼 글자가 그림으로 된 쪽을
  윈도우 내장 한국어 인식으로 읽어 고르기·복사·찾기가 되게 합니다. 인식은 PC 안에서만 합니다.
  인식한 숫자·지번은 틀릴 수 있으니 원문과 대조해 주세요.
- 자료 폴더 목록(보기 > 자료 폴더): 건물 폴더별 PDF를 최신 rev 순으로 보고, 두 판을 나란히 비교

만든 곳: [부빅터](https://www.bubigter.com)

## 개인정보

한장PDF는 문서 내용이나 사용 기록을 어디에도 보내지 않습니다. 자세한 내용은 [PRIVACY.md](PRIVACY.md).

## 실행(개발)

```
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python pdf_viewer.py [파일.pdf]
```

시험: `QT_QPA_PLATFORM=offscreen python -m pytest tests`

## 라이선스

GNU Affero General Public License v3.0([LICENSE](LICENSE)). PDF 엔진 PyMuPDF·MuPDF(Artifex)가 AGPL v3이라
이 프로그램도 같은 조건으로 공개합니다. 쓰는 부품: PyMuPDF(AGPL v3), Qt for Python·PySide6(LGPL v3), pywinrt(MIT).
