# AI R Voice 리브랜딩 구현·검증 보고서

- 실행일: `2026-08-13 KST`
- 계획: `dev-plan/implement_20260813_205318.md`
- 시작 커밋: `582175a5d801c0a68774461da88ede4503e317ad`
- 브랜치: `main`
- 시작 원격: `https://github.com/coreline-ai/voice-tracker-find.git`
- 결과: 소스 구현과 현재 환경의 자동 검증 완료, 외부·실기기 gate는 분리

## 1. 적용 범위

- Android application ID, namespace, Kotlin/Java/AIDL/test source tree, Room schema 경로 전환
- 앱 표시명, Application/App/Theme/Database class, DB/DataStore/deep link, launcher asset 전환
- OAuth build/property key와 cloud summary schema 전환
- 웹 title/wordmark/footer 및 안전한 sessionStorage key migration
- Python distribution/import/runtime path와 비파괴 legacy home migration
- Windows/Samsung/release script의 module, package, task, APK/zip 이름 전환
- QA/deviceTest 전용 manifest의 local HTTP Receiver 정책 분리 및 release 차단 gate 추가
- 웹 대시보드 public favicon 추가(브라우저의 인증되지 않은 `/favicon.ico` 요청 오류 제거)
- 현재 README, SETUP, DISTRIBUTE, HANDOFF와 current 설계 문서 전환
- 활성 source/artifact legacy literal allowlist gate 추가

녹음·STT·Gemma·OAuth 라우팅과 독립 OAuth LLM SDK artifact 좌표/바이너리는 변경하지 않았다.

## 2. 최종 identity

| 항목 | 값 |
|---|---|
| 제품명 | `AI R Voice` |
| Web | `AI R Voice Console`, `AI R Voice · LAN Console` |
| release | `com.coreline.ai.voice` |
| debug | `com.coreline.ai.voice.debug` |
| QA | `com.coreline.ai.voice.qa` |
| deviceTest | `com.coreline.ai.voice.deviceTest` |
| Python | distribution `ai-r-voice`, import `airvoice` |
| runtime/vault | `~/.airvoice`, `~/ai-r-voice-vault` |
| APK/zip | `ai-r-voice.apk`, `ai-r-voice-release.zip` |

## 3. 기준선

변경 직전 동일 checkout에서 확인한 기준선:

- Android unit/lint/debug build: `BUILD SUCCESSFUL in 27s`, 240 tasks
- Python: `634 passed, 14 skipped, 4 deselected`, Ruff pass
- 웹/receiver/OpenAPI test는 Python suite에 포함

기준선 실행 시 Gradle cache와 localhost socket 접근은 샌드박스에서 제한되어, 같은 명령을
허용된 로컬 환경에서 다시 실행해 통과시켰다. 제품 결함으로 분류하지 않았다.

## 4. 최종 자동 검증

### Python/Web

```bash
./.venv/bin/python -m pytest --tb=short -q
./.venv/bin/ruff check .
uv lock --check
uv build
```

결과:

```text
645 passed, 14 skipped, 4 deselected
All checks passed!
Successfully built dist/ai_r_voice-0.1.0.tar.gz
Successfully built dist/ai_r_voice-0.1.0-py3-none-any.whl
```

wheel을 `/tmp`에서 zip path로 먼저 로드해 `import airvoice`가 wheel 내부 파일을 가리키는지
확인했고 `python -m airvoice.main --help`가 `AI R Voice 배치 파이프라인 실행`을 출력했다.

추가 회귀:

- legacy PC migration: dry-run, apply, target conflict, missing source 4 cases
- web token storage: 신규/legacy-only/new+legacy 3 cases
- receiver dashboard/static/OpenAPI targeted tests
- allowlisted deterministic release zip content/manifest test

### Android

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew --no-configuration-cache --no-parallel clean \
  :feature-cloud-summary:testDebugUnitTest \
  :feature-ondevice:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDevicePreview \
  :app:assembleDeviceTest \
  :app:assembleRelease
```

결과:

```text
BUILD SUCCESSFUL in 1m 56s
492 actionable tasks: 296 executed, 170 from cache, 26 up-to-date
```

같은 build에서 다음 Gradle gate도 통과했다.

- `verifyOAuthLlmArtifacts`
- `verifyTrackedOAuthDefaults`
- `verifyCloudSummaryBoundary`
- `verifyOnDeviceNativeArtifacts`
- `verifyOnDeviceNetworkBoundary`
- `verifyOAuthLlmNotices`
- `verifyBundledFontLicenses`
- `verifyRasterAssets`

third-party `litertlm-android 0.14.0` Kotlin metadata/R8 warning과 기존 deprecation warning은
출력됐지만 lint, shrink와 release package가 모두 성공했다.

### 2026-08-13 재검증

리브랜딩 잔여 항목을 다시 검색하는 과정에서 `devicePreview`/`deviceTest`가 custom build type이라
`src/debug`의 cleartext 정책을 자동 상속하지 않는 문제를 발견해 수정했다. 두 QA 계열에는
전용 manifest와 network security config를 두고, release는 cleartext를 계속 금지한다.
`AIRVOICE_DEBUG_SERVER_URL`의 이전 DHCP IP fallback도 제거했다.

```text
:feature-cloud-summary:testDebugUnitTest
:feature-ondevice:testDebugUnitTest
:app:testDebugUnitTest
:app:lintDebug
:app:verifyVariantTransportPolicy
:app:assembleDevicePreview
:app:assembleDeviceTest
:app:assembleRelease

BUILD SUCCESSFUL in 31s
472 actionable tasks: 63 executed, 409 up-to-date

pytest: 645 passed, 14 skipped, 4 deselected
ruff: All checks passed
uv lock --check: pass
uv build: pass
scripts/verify_rebrand.py: pass
```

웹 대시보드도 새 browser session에서 title `AI R Voice · LAN Console`, token 입력 후
`정상 연결`, Receiver status `정상 운영`을 확인했고 browser console은 error/warning 0건이었다.
대시보드 API는 무자격 401, 임시 token 인증 200이며 응답에 token/로컬 경로가 없음을 확인했다.

## 5. APK identity와 checksum

`aapt 35.0.0 dump badging` 결과:

| Artifact | application ID | version | label | SHA-256 |
|---|---|---|---|---|
| `ai-r-voice.apk` | `com.coreline.ai.voice.qa` | `1.0.0-preview` | `AI R Voice` | `2cbfeb540ea71b86e64a26e0decd48814963cfa76ef042c053b5691b561c848c` |
| `app-debug.apk` | `com.coreline.ai.voice.debug` | `1.0.0-debug` | `AI R Voice` | `72c25185cb2fcad2756c7f55015051e8201d0fb6cb7475237921a85db9496b5b` |
| `app-deviceTest.apk` | `com.coreline.ai.voice.deviceTest` | `1.0.0-device-test` | `AI R Voice` | `5ef73a1dd15137df7b2cf0f1516000052cd1f7e9b66ae6b8f75f3b49c588eb2a` |
| `app-release-unsigned.apk` | `com.coreline.ai.voice` | `1.0.0` | `AI R Voice` | `f5820f357d89a796b957af558b809115b4ba076d71c6d50c153b4fcb65d6859b` |

모두 min SDK 26, compile/target SDK 35, launcher
`com.coreline.ai.voice.MainActivity`로 확인했다. release는 **unsigned**이며 배포 완료로 분류하지
않는다.

Python artifact:

| Artifact | SHA-256 |
|---|---|
| `dist/ai_r_voice-0.1.0-py3-none-any.whl` | `d52304a3592c25e942b475d692a715ed650f9c45ee1acb9eb1a5450284d50df8` |
| `dist/ai_r_voice-0.1.0.tar.gz` | `abf0bcbb0a0934ba5b24d7a1f98420a78fe7ce5e830bc5da17a92c94bb15561f` |
| `dist/ai-r-voice-release.zip` | `4f2c78b954a095a66e0d8341425b7fae7ec845468d643f821107c890337c8eea` |

## 6. 보안·legacy·native 검사

```bash
./.venv/bin/python scripts/verify_rebrand.py
bash -n <all shell scripts>
apkanalyzer dex packages app-devicePreview.apk
aapt dump --values resources app-devicePreview.apk
unzip -Z1 app-devicePreview.apk
unzip -Z1 dist/ai_r_voice-0.1.0-py3-none-any.whl
./.venv/bin/python scripts/make_release.py
```

결과:

- 활성 source/current docs identity 및 legacy literal allowlist: pass
- APK DEX package/resource/entry의 허용되지 않은 과거 package/brand: 0
- wheel entry와 활성 identifier의 과거 Python package: 0
- APK private-key/client-secret/refresh-token concrete marker: 0
- hard-coded signing password: 0. Gradle에는 `AIRVOICE_*` environment/property lookup만 존재
- shell script syntax: pass
- release zip allowlist, internal checksum manifest, sensitive-entry와 legacy allowlist scan: pass
- PowerShell parse/Pester: `pwsh` 미설치로 미실행

QA APK의 native payload:

```text
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libdatastore_shared_counter.so
lib/arm64-v8a/liblitertlm_jni.so
lib/arm64-v8a/libonnxruntime.so
lib/arm64-v8a/libsherpa-onnx-jni.so
```

리브랜딩으로 신규 native payload를 추가하지 않았다. 독립 SDK AAR/POM/license/provenance와
checksum은 Gradle gate로 검증했고 SDK 좌표를 변경하지 않았다.

## 7. 데이터 migration 검증

### Python

`src/airvoice/legacy_migration.py`는 다음 정책을 테스트했다.

- 기본 dry-run
- 명시적 `--apply`에서 staging copy 후 atomic target 생성
- legacy source는 삭제하지 않음
- 새 target이 있으면 merge/overwrite 거부
- legacy source가 없으면 안전한 no-op

### Android

새 application ID는 신규 sandbox다. 이전 앱 private data를 자동 복사하거나 이전 앱을 자동
삭제하지 않는다. Room schema file 1 및 1→10은 새 database class 경로로 이동했고 SQL/table/
column을 재작성하지 않았다.

Room instrumentation과 `.qa` update-install 지속성은 Samsung 단말이 없어 이번 실행에서
재검증하지 않았다.

## 8. 실기기·외부 gate

```text
adb devices -l
0123456789ABCDEF device product:full_tb8788p1_64_wifi_k419 model:PD20
```

PD20은 Samsung이 아니지만, 이번에는 사용자의 명시적 요청에 따라 **수동 QA 설치·실행**만
진행했다. Samsung 전용 스크립트의 제조사/격리 보호를 우회해 일반 설치 경로를 제품 gate로
대체한 것은 아니다.

| 검증 | 결과 |
|---|---|
| `ai-r-voice.apk` update install | PASS (`com.coreline.ai.voice.qa`, `AI R Voice`, minSdk 26/targetSdk 35) |
| cold start | PASS (3.309초, `MainActivity`) |
| 메인 UI | PASS (`기록 준비`, 하단 녹음/노트/설정/로컬 AI) |
| Receiver 연결 확인 | PASS (`adb reverse` 경유, UI `서버와 안전하게 연결되었습니다`) |
| OAuth settings surface | PASS (Anthropic/Codex/xAI 계정 영역 렌더링) |
| runtime crash scan | PASS (`FATAL EXCEPTION` 없음) |

PD20에는 사용 가능한 마이크 입력이 없어 실제 녹음/전사 파이프라인은 실행하지 못했다. 테스트에
사용한 Receiver token은 앱/브라우저 세션에서 제거하고 임시 Receiver를 종료한다.

다음 항목은 여전히 미실행이다.

| Gate | 상태 | 이유 |
|---|---|---|
| Samsung `.qa` 설치/launcher/cold start | NOT_EXECUTED | Samsung 단말 미연결 |
| 녹음/재생/notes/local AI | NOT_EXECUTED | PD20 마이크 입력 부재 및 Samsung 단말 미연결 |
| Samsung `.qa` update-install 지속성 | NOT_EXECUTED | Samsung 단말 미연결 |
| Room instrumentation migration | NOT_EXECUTED | Samsung 단말 미연결 |
| 실제 브라우저 dashboard UI | PASS | 새 browser session에서 인증 연결/상태/console 0 errors 확인 |
| 실계정 OAuth | `executed=false`, `DEFERRED_BY_OWNER` | 소유자 보류 |
| release signing | NOT_EXECUTED | credential 미제공 |
| GitHub rename/fresh clone | NOT_EXECUTED | 외부 저장소 변경 승인 범위 밖 |
| commit/push | NOT_EXECUTED | 현재 요청은 구현 프롬프트와 작업 정리 범위 |

## 9. 잔여 실행 순서

1. Samsung 단말 연결 후 `android-app/scripts/install_samsung_preview.sh` 실행
2. `android-app/scripts/run_device_qa.sh --case core`와 Room migration instrumentation 실행
3. 사용자 승인 시 `docs/qa/oauth-llm-e2e-runbook.md` 실행
4. production registration/release signing 준비
5. GitHub 저장소 rename 후 origin/fresh clone/build 검증
6. 최종 검토, commit, push

미실행 gate는 자동 검증 통과와 구분하며 완료로 오표기하지 않는다.
