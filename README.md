# SleepWell_APP

Android 수면 기록·개인화 수면 계획 앱 **SleepWell**입니다. Samsung Health가 Health Connect에 공유한 수면 기록을 누적하고, 사용자의 수면 이력 특징과 Two-Process 생리 모델 특징을 기기 내 HGB 하이브리드 예측에 사용합니다.

- **UI 디자인 및 모델 개발: [Han Seoyun (han-seo-yun), ](https://github.com/han-seo-yun)**
- **백엔드: [Choi Sobin (soin7563-ops), ](https://github.com/soin7563-ops)**
- **프론트엔드: [Jeong Chaeyoon (iuniion3287-web), ](https://github.com/iuniion3287-web)**
- **베타 테스트: [Shin Hyeji (Hyeji-Shin-lab), ](https://github.com/Hyeji-Shin-lab)**

패키지 `app.sleepwell.offlinehgb` · 버전 **0.8.9** (17) · 변경일 `2026-11-08`

## 설치용 APK

**[SleepWell v0.8.9 APK 다운로드](https://github.com/han-seo-yun/SleepWell_APP/releases/download/v0.8.9/SleepWell-v0.8.9-debug.apk)**

Android 10(API 29) 이상용 디버그 서명 APK입니다.

[릴리스 상세](https://github.com/han-seo-yun/SleepWell_APP/releases/tag/v0.8.9) · [SHA-256 확인 파일](https://github.com/han-seo-yun/SleepWell_APP/releases/download/v0.8.9/SHA256SUMS.txt)

## SleepWell Update

실행 경로는 **Data 피쳐 33개 + Two-Process 수리 피쳐 6개 → HGB 모델 계산 → 기존 점진 개인화·권장 정책**입니다. 하이브리드 모델 예측을 기존 앱 정책대로 결합합니다. (배포를 위해 대체한 모델로, 서버 연결과 TabDPT 모델이 들어가지 않습니다.)

0.8.7의 경량 TabDPT 모델·ONNX Runtime 의존성·학습 문맥 번들을 APK에서 제외했습니다. 기존 검증된 HGB 하이브리드 모델은 학습 모델은 저장소에 포함하므로 별도 모델 다운로드 없이 앱을 빌드할 수 있습니다.

수면 기록·설문·프로필·CSV·결과 스냅샷의 저장 형식, 사용자의 데이터 계산, 사용이력에 따른 점진 개인화, 워치 입력 정책, Health Connect 누적·백그라운드 동기화 및 기기 시간대 처리는 유지합니다.

기존 비교 실험의 개인화 보정 후 입면·기상 평균 절대오차는 연구용 TabDPT 57.40분, 경량 TabDPT 57.59분, HGB 58.17분이었습니다.

## 화면과 주요 기능

- **오늘:** 오늘의 수면 준비, 최근 수면 구간·단계 그래프, 개인화 권장, 7일·30일 추세, 모델 추정 지수와 추천 근거.
- **기록:** 월간 달력에 실제 수면과 저장한 분석 결과를 구분해 표시합니다. 날짜를 누르면 수면 상세와 저장 결과를 바텀시트로 확인합니다.
- **내 정보:** 성별·생활 습관과 희망 수면시간을 관리합니다. 희망 수면은 시·분으로 직접 입력하며 연령대·직업 입력란은 추후 업데이트 예정입니다.
- **자동 누적:** 전경 갱신과 기존 백그라운드 동기화를 유지합니다. 같은 기록의 중복 처리·출처·관측 시각을 보존합니다.
- **필요한 입력만 보완:** 웨어러블 센서에서 수집하지 못하는 지표는 앱 내 설문으로 보완합니다. 파일 가져오기와 직접 기록또한 가능합니다.

## 빌드 및 실행

먼저 위 스크립트로 검증된 경량 TabDPT 번들을 준비합니다. 그 다음 Android Studio에서 저장소를 열고 Android SDK 경로를 설정한 뒤 `app` 구성을 실행합니다. 로컬 SDK 경로와 모델 번들은 저장소에 포함하지 않습니다.

| 항목 | 설정 |
|---|---|
| Android SDK | min 29 / compile 37 / target 37 |
| Android Gradle Plugin | 9.4.0 |
| Gradle Wrapper | 9.7.1 |
| Kotlin Compose 플러그인 | 2.4.20 |
| Compose BOM | 2026.09.00 |
| Health Connect | 1.1.0 |
| ONNX Runtime Android | 1.22.0 |
| Gradle daemon JVM | 저장소 설정 25; 검증은 Android Studio 번들 JBR 사용 |
| Java source/target | 11 |

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

생성된 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다. Health Connect 수면 읽기 권한이 필요하며 백그라운드 읽기는 지원 OS·권한·배터리 정책에 따라 실행 시점이 달라집니다. 앱 강제 중지 상태에서는 다시 앱을 열 때까지 동기화가 중단될 수 있습니다.


## 소스 구성

```text
app/src/main/java/app/sleepwell/
  analysis/   # 수면 누적, DATA33 연계, 설문·프로필·결과 보관, 동기화
  health/     # Health Connect·CSV 및 분석 인터페이스
  user/       # 모델·개인화·측정·권장 정책
  ui/         # 오늘·기록·내 정보와 그래프
app/src/main/assets/       # 공개 가능한 모델·명시적 데모 자산; TabDPT 번들은 로컬 준비
app/src/androidTest/      # 회귀 테스트와 격리된 fixture
gradle/                   # Wrapper 및 버전 카탈로그
tools/                    # 테스트 fixture 생성 도구
```

앱 코드·리소스·테스트·빌드 설정·개발 도구를 포함합니다. 모델 가중치·학습 문맥, 빌드 산출물, 로컬 SDK 설정, 인증정보, 실제 수면·설문 데이터와 검증 백업은 데이터 정책에 따라 제외하였습니다.
