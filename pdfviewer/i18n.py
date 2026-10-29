"""화면 글자 언어(한국어·영어).

코드에는 한국어 문장을 그대로 두고 tr()로 감싼다. 영어로 볼 때만 i18n_en.EN 사전에서 바꿔 보여 준다.
언어는 프로그램을 켤 때 한 번 정한다(바꾸면 다시 켜야 적용):
  1. 환경변수 PDFV_LANG(시험용) 2. 사용자가 도움말 > Language에서 고른 값 3. 윈도우 표시 언어(한국어면 한국어, 아니면 영어)
"""
import os

from PySide6.QtCore import QLocale, QSettings

from .i18n_en import EN

LANGS = ("ko", "en")
SETTING_KEY = "ui/lang"


def settings() -> QSettings:
    """창과 같은 설정 저장소(시험에서는 PDFV_SETTINGS 파일)."""
    ini = os.environ.get("PDFV_SETTINGS")  # 시험용: 실제 사용자 설정과 섞이지 않게 따로 둔 파일
    return QSettings(ini, QSettings.Format.IniFormat) if ini else QSettings("HighStar", "PDFViewer")


def pick_lang(saved=None, system: QLocale.Language | None = None) -> str:
    env = os.environ.get("PDFV_LANG")
    if env in LANGS:
        return env
    if saved is None:
        saved = settings().value(SETTING_KEY, "")
    if saved in LANGS:
        return saved
    if system is None:
        system = QLocale.system().language()
    return "ko" if system == QLocale.Language.Korean else "en"


LANG = pick_lang()


def tr(text: str) -> str:
    """화면에 보일 문장. 사전에 없으면 한국어 그대로(시험이 빠진 것을 잡는다)."""
    if LANG == "ko":
        return text
    return EN.get(text, text)


def save_lang(lang: str, s: QSettings | None = None) -> None:
    """다음에 켤 때 쓸 언어를 저장한다."""
    if lang not in LANGS:
        raise ValueError(lang)
    s = s or settings()
    s.setValue(SETTING_KEY, lang)
    s.sync()
