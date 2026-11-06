# 통합 v8 검증 안내

검증일: 2026-11-06. 통합 당시 Android API 37 에뮬레이터에서 32개 회귀 테스트 클래스의 **182개 테스트가 통과**했습니다. 수동 실행 전용 `BackgroundJobLifecycleProbe`는 이 집계에서 제외했습니다. 별도 JVM 테스트 소스는 없으므로 `testDebugUnitTest`는 `NO-SOURCE`입니다.

## 빌드

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

캐시가 준비된 검증 환경에서는 `--offline`을 사용했습니다. 최초 설정에서는 SDK와 Gradle 의존성 다운로드가 필요합니다. 마지막 lint는 `--rerun-tasks --no-build-cache`로 다시 실행했으며 오류 0개, 경고 8개, 힌트 9개였습니다.

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
