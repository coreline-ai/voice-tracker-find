# OAuth cloud summary implementation verification — 2026-08-02

## 구현 상태

- 독립 SDK 소비: `ai.coreline.oauthllm:oauth-llm-android:0.1.0`, local Maven artifact only
- cloud module: implemented
- OAuth account settings UI: implemented
- OAuth-first/Gemma fallback router: implemented for immediate and manual summary entry
- Room provenance schema: version 10 implemented and migrated on two physical devices
- real-account E2E: `executed=false`
- real-account E2E status: `DEFERRED_BY_OWNER`

최초 clean 검증 빌드에는 Anthropic/Codex/xAI client ID와 model ID가 설정되지 않았다.
이후 삼성폰의 로컬 검증 빌드에는 읽기 전용 호환성 참조에서 확인한 public client ID와
단일 model ID를 ignored `android-app/local.properties`로 주입했다. 값은 저장소에 커밋하지
않으며 client secret은 사용하지 않는다. 계정 연결 버튼 활성화와 삼성폰 설치·화면 표시까지만
확인했고 실제 로그인/generate는 실행하지 않았다.

프로젝트 구현 기준선은 정리 완료 상태다. 실계정 단계 재개 시
`oauth-cloud-summary-handoff.md`와 `oauth-llm-e2e-runbook.md`를 순서대로 따른다.

## 자동 검증

### Clean build/test/lint

```text
./gradlew --no-configuration-cache --no-parallel clean
  :feature-cloud-summary:testDebugUnitTest
  :feature-ondevice:testDebugUnitTest
  :app:testDebugUnitTest
  :feature-cloud-summary:lintDebug
  :feature-ondevice:lintDebug
  :app:lintDebug
  :app:assembleDebug
  :app:assembleRelease
  :feature-ondevice:compileDebugAndroidTestKotlin

BUILD SUCCESSFUL in 4m 31s
```

| 모듈 | unit tests | 실패 | lint errors | lint warnings |
|---|---:|---:|---:|---:|
| app | 47 | 0 | 0 | 60 |
| feature-ondevice | 94 | 0 | 0 | 14 |
| feature-cloud-summary | 5 | 0 | 0 | 3 |

### Physical-device migration test

`OnDeviceDatabaseMigrationTest`만 격리 실행했다.

| Device | Android test | 결과 |
|---|---:|---|
| Samsung SM-S931N | 7 | pass |
| PD20 | 7 | pass |

두 기기에서 Room 9→10의 Provider/request/usage provenance column migration을 포함해 모두
통과했다. 실제 OAuth 계정 테스트는 이 migration test에 포함되지 않는다.

### APK

| Artifact | 크기 | SHA-256 | 서명 |
|---|---:|---|---|
| `app-debug.apk` | 약 70 MB | `454e57e4cdf8ae537ab8b7e0b6d7ff3e57beaac26810c25b3483a3a2c1ac72b1` | debug v2, 1 signer |
| `app-release-unsigned.apk` | 약 51 MB | `c3ce1e9020653321d84df68e75c624ce60fa9aa6a2567eb7ecd569dde994480a` | unsigned |

Debug package는 `com.thinktank.recorder.next.debug`, minSdk 26, target/compileSdk 35,
`arm64-v8a`다. release signing property가 없으므로 release APK는 의도대로 unsigned다.

public registration을 주입한 최종 Debug APK는 `:app:assembleDebug`와 SDK checksum/경계/NOTICE
gate를 통과했고 Samsung SM-S931N에 업데이트 설치했다. 화면 자동화로 Anthropic/Codex/xAI
연결 버튼 3개가 모두 `enabled=true`임을 확인했다.

## 경계·보안 검증

- SDK AAR/JAR/POM hard-coded SHA-256 gate 통과
- `ai.coreline.oauthllm`은 exclusive local Maven repository에서만 resolve
- SDK sibling source Gradle path 참조 검색 결과 0건
- `feature-cloud-summary`의 on-device 내부 구현 import 0건; 공개 `api`만 사용
- consumer Kotlin source의 client secret/token/code/verifier 보유 식별자 0건
- SDK 공개 registration에는 client ID만 존재하며 consumer BuildConfig에 secret field가 없음
- SDK AAR와 `feature-cloud-summary`에 native library 0개
- APK native library는 기존 graphics/datastore/LiteRT/SenseVoice/ONNX arm64 payload만 존재
- SDK LICENSE/NOTICE/THIRD_PARTY_NOTICES/PROVENANCE가 release APK assets에 포함됨

AppAuth 자체에는 범용 `ClientSecret*` 클래스/문자열이 포함되지만 ThinkTank와 SDK public
configuration은 client secret 값을 입력·읽기·저장하는 표면을 만들지 않는다. 문자열 이름의
존재를 실제 secret 값 포함으로 잘못 판정하지 않고, source/configuration surface와 주입 경로를
검사했다.

## 알려진 경고와 미실행 항목

- LiteRT LM 0.14.0 Kotlin metadata 2.3.0과 현재 lint/R8 reader의 metadata 2.1.0 경고가 남아
  있다. 기존 경고이며 test/lint/build는 성공했다.
- Gradle daemon metaspace 재시작 경고와 기존 Compose `ClickableText` deprecation 경고가 있다.
- 실제 Anthropic/Codex/xAI 로그인, refresh, generate, logout은 실행하지 않았다.
- release APK 서명/배포와 실제 계정 E2E는 별도 자격 증명과 signing config가 필요하다.
- 장시간 background 계층형 pipeline 자체는 기존 Gemma checkpoint 경로를 유지한다. OAuth
  우선 라우터는 현재 immediate/manual summary 진입점에 적용되어 있다.

## 인수인계 상태

- 구현·자동 검증: 완료
- 실계정 E2E: 사용자 결정으로 보류, 완료 주장하지 않음
- 안전한 설정 예시: `android-app/oauth-llm.properties.example`
- 재개 체크리스트: `docs/qa/oauth-cloud-summary-handoff.md`
- 실계정 실행·증적 양식: `docs/qa/oauth-llm-e2e-runbook.md`
