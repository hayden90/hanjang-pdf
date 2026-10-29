"""시험은 한국어 화면으로 돈다(PC 언어와 사용자가 고른 언어에 영향받지 않게). 영어 화면은 test_i18n.py가 따로 띄워 본다."""
import os

os.environ["PDFV_LANG"] = "ko"
