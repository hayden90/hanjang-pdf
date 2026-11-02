# 한장PDF 안드로이드판: 빌드 방법

안드로이드용 한장PDF(PDF 보기·찾기, 쪽 작업, 형광펜·메모·글자 상자·네모 상자)의 소스입니다.
PDF 엔진은 MuPDF(fitz, AGPL v3)이고, 앱 전체도 저장소 루트의 [LICENSE](../LICENSE)(GNU AGPL v3)로 공개합니다.
앱 안의 **정보** 화면(⋮ 메뉴 > 정보)에 이 판의 소스 주소, 이 문서 주소, 쓰는 부품과 라이선스 전문이 들어 있습니다.

## 준비물

- JDK 17
- Android SDK: platform `android-36`, build-tools(AGP가 고르는 판), platform-tools(adb)
- 인터넷(처음 빌드할 때 Gradle·부품을 받습니다. MuPDF는 `maven.ghostscript.com`에서만 받습니다)

SDK 위치는 `android/local.properties`에 적습니다(저장소에 올리지 않는 파일).

```
sdk.dir=/경로/Android/sdk
```

## 빌드

```
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # 서명 설정은 들어 있지 않습니다(서명하지 않은 APK)
```

- Gradle 9.8.2(래퍼가 받음), Android Gradle Plugin 9.4.0, Kotlin·Compose(BOM 2025.10.00)
- minSdk 26(안드로이드 8.0), targetSdk 36
- 권한: 없음. 인터넷·저장소 권한을 쓰지 않고, 파일은 시스템 파일 고르기 창(SAF)으로만 엽니다.
- 정보 화면의 부품 고지(`assets/about/notices.tsv`)는 빌드할 때 이 빌드의 런타임 의존성에서 자동으로 만듭니다.
  라이선스를 모르는 부품이 들어오면 빌드가 멈춥니다. 그때는 `app/build.gradle.kts`의 `AboutAssetsTask`에
  그 부품의 라이선스를 더합니다.

## 소스 판(태그)

배포한 앱 판 `X.Y.Z`의 소스는 GitHub 태그 `android-vX.Y.Z`에 있습니다(정보 화면의 소스 링크가 이 태그를 엽니다).
판 번호는 `app/build.gradle.kts`의 `versionName`입니다.

## 시험

기기 시험(계측)은 연결한 기기나 에뮬레이터에서 돌립니다. 화면을 누르지 않고 화면 모델·의미 동작으로만 시험합니다.

```
./gradlew assembleDebug assembleDebugAndroidTest
adb -s <기기> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <기기> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <기기> shell am instrument -w com.bubigter.hanjangpdf.test/androidx.test.runner.AndroidJUnitRunner
```

- 접근성(`A11yTest`): 한국어·영어, 글꼴 200%, 어두운 화면에서 이름 없는 단추, 48dp보다 작은 누르는 영역,
  잘린 글, 겹친 단추, Accessibility Test Framework 검사를 봅니다(기기 설정은 바꾸지 않음).
- PC판과 주석 호환(`CompatTest`, `PagesTest`)은 저장소 루트에서 PC 쪽 도구와 함께 돌립니다:
  `python -m tests.android_compat.compat …`, `python -m tests.android_compat.pages compare …`
- PC 쪽 정적 검사(화면 글 한국어·영어 짝, MuPDF 참조 규칙): 저장소 루트에서
  `python -m pytest -q tests/android_compat`

16KB 페이지 기기 확인: `adb -s <기기> shell getconf PAGE_SIZE`가 `16384`인 에뮬레이터(안드로이드 15 이상,
16KB 시스템 이미지)에서 같은 계측 시험을 돌립니다.

## 언어

화면 글은 `app/src/main/res/values/strings.xml`(한국어)와 `values-en/strings.xml`(영어)에 있고 기기 언어를 따릅니다.
화면 없는 부분(core)이 내는 한국어 알림 글은 `core_*` 틀로 알아보고 화면 언어로 바꿉니다(`ui/CoreText.kt`).
새 글을 넣으면 두 파일에 같은 이름으로 넣어야 하며, `tests/android_compat/test_android_i18n.py`가 빠진 것을 잡습니다.
