# AI R Voice 프로젝트 핸드오프

- 갱신 일시: `2026-08-13 KST`
- 제품명: `AI R Voice`
- Android release package: `com.coreline.ai.voice`
- 브랜치: `main`
- 작업 시작 커밋: `582175a5d801c0a68774461da88ede4503e317ad`
- 현재 원격: `https://github.com/coreline-ai/voice-tracker-find.git`
- 최신 반영 커밋: `90b7ba0 docs: refresh GitHub README presentation`
- 현재 상태: 리브랜딩 구현·자동 검증·Android 앱/Receiver/Web Console 연결 검증·main push 완료. 외부 운영 gate 대기

## 1. 구현 상태

Android, 웹 콘솔, Python 배포 패키지와 운영 스크립트의 활성 제품 식별자를 새 체계로
전환했다. 녹음·STT·Gemma·OAuth cloud-first/local-fallback 동작과 독립 OAuth LLM SDK
artifact 계약은 재설계하지 않았다.

| 영역 | 현재 정본 |
|---|---|
| Android release/debug/QA/deviceTest | `com.coreline.ai.voice` / `.debug` / `.qa` / `.deviceTest` |
| Android source root | `com.coreline.ai.voice` |
| cloud/on-device/LiteRT package | `.cloudsummary` / `.ondevice` / `.ondevice.summary.litert` |
| 앱 표시명 | `AI R Voice` |
| Android DB/DataStore/scheme | `airvoice.db` / `airvoice_settings` / `airvoice` |
| 웹 | `AI R Voice Console`, `AI R Voice · LAN Console` |
| Python distribution/import | `ai-r-voice` / `airvoice` |
| Python runtime/vault | `~/.airvoice` / `~/ai-r-voice-vault` |
| APK/release zip | `ai-r-voice.apk` / `ai-r-voice-release.zip` |

현재 판단은 **리브랜딩 소스 구현과 자동 회귀 검증 완료**다. 아래 항목은 완료에 포함하지
않는다.

- Anthropic/Codex/xAI 실계정 OAuth E2E
- Provider 승인 production registration 전환
- release APK 정식 서명과 스토어 배포
- 승인된 Android 디바이스의 설치·재설치 지속성·실기기 E2E smoke
- Room instrumentation migration 1→10 재실행
- Cloud/GCP staging E2E
- GitHub 저장소 rename과 fresh clone 검증

## 2. 새 환경에서 시작하기

현재 clone URL은 위 원격 주소다. GitHub 저장소 rename을 수행하기 전에도 기존 checkout과
fresh clone은 이 주소를 사용한다. 기존 checkout을 인계받은 경우 다음 순서로 시작한다.

```bash
git switch main
git status --short
git pull --ff-only origin main

cd android-app
source scripts/resolve_java_home.sh
airvoice_require_java21
./gradlew --no-daemon \
  :feature-cloud-summary:testDebugUnitTest \
  :feature-ondevice:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:verifyVariantTransportPolicy \
  :app:assembleDevicePreview
```

Python:

```bash
uv sync --extra dev --extra server --extra tls
uv run pytest --tb=short -q
uv run ruff check .
uv build
python -m airvoice.main --help
```

호환 기준:

| 항목 | 버전 |
|---|---|
| Kotlin | 1.9.24 |
| AGP | 8.9.1 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Java/JVM target | 17 |
| Coroutines | 1.9.0 |
| OkHttp | 4.12.0 |
| AppAuth | 0.11.1 |

`android-app/local.properties`는 로컬 Android SDK 경로와 선택적 override 전용이며 Git에
포함하지 않는다. 공개 OAuth client/model 기본값은 추적되는
`android-app/oauth-llm.defaults.properties`에서 주입된다. client secret은 APK에 넣지 않는다.

## 3. 핵심 구조

| 경로 | 역할 |
|---|---|
| `android-app/app` | Compose 앱, 녹음, 설정과 OAuth 계정 화면, DI 조립 |
| `android-app/feature-cloud-summary` | OAuth SDK adapter, prompt/parser, typed 실패 변환 |
| `android-app/feature-ondevice` | SenseVoice/Gemma, Room, 장시간 checkpoint와 fallback |
| `android-app/litert-bridge` | LiteRT 연동 경계 |
| `android-app/local-maven` | 독립 proprietary OAuth LLM SDK 0.1.0 Maven artifact |
| `src/airvoice` | Python pipeline, receiver, cloud API와 안전한 legacy migration |
| `web/dashboard` | LAN receiver 웹 콘솔과 sessionStorage migration |
| `scripts/verify_rebrand.py` | 활성 영역 identity 및 legacy literal allowlist gate |
| `dev-plan/implement_20260813_205318.md` | 전체 리브랜딩 계획과 실제 진행 상태 |
| `docs/qa/ai-r-voice-rebrand-implementation-20260813.md` | 이번 구현·검증 증적 |

OAuth SDK 좌표는 변경하지 않았다.

```text
ai.coreline.oauthllm:oauth-llm-api:0.1.0
ai.coreline.oauthllm:oauth-llm-android:0.1.0
```

## 4. 데이터와 호환 정책

### Android

- 새 application ID는 신규 앱 sandbox를 사용한다.
- 이전 application ID의 private data를 자동 복제하거나 이전 앱을 자동 삭제하지 않는다.
- 새 `.qa` package 안에서는 `adb install -r`에 의한 녹음·설정·모델 지속성을 검증해야 한다.
- Room table/column과 on-device migration 1→10 계약은 유지했고 schema class 경로만 새 package로
  이동했다.

### Python

- 기본 경로는 `~/.airvoice`, `~/ai-r-voice-vault`다.
- `python -m airvoice.legacy_migration`은 기본 dry-run이며 `--apply`를 명시해야 복사한다.
- legacy source는 삭제하지 않으며, target이 이미 차 있으면 merge/overwrite하지 않고 실패한다.
- 기존 환경변수로 명시적 경로를 준 환경은 강제 이동하지 않는다.

### 웹

- canonical sessionStorage key는 `airvoice-receiver-dashboard-token`이다.
- 새 key가 없고 legacy key만 있을 때만 1회 복사한다.
- 새 key가 있으면 legacy 값으로 덮어쓰지 않으며 clear 시 두 key를 모두 제거한다.
- token은 DOM, console, server log, 오류 본문에 출력하지 않는다.

## 5. OAuth 클라우드 요약 경계

```text
활성 OAuth profile 있음
  -> 선택 Provider에 제한된 전사 텍스트만 전송
  -> 성공: provider/model/request ID/latency/usage 저장
  -> fallbackEligible 실패: Gemma를 정확히 한 번 실행
  -> UserCancelled/InvalidRequest: 자동 Gemma fallback 없음

활성 profile 없음
  -> 로컬 Gemma 경로
```

- Provider A 실패 후 Provider B를 자동 호출하지 않는다.
- 오디오 원본은 OAuth SDK나 Provider에 전달하지 않는다.
- token, authorization code, PKCE verifier, cookie, raw Provider 오류 본문을 UI·로그·DB에
  노출하지 않는다.
- 현재 public client ID와 model 정본은 `android-app/oauth-llm.defaults.properties`에 있다.
- 실계정 E2E는 `executed=false`, `DEFERRED_BY_OWNER`다.

## 6. 2026-08-13 자동 검증 결과

### Python/Web

```text
pytest: 645 passed, 14 skipped, 4 deselected
ruff: All checks passed
uv lock --check: pass
uv build: pass
wheel zip-import + `python -m airvoice.main --help`: pass
```

### Android

다음 작업을 재검증 build에서 실행했다.

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
```

Gradle gate에서 OAuth SDK checksum, license asset, tracked OAuth defaults, cloud/on-device package
boundary와 native/network boundary가 함께 검증됐다. third-party LiteRT Kotlin metadata와 기존 API
deprecation warning은 출력됐지만 빌드는 성공했다.

### 산출물

| Artifact | Package / 상태 | SHA-256 |
|---|---|---|
| `ai-r-voice.apk` | QA `.qa`, debug signed | `2cbfeb540ea71b86e64a26e0decd48814963cfa76ef042c053b5691b561c848c` |
| `app-debug.apk` | `.debug`, debug signed | `72c25185cb2fcad2756c7f55015051e8201d0fb6cb7475237921a85db9496b5b` |
| `app-deviceTest.apk` | `.deviceTest`, debug signed | `5ef73a1dd15137df7b2cf0f1516000052cd1f7e9b66ae6b8f75f3b49c588eb2a` |
| `app-release-unsigned.apk` | release, unsigned | `f5820f357d89a796b957af558b809115b4ba076d71c6d50c153b4fcb65d6859b` |
| `ai_r_voice-0.1.0-py3-none-any.whl` | Python wheel | `d52304a3592c25e942b475d692a715ed650f9c45ee1acb9eb1a5450284d50df8` |
| `ai_r_voice-0.1.0.tar.gz` | Python sdist | `abf0bcbb0a0934ba5b24d7a1f98420a78fe7ce5e830bc5da17a92c94bb15561f` |
| `ai-r-voice-release.zip` | allowlisted source + QA APK | `4f2c78b954a095a66e0d8341425b7fae7ec845468d643f821107c890337c8eea` |

`aapt` 기준 네 APK는 모두 compile/target SDK 35, min SDK 26, label `AI R Voice`, launcher
`com.coreline.ai.voice.MainActivity`다. APK DEX/resource/entry와 wheel package/identifier scan,
구체 secret marker scan 및 legacy allowlist 검사를 통과했다. release zip은
`scripts/make_release.py`로 재현하며 내부 `RELEASE-MANIFEST.sha256`과 민감 파일명 gate를 둔다.

## 7. 실기기 결과

연결된 Android 디바이스에서 사용자의 명시적 요청에 따라 QA APK를 수동 update-install하고
cold start, 메인 UI, Receiver 연결을 검증했다. `adb reverse tcp:8765`로 임시 local Receiver에
연결했으며 UI는 `서버와 안전하게 연결되었습니다`를 표시했다. OAuth 계정 UI
(Anthropic/Codex/xAI)도 렌더링을 확인했다.

실제 browser 새 세션에서도 `AI R Voice · LAN Console` title, token 인증 후 `정상 연결`과
`정상 운영` 상태, console error/warning 0건을 확인했다. API는 인증 없이는 401, 임시 token으로
200이며 응답에는 token/절대 로컬 경로가 포함되지 않았다. 테스트 token은 세션에서 제거하고
임시 Receiver를 종료한다.

승인된 Android 디바이스의 재설치 지속성·실기기 E2E와 Room instrumentation은 추가 검증
예정이다. 기기 격리 보호를 강제하는 다음 스크립트가 정식 Android device gate다.

실행 재개 시:

```bash
cd android-app
./scripts/install_samsung_preview.sh
./scripts/run_device_qa.sh --case core
```

스크립트 자체가 승인된 기기 확인과 `.qa`/`.deviceTest` package 격리를 강제한다.

## 8. 다음 작업 순서

1. 승인된 Android 디바이스에서 Phase 9 smoke와 Room migration instrumentation을 실행한다.
2. `docs/qa/oauth-llm-e2e-runbook.md`로 사용자 승인 시에만 Provider별 실계정 E2E를 실행한다.
3. production OAuth registration과 release signing을 준비한다.
4. GitHub 저장소를 `coreline-ai/ai-r-voice`로 rename하고 origin/fresh clone을 검증한다.
5. GitHub 저장소 rename 여부를 결정한 뒤 origin/fresh clone을 검증한다.

PowerShell은 현재 macOS 환경에 `pwsh`가 없어 parser/Pester를 실행하지 못했다. shell script는
`bash -n`을 통과했고 Windows script는 새 module/path/task 이름으로 정적 갱신했다.

## 9. 보안 주의사항

- `local.properties`, `.env`, signing credential, DB, 녹음, token, cert를 commit하지 않는다.
- client secret, access/refresh token, authorization code, PKCE verifier, raw Provider body를 source,
  Gradle 설정, 로그, 스크린샷, fixture에 추가하지 않는다.
- public client ID는 비밀이 아니지만 registration 소유권이나 production 승인을 의미하지 않는다.
- SDK artifact 갱신은 version/checksum/POM/license/API 호환성을 한 변경으로 검증한다.
- 실계정·서명·Android 실기기·GitHub rename gate를 자동 테스트 성공과 혼동해 완료로 표시하지 않는다.
