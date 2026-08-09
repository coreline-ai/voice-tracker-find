# ThinkTank 프로젝트 핸드오프

- 작성 기준: `2026-08-10 KST`
- 저장소: `https://github.com/coreline-ai/voice-tracker-find.git`
- 브랜치: `main`
- 구현 기준 커밋: `34d0b4c` (`chore: track public OAuth compatibility defaults`)

## 1. 현재 상태

현재 코드는 **프로덕션급 구현 기준선**이다. 독립 OAuth LLM SDK artifact 소비, OAuth 계정
설정 UI, 클라우드 우선·Gemma 폴백, Room provenance, 장시간 로컬 처리 기반과 자동 검증이
구현되어 있다.

다만 다음 항목은 구현 완료 주장에 포함하지 않는다.

- Anthropic/Codex/xAI 실계정 로그인·refresh·generate·logout E2E
- Provider 승인 ThinkTank 소유 OAuth registration 교체
- release APK 정식 서명과 배포
- 30분·1시간·2시간 실음원 장시간 성능·열·배터리 검증
- Cloud worker/GCP staging 전체 E2E

즉, 상태는 **구현 완료 / 실계정 및 출시 운영 gate 대기**다.

## 2. 새 환경에서 시작하기

```bash
git clone https://github.com/coreline-ai/voice-tracker-find.git
cd voice-tracker-find
git switch main
git pull --ff-only origin main

cd android-app
# JDK 17과 Android SDK 35가 필요하다.
# local.properties에는 이 환경의 sdk.dir만 설정한다.
./gradlew --no-configuration-cache --no-parallel \
  :feature-cloud-summary:testDebugUnitTest \
  :feature-ondevice:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug
```

호환 기준:

| 항목 | 버전 |
|---|---|
| Kotlin | 1.9.24 |
| AGP | 8.9.1 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Java/JVM | 17 |
| Coroutines | 1.9.0 |
| OkHttp | 4.12.0 |
| AppAuth | 0.11.1 |

`android-app/local.properties`는 Git에 포함하지 않는다. OAuth public client/model 기본값은
`android-app/oauth-llm.defaults.properties`에 추적되므로 새 clone에서도 계정 연결 버튼이
활성화된다. 환경별 override만 `local.properties`, 환경 변수 또는 Gradle property로 전달한다.

## 3. 핵심 구조

| 경로 | 역할 |
|---|---|
| `android-app/app` | Compose 앱, Activity Result, 설정/계정 화면, DI 조립 |
| `android-app/feature-cloud-summary` | OAuth SDK adapter, 구조화 prompt/parser, typed 실패 변환 |
| `android-app/feature-ondevice` | SenseVoice/Gemma 실행, cloud-first 정책, Room, 장시간 checkpoint |
| `android-app/litert-bridge` | LiteRT 연동 경계 |
| `android-app/local-maven` | 독립 proprietary OAuth LLM SDK 0.1.0 Maven artifact |
| `src/thinktank` | Python 서버·receiver·cloud API |
| `dev-plan` | 구현 단계와 미실행 gate 기록 |
| `docs/qa` | 검증 보고서와 실계정 E2E 런북 |

OAuth SDK 좌표:

```text
ai.coreline.oauthllm:oauth-llm-api:0.1.0
ai.coreline.oauthllm:oauth-llm-android:0.1.0
```

ThinkTank는 `android-app/local-maven`의 artifact만 소비하며 독립 SDK source project에 Gradle
의존하지 않는다.

## 4. OAuth 클라우드 요약 동작

```text
활성 OAuth profile 있음
  → 선택 Provider에 제한된 전사 텍스트만 전송
  → 성공: Provider/model/request ID/latency/usage 저장
  → fallbackEligible 실패: Gemma를 정확히 한 번 실행
  → UserCancelled/InvalidRequest: 자동 Gemma 폴백 없음

활성 profile 없음
  → 기존 로컬 Gemma 경로
```

- Provider A 실패 후 Provider B로 자동 전환하지 않는다.
- 오디오 원본은 OAuth SDK나 Provider에 전달하지 않는다.
- token, authorization code, PKCE verifier, cookie, raw Provider 오류 본문을 UI·로그·DB에
  노출하지 않는다.
- OAuth 우선 라우팅은 현재 immediate/manual summary 진입점에 적용된다.
- 장시간 background 계층형 요약은 기존 Gemma checkpoint 경로를 유지한다.

## 5. 현재 public OAuth 호환 설정

정본은 `android-app/oauth-llm.defaults.properties`다.

| Provider | Public client ID | 기본 model |
|---|---|---|
| Anthropic | `9d1c250a-e61b-44d9-88ed-5944d1962f5e` | `claude-haiku-4-5` |
| Codex | `app_EMoamEEZ73f0CkXaXp7hrann` | `gpt-5.6-luna` |
| xAI | `b1a00492-073a-47ea-816f-4c329264a828` | `grok-4.5` |

이 값은 비밀 credential이 아니라 source-controlled compatibility registration이다. 그러나
public client ID라는 사실이 registration 소유권이나 production 사용 승인을 의미하지 않는다.
정식 배포 전 Provider-approved ThinkTank registration과 callback/scopes를 다시 확인한다.
Client secret은 APK에 포함하지 않는다.

고정 loopback callback:

| Provider | Callback |
|---|---|
| Anthropic | `http://localhost:54545/callback` |
| Codex | `http://localhost:1455/auth/callback` |
| xAI | `http://127.0.0.1:56121/callback` |

## 6. 검증 기준선

### 2026-08-10 smoke verification

다음 작업을 현재 `main`에서 다시 실행했다.

```text
:feature-cloud-summary:testDebugUnitTest
:feature-ondevice:testDebugUnitTest
:app:testDebugUnitTest
:app:assembleDebug

BUILD SUCCESSFUL in 27s
172 actionable tasks: 21 executed, 151 up-to-date
```

이 과정에서 다음 gate도 통과했다.

- OAuth SDK artifact checksum
- cloud/on-device 모듈 경계
- source-controlled public OAuth default와 confidential field 부재
- on-device network/native artifact 경계
- SDK NOTICE/license asset

### 2026-08-02 전체 검증 기준선

| 항목 | 결과 |
|---|---|
| app unit tests | 47/47 pass |
| feature-ondevice unit tests | 94/94 pass |
| feature-cloud-summary unit tests | 5/5 pass |
| lint | 0 errors |
| clean debug/release build | pass |
| Room 9→10 migration | Samsung SM-S931N 7/7, PD20 7/7 pass |
| 실계정 E2E | `executed=false` |

현재 로컬 산출물:

| Artifact | SHA-256 | 상태 |
|---|---|---|
| `android-app/app/build/outputs/apk/debug/app-debug.apk` | `454e57e4cdf8ae537ab8b7e0b6d7ff3e57beaac26810c25b3483a3a2c1ac72b1` | debug signed |
| `android-app/app/build/outputs/apk/release/app-release-unsigned.apk` | `c3ce1e9020653321d84df68e75c624ce60fa9aa6a2567eb7ecd569dde994480a` | unsigned |

APK는 Git 추적 대상이 아니므로 다른 환경에서는 재빌드한다.

## 7. 남은 gate와 권장 진행 순서

### OAuth/출시 필수

1. Provider별 QA 계정과 승인된 public registration을 준비한다.
2. `docs/qa/oauth-llm-e2e-runbook.md`를 Anthropic/Codex/xAI별로 실행한다.
3. 재시작 후 profile 선택 복원, refresh rotation, reauth, disconnect를 확인한다.
4. 원격 성공/실패의 Provider/model/fallback 사유가 DB와 UI에 일치하는지 확인한다.
5. 실제 token/raw callback을 남기지 않은 sanitized evidence만 기록한다.
6. ThinkTank 소유 production registration으로 교체한다.
7. release signing configuration을 준비하고 signed release를 검증한다.

### 전체 제품 후속 gate

- Samsung 30분·1시간·2시간 STT→Gemma 풀런 및 중단·재개
- peak PSS, thermal, battery, working storage 측정
- 합성 TTS 품질 5/5 확보. 현재 `tts_05`는 안전 거절되지만 가용성은 4/5다.
- Cloud outbox/worker 실행 주체와 GCP staging E2E

위 항목은 현재 OAuth 통합 코드의 결함을 의미하지 않으며 실제 운영·출시 범위의 후속
검증 또는 별도 개발 항목이다.

## 8. 중요 문서

- `android-app/docs/oauth-cloud-summary.md`: 통합·빌드 설정
- `docs/qa/oauth-cloud-summary-implementation-20260802.md`: 구현/자동 검증 보고서
- `docs/qa/oauth-cloud-summary-handoff.md`: OAuth 범위 인수인계
- `docs/qa/oauth-llm-e2e-runbook.md`: 실계정 E2E와 증적 양식
- `dev-plan/implement_20260802_204022.md`: ThinkTank OAuth 통합 계획
- `dev-plan/implement_20260802_190531.md`: 독립 SDK와 소비 앱 전체 계획
- `dev-plan/implement_20260801_213348.md`: Gemma/TTS 품질 gate
- `dev-plan/implement_20260731_193413.md`: 장시간 처리와 Cloud 후속 gate

## 9. 작업 재개 시 주의사항

- `main`에서 시작하기 전에 `git status`와 `git pull --ff-only origin main`을 확인한다.
- `android-app/local.properties`와 signing credential을 커밋하지 않는다.
- client secret, access/refresh token, authorization code, PKCE verifier, raw Provider body를
  source, Gradle 설정, 로그, 스크린샷, 테스트 fixture에 추가하지 않는다.
- SDK artifact 갱신 시 version/checksum/POM/license/API compatibility를 한 변경으로 검증한다.
- 실계정 실행 전까지 E2E를 완료로 표시하지 않는다.
- 기존 `feature-ondevice` 네트워크 금지 경계와 로컬 전용 동작을 유지한다.

## 10. 완료 판단

- **현재 판단:** 프로덕션급 구현 기준선 완료
- **아직 아님:** 실계정 검증 완료, production OAuth 승인 완료, signed release 배포 완료
- **다음 담당자가 가장 먼저 할 일:** `docs/qa/oauth-llm-e2e-runbook.md`를 읽고 Provider별 QA
  registration/계정을 준비한 뒤 실계정 E2E를 실행한다.
