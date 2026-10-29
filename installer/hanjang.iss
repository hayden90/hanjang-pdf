; 한장PDF 설치 프로그램(Inno Setup 6). 메인컴 빌드에서
;   ISCC /DDistDir=<dist\HanjangPDF> /DAppVer=<판> /DOutDir=<출력 폴더> installer\hanjang.iss
#ifndef DistDir
  #define DistDir "..\..\dist\HanjangPDF"
#endif
#ifndef AppVer
  #define AppVer "rev13"
#endif
#ifndef OutDir
  #define OutDir "..\..\installer_out"
#endif
#define AppName "한장PDF"
#define ExeName "HanjangPDF.exe"
#define ProgId "HanjangPDF.pdf"

[Setup]
AppId={{6F1C2A4E-3B7D-4C5A-9E21-8D0B7A64C3F1}
AppName={#AppName}
AppVersion={#AppVer}
AppVerName={#AppName} {#AppVer}
AppPublisher=부빅터
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
; 설치 위치 고르기 화면을 늘 보여 준다
DisableDirPage=no
DisableProgramGroupPage=yes
; 관리자 권한 없이 '나만' 설치가 기본, 설치 창에서 '모든 사용자'도 고를 수 있다
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ChangesAssociations=yes
CloseApplications=yes
SetupIconFile=..\assets\hanjang.ico
UninstallDisplayIcon={app}\{#ExeName}
UninstallDisplayName={#AppName}
OutputDir={#OutDir}
OutputBaseFilename=HanjangPDF_Setup_{#AppVer}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "desktopicon"; Description: "바탕화면에 바로가기 만들기"; GroupDescription: "바로가기:"

[Files]
Source: "{#DistDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; AGPL·부품 라이선스 고지를 설치 폴더에 함께 둔다
Source: "..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#ExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#ExeName}"; Tasks: desktopicon

[Registry]
; PDF '연결 프로그램' 목록에 나오게 한다(기본 프로그램을 몰래 바꾸지는 않음 — 고르는 것은 사용자)
Root: HKA; Subkey: "Software\Classes\{#ProgId}"; ValueType: string; ValueName: ""; ValueData: "PDF 문서 ({#AppName})"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\{#ProgId}\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\{#ExeName}"",0"
Root: HKA; Subkey: "Software\Classes\{#ProgId}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#ExeName}"" ""%1"""
Root: HKA; Subkey: "Software\Classes\.pdf\OpenWithProgids"; ValueType: string; ValueName: "{#ProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\{#ExeName}"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "{#AppName}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\{#ExeName}\SupportedTypes"; ValueType: string; ValueName: ".pdf"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\Applications\{#ExeName}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#ExeName}"" ""%1"""
; 윈도우 설정 > 기본 앱 목록에도 나오게
Root: HKA; Subkey: "Software\HanjangPDF\Capabilities"; ValueType: string; ValueName: "ApplicationName"; ValueData: "{#AppName}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\HanjangPDF\Capabilities"; ValueType: string; ValueName: "ApplicationDescription"; ValueData: "PDF 보기·형광펜·메모·글자 상자"
Root: HKA; Subkey: "Software\HanjangPDF\Capabilities\FileAssociations"; ValueType: string; ValueName: ".pdf"; ValueData: "{#ProgId}"
Root: HKA; Subkey: "Software\RegisteredApplications"; ValueType: string; ValueName: "{#AppName}"; ValueData: "Software\HanjangPDF\Capabilities"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#ExeName}"; Description: "{#AppName} 실행"; Flags: nowait postinstall skipifsilent
