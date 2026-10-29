"""영어 화면 문장 사전: 한국어 원문 → 영어.

열쇠는 코드의 tr("…") 원문과 글자 하나까지 같아야 한다. {이름} 자리표시는 두 말에서 똑같이 둔다.
빠지거나 남는 항목은 tests/test_i18n.py가 잡는다.
"""

EN: dict[str, str] = {
    # ── 프로그램·메뉴 ──
    "한장PDF": "HanjangPDF",
    "서류를 보기 좋게 만든 PDF 뷰어": "A PDF viewer made for reading documents comfortably",
    "이 프로그램은 자유 소프트웨어입니다. GNU AGPL v3 조건으로 다시 배포하거나 고칠 수 있으며, 어떤 보증도 하지 않습니다.":
        "This program is free software. You may redistribute and modify it under the GNU AGPL v3. It comes with no warranty.",
    "소스 코드": "Source code",
    "쓰는 부품: PyMuPDF·MuPDF(AGPL v3, Artifex), Qt·PySide6(LGPL v3), pywinrt(MIT)":
        "Components: PyMuPDF/MuPDF (AGPL v3, Artifex), Qt/PySide6 (LGPL v3), pywinrt (MIT)",
    "문서 내용은 PC 밖으로 보내지 않습니다(글자 인식도 윈도우 안에서만).":
        "Your documents never leave your PC (text recognition also runs inside Windows).",
    "{APP_NAME} 정보": "About {APP_NAME}",
    "파일": "File",
    "편집": "Edit",
    "도구": "Tools",
    "보기": "View",
    "도움말": "Help",
    "주석": "Annotations",
    "열기": "Open",
    "저장": "Save",
    "지금 파일 이름 그대로 저장 (Ctrl+S)": "Save under the current file name (Ctrl+S)",
    "다른 이름으로 저장": "Save As",
    "인쇄…": "Print…",
    "인쇄": "Print",
    "인쇄 (Ctrl+P). 형광펜·메모·글자 상자도 함께 찍힘": "Print (Ctrl+P). Highlights, notes and text boxes are printed too",
    "탭 닫기": "Close Tab",
    "끝내기": "Exit",
    "되돌리기": "Undo",
    "방금 한 편집 되돌리기 (Ctrl+Z)": "Undo the last edit (Ctrl+Z)",
    "다시 하기": "Redo",
    "되돌린 편집 다시 하기 (Ctrl+Y)": "Redo the undone edit (Ctrl+Y)",
    "글자 복사": "Copy Text",
    "보기 모드에서 쪽 위를 끌어 고른 글자 복사 (Ctrl+C)": "Copy the text selected by dragging in View mode (Ctrl+C)",
    "고른 쪽 내보내기…": "Export Selected Pages…",
    "현재 쪽 뒤에 PDF 넣기…": "Insert PDF After Current Page…",
    "고른 쪽 지우기": "Delete Selected Pages",
    "↺ 반시계 90°": "↺ Rotate Left 90°",
    "고른 쪽(없으면 지금 쪽)을 반시계 방향으로 90° 돌리기 (Ctrl+L)":
        "Rotate the selected pages (or the current page) 90° counterclockwise (Ctrl+L)",
    "↻ 시계 90°": "↻ Rotate Right 90°",
    "고른 쪽(없으면 지금 쪽)을 시계 방향으로 90° 돌리기 (Ctrl+R)":
        "Rotate the selected pages (or the current page) 90° clockwise (Ctrl+R)",
    "찾기": "Find",
    "확대": "Zoom In",
    "축소": "Zoom Out",
    "폭 맞춤": "Fit Width",
    "쪽 맞춤": "Fit Page",
    "◀ 이전 쪽": "◀ Previous",
    "다음 쪽 ▶": "Next ▶",
    "부빅터에서 건물·실거래 정보 보기": "Building and transaction data on Bubigter",
    "부빅터 웹사이트를 브라우저로 엽니다": "Opens the Bubigter website in your browser",
    "한장PDF 소스 코드(GitHub)를 브라우저로 엽니다": "Opens the HanjangPDF source code (GitHub) in your browser",
    "PDF 파일을 이 창에 끌어다 놓거나 Ctrl+O로 여세요.": "Drag a PDF file into this window, or press Ctrl+O to open one.",
    "PDF 열기": "Open PDF",
    "{name}에 저장하지 않은 편집이 있습니다. 저장할까요?": "{name} has unsaved changes. Save them?",

    # ── 모드·도구 ──
    "보기 모드(Esc). 쪽 위를 끌면 글자 고르기(Ctrl+C 복사), 메모 위에 올리면 내용, 두 번 누르면 고치기":
        "View mode (Esc). Drag on a page to select text (Ctrl+C to copy); hover a note to read it, double-click to edit",
    "형광펜": "Highlight",
    "글자 위를 끌어서 칠하기(Ctrl+H). 색은 옆 ▾": "Drag over text to highlight it (Ctrl+H). Pick the color with ▾",
    "메모": "Note",
    "쪽을 눌러 메모 붙이기(Ctrl+M)": "Click on a page to add a note (Ctrl+M)",
    "글자": "Text",
    "쪽 위를 끌어 글자 상자를 그리고 그 안에 바로 쓰기(Ctrl+T). 그냥 누르면 기본 폭 상자. 크기·정렬·색은 상자 위 막대에서, 인쇄에도 나옴. 넣은 글자를 누르면 고치기":
        "Drag on a page to draw a text box and type in it (Ctrl+T). A plain click makes a default-width box. "
        "Size, alignment and color are on the bar above the box, and the text prints too. Click added text to edit it",
    "상자": "Box",
    "쪽 위를 끌어 네모 상자 그리기(Ctrl+Shift+R). 선·채우기 색은 옆 ▾, 그린 상자를 누르면 지금 색으로 바뀜":
        "Drag on a page to draw a rectangle (Ctrl+Shift+R). Pick line and fill colors with ▾; "
        "clicking a drawn box applies the current colors",
    "지우개": "Eraser",
    "형광펜·메모·글자·상자를 눌러 지우거나, 끌어서 그 범위를 한꺼번에 지우기(Ctrl+Shift+E)":
        "Click a highlight, note, text or box to erase it, or drag to erase everything in an area (Ctrl+Shift+E)",
    "형광펜: 칠할 글자 위를 끌어 주세요. Esc로 끝": "Highlight: drag over the text to highlight. Esc to finish",
    "메모: 붙일 자리를 눌러 주세요. Esc로 끝": "Note: click where the note should go. Esc to finish",
    "글자: 넣을 자리를 끌어 상자를 그리고 바로 쓰세요(Ctrl+Enter 넣기). 넣은 글자를 누르면 고치기":
        "Text: drag to draw a box and start typing (Ctrl+Enter to add). Click added text to edit it",
    "상자: 쪽 위를 끌어 그리세요. 색은 '상자' 옆 ▾. 그린 상자를 누르면 지금 색으로 바뀜":
        "Box: drag on the page to draw. Colors are under ▾ next to 'Box'. Clicking a drawn box applies the current colors",
    "지우개: 지울 것을 누르거나, 끌어서 범위를 한꺼번에 지우세요. Esc로 끝":
        "Eraser: click what to erase, or drag to erase an area at once. Esc to finish",
    "형광펜 색": "Highlight Color",
    "상자 색": "Box Color",
    "노랑": "Yellow",
    "초록": "Green",
    "분홍": "Pink",
    "검정": "Black",
    "빨강": "Red",
    "파랑": "Blue",
    "주황": "Orange",
    "연두": "Light green",
    "하늘": "Sky blue",
    "회색": "Gray",
    "흰색": "White",
    "없음": "None",
    "선 색": "Line Color",
    "채우기 색": "Fill Color",
    "선 굵기": "Line Width",
    "진하기": "Opacity",
    "다른 색…": "Other Color…",
    "100% (안 비침)": "100% (opaque)",
    "30% (아래 글자가 잘 보임)": "30% (text below stays readable)",
    "상자: 선 {line} · 채우기 {fill} · 굵기 {width:g}pt · 진하기 {opacity}%":
        "Box: line {line} · fill {fill} · width {width:g}pt · opacity {opacity}%",
    "글자 인식": "Text Recognition",
    "글자가 그림으로 된 쪽(세움터 건축물대장·스캔)을 읽어 고르기·복사·찾기가 되게 함(Ctrl+Shift+O). 윈도우 내장 인식, PC 밖으로 안 보냄. 숫자·지번은 원문과 대조":
        "Reads pages whose text is only an image (scans, printouts) so you can select, copy and search it "
        "(Ctrl+Shift+O). Uses built-in Windows recognition and nothing leaves your PC. Check numbers against the original",
    "자료 폴더": "Documents Folder",
    "브리핑·매각자료 폴더 목록 보이기/숨기기(Ctrl+B). 두 판 비교도 여기서":
        "Show or hide the documents folder list (Ctrl+B). Compare two versions from here too",
    "보기 모드(Esc)에서 쪽 위를 마우스로 끌어 글자를 먼저 골라 주세요.":
        "First select text by dragging on a page in View mode (Esc).",
    "글자 {n}자를 복사했습니다.": "Copied {n} characters.",
    "두 판 비교(읽기 전용). '같이 넘기기'를 끄면 따로 넘길 수 있습니다.":
        "Comparing two versions (read-only). Turn off 'Scroll together' to page through them separately.",

    # ── 찾기·비교 ──
    "찾을 글자 (Enter: 다음, Shift+Enter: 이전)": "Find text (Enter: next, Shift+Enter: previous)",
    "이전": "Previous",
    "다음": "Next",
    "닫기": "Close",
    "같이 넘기기": "Scroll together",
    "비교: {name} ↔ {name2}": "Compare: {name} ↔ {name2}",
    "{name}  ({page_count}쪽)": "{name}  ({page_count} pages)",

    # ── 자료 폴더 ──
    "폴더 바꾸기…": "Change Folder…",
    "새로 고침": "Refresh",
    "고른 두 파일 비교": "Compare Two Selected Files",
    "두 번 누르면 열기 · Ctrl로 두 파일을 골라 '비교' · 오른쪽 클릭: 이전 rev와 비교":
        "Double-click to open · Ctrl-select two files and press 'Compare' · Right-click: compare with the previous rev",
    "브리핑·매각자료 폴더 고르기": "Choose a Documents Folder",
    "자료 폴더를 찾지 못했습니다.\n'폴더 바꾸기…'로 브리핑·매각자료 폴더를 골라 주세요.":
        "No documents folder found.\nUse 'Change Folder…' to choose one.",
    "비교할 파일을 Ctrl을 누른 채 두 개 골라 주세요.": "Hold Ctrl and select two files to compare.",
    "이전 rev 없음": "No previous rev",
    "이전 rev와 비교({name})": "Compare with previous rev ({name})",
    "★ 최신  ": "★ Latest  ",
    "(폴더 밖)": "(outside folders)",

    # ── 문서 편집·저장 ──
    "암호": "Password",
    "{file}\n암호를 넣어 주세요.": "{file}\nEnter the password.",
    "암호 문서": "Password-Protected Document",
    "이 PDF는 암호가 걸려 있었습니다. 저장하는 파일에는 암호가 걸리지 않습니다. 계속할까요?":
        "This PDF was password-protected. The saved file will not have a password. Continue?",
    "{at}쪽 자리에 {n}쪽을 넣었습니다.": "Inserted {n} pages at page {at}.",
    "{n}쪽을 지웠습니다. 되돌리기: Ctrl+Z": "Deleted {n} pages. Undo: Ctrl+Z",
    "되돌렸습니다. 다시 하기: Ctrl+Y": "Undone. Redo: Ctrl+Y",
    "다시 했습니다.": "Redone.",
    "넣을 PDF 고르기": "Choose a PDF to Insert",
    "{file} 파일은 다른 탭에 열려 있습니다.\n그 탭을 먼저 닫은 뒤 다시 저장해 주세요.":
        "{file} is open in another tab.\nClose that tab first, then save again.",
    "{file} 파일이 이미 있습니다. 바꿀까요?": "{file} already exists. Replace it?",
    "고른 쪽 내보내기": "Export Selected Pages",
    "{stem}_{pages}쪽.pdf": "{stem}_p{pages}.pdf",
    "내보내기": "Export",
    "{n}쪽을 {file}(으)로 내보냈습니다.": "Exported {n} pages to {file}.",
    "원본에 덮어쓰기": "Overwrite Original",
    "원본 파일 {name}을(를) 지금 내용으로 덮어씁니다.\n덮어쓰면 되돌릴 수 없습니다. 계속할까요?":
        "This will overwrite the original file {name} with the current content.\nThis cannot be undone. Continue?",
    "{stem}_편집.pdf": "{stem}_edited.pdf",
    "{file}(으)로 저장했습니다.": "Saved as {file}.",
    "{n}쪽을 인쇄로 보냈습니다.": "Sent {n} pages to the printer.",
    "프린터를 열지 못했습니다.": "Could not open the printer.",

    # ── 글자 인식 ──
    "모든 쪽에 이미 글자 정보가 있어 읽을 쪽이 없습니다.": "Every page already has text, so there is nothing to recognize.",
    "글자를 읽는 중…": "Recognizing text…",
    "취소": "Cancel",
    "글자 인식을 취소했습니다. 문서는 그대로입니다.": "Text recognition canceled. The document is unchanged.",
    "글자를 읽지 못했습니다: {e}": "Could not recognize text: {e}",
    "읽을 수 있는 글자를 찾지 못했습니다.": "No readable text was found.",
    "글자 인식: {n}쪽에 낱말 {count}개를 넣었습니다. 이제 끌어서 고르기·복사·찾기가 됩니다. 인식한 글자는 틀릴 수 있으니 숫자·지번은 원문과 대조하세요. 되돌리기: Ctrl+Z":
        "Text recognition: added {count} words on {n} pages. You can now select, copy and search them. "
        "Recognized text may contain mistakes, so check numbers against the original. Undo: Ctrl+Z",
    "글자 인식은 윈도우에서만 쓸 수 있습니다.": "Text recognition is only available on Windows.",
    "윈도우에 글자 인식 언어가 깔려 있지 않습니다. 설정 > 시간 및 언어 > 언어에서 쓰는 언어의 '광학 문자 인식'을 설치해 주세요.":
        "No text recognition language is installed in Windows. Go to Settings > Time & language > Language "
        "and install 'Optical character recognition' for your language.",
    "쪽 번호가 맞지 않습니다.": "The page number is out of range.",
    "이 쪽에는 글자 정보가 없어(스캔·그림으로 된 쪽) 고를 수 없습니다. 도구 > 글자 인식(Ctrl+Shift+O)을 눌러 보세요.":
        "This page has no text (it is a scan or an image), so nothing can be selected. "
        "Try Tools > Text Recognition (Ctrl+Shift+O).",
    "글자를 골랐습니다. 복사: Ctrl+C": "Text selected. Copy: Ctrl+C",

    # ── 주석 ──
    "형광펜을 칠했습니다. 되돌리기: Ctrl+Z": "Highlighted. Undo: Ctrl+Z",
    "메모 내용": "Note text",
    "메모 붙이기": "Add Note",
    "메모를 붙였습니다.": "Note added.",
    "메모 고치기": "Edit Note",
    "메모를 고쳤습니다.": "Note updated.",
    "메모: {text}\n(두 번 누르면 고치기)": "Note: {text}\n(double-click to edit)",
    "글자 고치기": "Edit Text",
    "글자를 넣었습니다. 인쇄에도 나옵니다. 되돌리기: Ctrl+Z": "Text added. It will print too. Undo: Ctrl+Z",
    "글자를 고쳤습니다. 되돌리기: Ctrl+Z": "Text updated. Undo: Ctrl+Z",
    "상자를 그렸습니다. 색은 '상자' 옆 ▾, 그린 상자를 누르면 지금 색으로 바뀝니다.":
        "Box drawn. Colors are under ▾ next to 'Box'; clicking a drawn box applies the current colors.",
    "상자는 쪽 위를 끌어서 그려 주세요. 그린 상자를 누르면 지금 고른 색으로 바뀝니다.":
        "Drag on the page to draw a box. Clicking a drawn box applies the currently chosen colors.",
    "상자 색을 바꿨습니다. 되돌리기: Ctrl+Z": "Box colors changed. Undo: Ctrl+Z",
    "그 자리에 지울 형광펜·메모·글자·상자가 없습니다. 끌면 그 범위를 한꺼번에 지웁니다.":
        "There is no highlight, note, text or box there to erase. Drag to erase everything in an area.",
    "지웠습니다. 되돌리기: Ctrl+Z": "Erased. Undo: Ctrl+Z",
    "끈 범위에 지울 형광펜·메모·글자·상자가 없습니다.": "There is no highlight, note, text or box in that area to erase.",
    "{n}개를 지웠습니다. 되돌리기: Ctrl+Z": "Erased {n} items. Undo: Ctrl+Z",

    # ── 글자 상자 ──
    "여기에 글자를 쓰세요 (Ctrl+Enter: 넣기, Esc: 취소)": "Type here (Ctrl+Enter: add, Esc: cancel)",
    "색": "Color",
    "넣기": "Add",
    "글자 색": "Text Color",
    "글자 넣기": "Add Text",
    "넣을 글자 (Enter로 줄 바꿈)": "Text to add (Enter for a new line)",
    "왼쪽": "Left",
    "가운데": "Center",
    "오른쪽": "Right",
    "왼쪽: 누른 곳에서 시작 / 가운데: 누른 곳이 가운데 / 오른쪽: 누른 곳에서 끝남":
        "Left: starts at the click / Center: centered on the click / Right: ends at the click",
    "크기": "Size",
    "정렬": "Alignment",

    # ── 쪽 목록 ──
    "쪽을 끌어서 원하는 자리에 놓으면 순서가 바뀝니다.\n다른 PDF 파일을 끌어다 놓으면 그 자리에 들어갑니다.\n오른쪽 클릭: 회전·내보내기·지우기":
        "Drag a page to a new position to reorder.\nDrop another PDF file to insert it there.\nRight-click: rotate, export, delete",
    "고른 쪽 내보내기(다른 이름으로 저장)…": "Export Selected Pages (Save As)…",
    "이 쪽 앞에 PDF 넣기…": "Insert PDF Before This Page…",
    "이 쪽 뒤에 PDF 넣기…": "Insert PDF After This Page…",
    "↻ 시계 방향 90° 돌리기": "↻ Rotate 90° Clockwise",
    "↺ 반시계 방향 90° 돌리기": "↺ Rotate 90° Counterclockwise",
    "쪽 지우기": "Delete Pages",
    "{n}장": "{n} pages",

    # ── 오류 ──
    "글자가 너무 많아 이 쪽에 다 들어가지 않습니다. 글자를 줄이거나 크기를 작게 해 주세요.":
        "The text is too long to fit on this page. Shorten it or use a smaller size.",
    "이 프로그램으로 넣은 형광펜·메모만 지우거나 고칠 수 있습니다.":
        "Only highlights and notes added with this program can be erased or edited.",
    "형광펜을 칠할 곳을 마우스로 끌어서 골라 주세요.": "Drag with the mouse over the area to highlight.",
    "메모 내용이 비어 있습니다.": "The note is empty.",
    "쪽 안을 눌러 메모를 붙여 주세요.": "Click inside the page to add a note.",
    "메모 내용이 비어 있습니다. 지우려면 지우개를 쓰세요.": "The note is empty. To remove it, use the Eraser.",
    "메모가 아닙니다.": "This is not a note.",
    "넣을 글자가 비어 있습니다.": "There is no text to add.",
    "쪽 안을 눌러 글자를 넣어 주세요.": "Click inside the page to add text.",
    "쪽 안을 끌어 글자 상자를 그려 주세요.": "Drag inside the page to draw a text box.",
    "글자 상자가 아닙니다.": "This is not a text box.",
    "지울 곳을 마우스로 끌어서 골라 주세요.": "Drag with the mouse over the area to erase.",
    "끈 곳에 지울 형광펜·메모·글자·상자가 없습니다.": "There is no highlight, note, text or box where you dragged.",
    "선 색과 채우기 색이 둘 다 '없음'이면 상자가 보이지 않습니다.":
        "If both the line and fill colors are 'None', the box is invisible.",
    "투명도는 10~100% 사이로 골라 주세요.": "Choose an opacity between 10% and 100%.",
    "상자를 그릴 곳을 마우스로 끌어서 정해 주세요.": "Drag with the mouse to set where the box goes.",
    "상자가 아닙니다.": "This is not a box.",
    "없는 쪽 번호입니다: {pno}": "No such page: {pno}",
    "없는 쪽 번호입니다: {i}": "No such page: {i}",
    "모르는 형광펜 색입니다: {color}": "Unknown highlight color: {color}",
    "글자 크기는 {TEXT_MIN}~{TEXT_MAX} 사이로 골라 주세요.": "Choose a text size between {TEXT_MIN} and {TEXT_MAX}.",
    "모르는 정렬입니다: {align}": "Unknown alignment: {align}",
    "모르는 색입니다: {color}": "Unknown color: {color}",
    "모르는 색입니다: {hexv}": "Unknown color: {hexv}",
    "모르는 선 굵기입니다: {width}": "Unknown line width: {width}",
    "고른 쪽이 없습니다.": "No pages are selected.",
    "모든 쪽을 지울 수는 없습니다. 한 쪽 이상 남겨 주세요.": "You cannot delete every page. Keep at least one.",
    "회전은 90도 단위만 됩니다.": "Pages can only be rotated in steps of 90°.",
    "암호가 필요하거나 암호가 맞지 않습니다.": "A password is required, or the password is wrong.",
    "넣을 PDF에 쪽이 없습니다.": "The PDF to insert has no pages.",
    "넣을 위치가 잘못되었습니다: {at}": "Invalid insert position: {at}",
    "옮길 위치가 잘못되었습니다: {to}": "Invalid move position: {to}",
    "PDF를 열 수 없습니다: {e}": "Cannot open the PDF: {e}",
    "저장하지 못했습니다: {e}": "Could not save: {e}",
    "지금 열린 원본 파일 이름으로는 내보낼 수 없습니다. 다른 이름을 골라 주세요.":
        "You cannot export under the name of the open original file. Choose another name.",
    "편집하지 못했습니다: {e}": "Could not edit: {e}",
    "내보내지 못했습니다: {e}": "Could not export: {e}",
}
