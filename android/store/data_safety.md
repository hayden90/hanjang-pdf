# 데이터 보안(Data safety) 양식 답 초안

플레이 콘솔 '앱 콘텐츠 > 데이터 보안'에 넣을 답입니다. 근거는 이 저장소 코드로 확인한 것입니다(2026-11-02, 판 0.1.0).

## 답
| 질문 | 답 |
|---|---|
| 앱이 필수 사용자 데이터 유형을 수집하거나 공유하나요? | **아니요** |
| (위가 '아니요'라 아래 데이터 유형·암호화·삭제 요청 항목은 나오지 않음) | |

플레이 기준에서 '수집'은 앱이 기기 밖(개발자·제3자 서버)으로 데이터를 보내는 것입니다. 기기 안에서만 다루는 것은 수집이 아니고,
사용자가 직접 고른 앱으로 보내는 '공유'(안드로이드 공유 창)는 플레이 안내상 공유로 보지 않습니다.

## 근거(코드로 확인)
- 인터넷 권한 없음: 출시 빌드 병합 매니페스트의 `uses-permission`은 AndroidX가 넣는 `com.bubigter.hanjangpdf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 하나뿐(빌드 때 `releaseManifestCheck`가 다른 권한이 끼면 빌드를 멈춤).
- 저장소 권한 없음: 파일은 시스템 파일 고르기(SAF)와 다른 앱의 '열기·공유'(content://)로만 받음.
- 공유: `FileProvider`는 앱 캐시 `share/`만 열고, 고른 앱에 읽기 권한만 줌(`res/xml/share_paths.xml`).
- 광고·분석·오류 보고 SDK 없음: 런타임 부품은 MuPDF(fitz)와 AndroidX·Kotlin뿐(`android/licenses/modules.tsv`, 모르는 부품은 빌드가 멈춤).
- 백업 제외: `allowBackup=false`, `data_extraction_rules.xml`·`backup_rules.xml`이 모든 영역을 뺌.
- 바깥 연결: 정보 화면 링크 두 개를 브라우저로 넘기기만 함(앱이 직접 통신하지 않음).

## 부품을 더하거나 권한을 바꾸면
이 답을 다시 확인합니다. 특히 인터넷 권한이 생기면 이 양식과 PRIVACY.md를 함께 고쳐야 합니다.
