# 통합 v8 검증 안내

검증일: 2026-11-06. CSV 과거 재분석 수정 후 Android API 37 에뮬레이터에서 33개 회귀 테스트 클래스의 **195개 테스트가 통과**했습니다 (32.287초). 수동 실행 전용 `BackgroundJobLifecycleProbe`는 이 집계에서 제외했습니다. 별도 JVM 테스트 소스는 없으므로 `testDebugUnitTest`는 `NO-SOURCE`입니다.

## 빌드

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

캐시가 준비된 검증 환경에서는 `--offline`을 사용했습니다. 최초 설정에서는 SDK와 Gradle 의존성 다운로드가 필요합니다. CSV 재분석 최종 소스에서 앱·테스트 APK 빌드와 lint를 다시 실행했으며 오류 0개, 경고 8개, 힌트 9개였습니다.

## 회귀 테스트 실행 예시

아래 명령은 SDK의 `adb`가 PATH에 있고 에뮬레이터 한 대가 연결되어 있다는 전제입니다. 테스트용 에뮬레이터를 사용하세요. `install -r`는 기존 앱 데이터를 초기화하지 않지만 서명이 같아야 합니다.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$classes = Get-ChildItem app/src/androidTest/java -Recurse -Filter '*Test.kt' | ForEach-Object {
    $packageLine = (Select-String -LiteralPath $_.FullName -Pattern '^package ').Line
    $packageName = $packageLine.Substring(8).Trim()
    "$packageName.$($_.BaseName)"
}
adb shell am instrument -w -r -e class ($classes -join ',') app.sleepwell.test/android.test.InstrumentationTestRunner
```

이 명령은 현재 테스트 파일명과 클래스명이 같은 구조를 이용합니다. 수동 작업 상태를 검사하는 `BackgroundJobLifecycleProbe`는 선택되지 않습니다.

## CSV 과거 재분석 회귀 범위

- 실제 CSV 저장 → 원본별 모델 입력 로드 → 과거 분석 호출까지 연결 검증
- 선택 날짜가 바뀌면 입력 기록과 DATA33 누적 범위가 달라지고, 유효 CSV 내용 변경이 예측에 반영됨
- 후일 CSV·HC·일지·설문을 추가해도 같은 과거 날짜의 예측·DATA33은 동일함
- UTC에서 화면 다음 자정이 KST 모델 20시보다 빠른 경우, KST 20시 유지, 뉴욕 DST 25시간 날짜
- 경계 시각 HC·일지·직접 입력을 중복 정리 전에 제외하여 유효한 이전 기록을 밀어내지 않음
- CSV와 HC 중첩, 실시간 경로의 원래 수신 시각·우선순위 유지, 모델 전역 상태와 파일 원본 불변
- 예측 생성 방식·입력 경계의 스냅샷/결과 CSV 왕복 저장, 구버전 기본값, 같은 날짜 원본 예측과 재분석의 공존

구형 `InstrumentationTestRunner`의 전체 DEX 자동 검색은 이 환경에서 시작 ANR을 일으켰습니다. 위처럼 테스트 클래스를 명시하여 실행하면 195개 모두 통과합니다. 테스트 실패를 숨기거나 건너뛴 것이 아니라, 실행 전 클래스 탐색 단계를 대체한 것입니다.

## 남은 lint 경고

| 검사 | 개수 | 파일 |
|---|---:|---|
| DefaultLocale | 3 | Exporters.kt, MainActivity.kt |
| RedundantLabel | 1 | AndroidManifest.xml |
| DataExtractionRules | 1 | AndroidManifest.xml |
| MissingApplicationIcon | 1 | AndroidManifest.xml |
| UseKtx | 2 | SleepStageChart.kt |

전체 lint 보고서는 빌드 후 `app/build/reports/lint-results-debug.html`에서 확인합니다. 위 경고는 이번 저장·복원 통합에서 수정하지 않은 코드에 있습니다.

## 데이터 보존 및 한계

기존 프로필·설문·저장 결과·수면 원본·모델 입력 기록·합친 수면 CSV 총 6개 로컬 파일이 검증 전후 바이트 단위로 동일했습니다. 원본 기록과 사적인 검증 증거는 이 저장소에 포함하지 않습니다. UI 테스트의 예시 데이터는 격리된 메모리 또는 테스트 디렉터리에서만 사용합니다.

실물 삼성 계정과 Galaxy Watch의 신규 데이터 수신, 제조사 배터리 제한 상태의 장시간 동기화, 예측의 통계적 정확도는 이번 테스트 통과로 검증되지 않습니다. 모델 가중치와 기존 점진 개인화 정책은 통합 과정에서 변경하지 않았습니다.
