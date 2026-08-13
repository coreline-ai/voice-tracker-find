# AI R Voice 전체 리브랜딩 구현 프롬프트

아래 내용을 새 Codex 작업의 첫 메시지로 그대로 전달한다. 이 프롬프트는 계획 재작성용이
아니라 실제 소스 변경·파일 이동·테스트·기기 검증·문서 정리까지 수행하기 위한 실행 지시다.

---

## 실행 프롬프트

당신은 기존 음성 기록 프로젝트를 새 제품 `AI R Voice`로 완전히 전환하는 책임 개발자다.
계획만 제안하거나 분석에서 멈추지 말고, 저장소의 실제 파일을 수정하고 검증 가능한 범위까지
끝까지 구현하라.

### 0. Goal 등록과 종료 규칙

작업 시작 즉시 다음 objective를 장기 Goal로 등록하고, 단순 답변 한 번으로 종료하지 말고 자동
후속 turn을 사용해 계속 진행한다.

```text
AI R Voice 전체 리브랜딩을 Android/Web/Python/운영 스크립트/현재 문서에 실제 구현하고,
현재 환경에서 가능한 clean test·lint·build·artifact·보안 검증과 HANDOFF까지 완료한다.
실계정, signing credential, Samsung 단말, GitHub 관리 권한이 필요한 외부 gate는 임의 실행하지
말고 NOT_EXECUTED 사유와 재개 명령을 남긴다.
```

- 분석·계획 작성만으로 Goal을 완료 처리하지 않는다.
- 구현 가능한 source/test/script/doc 변경과 자동 검증이 남아 있으면 계속 작업한다.
- Samsung 실기기, 실계정 OAuth, release signing, GitHub rename처럼 현재 환경이나 사용자 승인이
  필요한 항목은 안전한 데까지 준비하고 `NOT_EXECUTED`/`DEFERRED_BY_OWNER`로 분리한다.
- 외부 gate가 없다는 이유만으로 자동 검증 결과를 실패로 만들지는 않되, 실행하지 않은 일을
  성공으로 표시하지 않는다.
- 현재 환경에서 가능한 구현·재검증·QA 보고서·HANDOFF가 모두 끝났을 때만 Goal을 완료한다.

### 1. 작업 위치와 정본

- 저장소 작업 경로:
  `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22`
- 전체 구현 계획 정본:
  `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/dev-plan/implement_20260813_205318.md`
- 현재 상태 인수인계:
  `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/HANDOFF.md`
- 제품 개요:
  `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/README.md`
- OAuth 통합 계획:
  `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/dev-plan/implement_20260802_204022.md`
- 실계정 OAuth 런북:
  `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/docs/qa/oauth-llm-e2e-runbook.md`

가장 먼저 작업 디렉터리, 적용 가능한 `AGENTS.md`/지침 파일, Git branch/status/remote를 확인하라.
작업 트리에 기존 변경이 있으면 reset·restore·삭제하지 말고 보존한 상태로 충돌을 피하라. 작업
트리가 clean이고 `main`이 원격을 추적하는 경우에만 `git pull --ff-only origin main`으로 시작
기준을 맞춘다. 시작 commit SHA와 baseline 결과를 구현 계획 Phase 0에 기록하라.

### 2. 변경 정본

아래 값은 질문 없이 확정값으로 사용한다.

| 구분 | 확정값 |
|---|---|
| 제품 표시명 | `AI R Voice` |
| 웹 제품명 | `AI R Voice Console` |
| 웹 브라우저 제목 | `AI R Voice · LAN Console` |
| Android release ID | `com.coreline.ai.voice` |
| Android debug ID | `com.coreline.ai.voice.debug` |
| Android QA ID | `com.coreline.ai.voice.qa` |
| Android deviceTest ID | `com.coreline.ai.voice.deviceTest` |
| app package | `com.coreline.ai.voice` |
| cloud summary package | `com.coreline.ai.voice.cloudsummary` |
| on-device package | `com.coreline.ai.voice.ondevice` |
| LiteRT bridge package | `com.coreline.ai.voice.ondevice.summary.litert` |
| Android Gradle root | `ai-r-voice-android` |
| Kotlin/Java 클래스 접두어 | `AirVoice` |
| Android deep link scheme | `airvoice` |
| Android 주 DB 파일 | `airvoice.db` |
| Android DataStore | `airvoice_settings` |
| cloud JSON schema | `air_voice_summary_v1` |
| Python project name | `ai-r-voice` |
| Python import module | `airvoice` |
| Python 실행 | `python -m airvoice.main` |
| PC runtime home | `~/.airvoice` |
| 기본 vault | `~/ai-r-voice-vault` |
| scheduled task | `airvoice-nightly`, `airvoice-receiver` |
| APK/zip | `ai-r-voice.apk`, `ai-r-voice-release.zip` |
| 환경변수 prefix | `AIRVOICE_` |
| 웹 sessionStorage key | `airvoice-receiver-dashboard-token` |
| GitHub 저장소 제안명 | `coreline-ai/ai-r-voice` |

표시 문자열은 반드시 공백을 포함한 `AI R Voice`를 사용한다. 코드 identifier만 `AirVoice`,
`airvoice`, `ai-r-voice`를 용도에 맞게 사용한다.

### 3. 절대 준수 사항

1. 계획 파일의 Phase 0부터 Phase 10까지 순서대로 구현하고 각 체크박스를 실제 상태와 동기화한다.
2. 한 Phase의 자체 테스트가 실패한 채 다음 Phase 완료로 넘어가지 않는다.
3. 계획을 다시 작성하는 것으로 작업을 종료하지 말고 실제 package/source tree/file/class rename을
   수행한다.
4. package 선언뿐 아니라 Kotlin, Java, AIDL, test, androidTest의 실제 디렉터리도 `git mv`한다.
5. `build`, `.gradle`, generated output은 이동하지 말고 clean build로 재생성한다.
6. 기존 녹음·STT·Gemma·OAuth cloud-first/local-fallback 동작을 재설계하지 않는다.
7. `ai.coreline.oauthllm` Maven group/artifact와 SDK license/checksum/provenance는 변경하지 않는다.
8. OAuth token, authorization code, PKCE verifier, client secret, raw Provider body를 source, UI,
   로그, DB, screenshot, fixture에 추가하지 않는다.
9. 세 Provider public client ID는 비밀이 아니지만 Provider 승인 없이 실제 값 자체를 임의로
   교체하지 않는다. 설정 key만 `AIRVOICE_*` 정본으로 바꾼다.
10. Provider A 실패 후 Provider B 자동 전환을 새로 만들지 않는다.
11. 오디오 원본을 OAuth SDK나 Provider에 전달하지 않는다.
12. 실계정 OAuth는 사용자가 보류했으므로 실행하지 않았다면 반드시 `executed=false`,
    `DEFERRED_BY_OWNER`로 남긴다.
13. release signing credential이나 개인 `.env`, DB, 녹음, token, cert를 Git에 추가하지 않는다.
14. 과거 dev-plan, 날짜 고정 QA 증적, UIAutomator XML, provenance의 역사적 사실을 무차별 치환하지
    않는다.
15. 과거 명칭은 계획의 허용 목록과 격리된 migration compatibility 코드에만 남긴다.
16. 작업 중 발견한 기존 변경을 revert하거나 다른 사람의 작업을 덮어쓰지 않는다.

### 4. 데이터 정책

#### Android

- `com.coreline.ai.voice`는 신규 앱이다.
- 기존 `com.thinktank.recorder.next*`의 private app data를 자동 복제하지 않는다.
- 기존 package를 자동 uninstall하지 않는다.
- 새 `com.coreline.ai.voice.qa` 내부에서는 `adb install -r` 후 녹음·설정·다운로드 모델이
  유지되어야 한다.
- 기존 Room table/column과 on-device migration 1→10을 유지한다.
- Room schema history는 새 database class package 경로로 이동하되 삭제하거나 새 schema로
  덮어쓰지 않는다.

#### Python/PC

- canonical 경로는 `~/.airvoice`, `~/ai-r-voice-vault`다.
- legacy `~/.thinktank` 데이터가 있고 새 target이 비어 있을 때만 비파괴 migration 안내 또는
  명시적 helper를 제공한다.
- 새 target이 이미 있으면 자동 merge·overwrite하지 말고 충돌을 보고한다.
- migration 실패 시 source 데이터를 삭제하지 않는다.
- 기존 환경변수로 explicit path를 지정한 경우 강제 이동하지 않는다.
- legacy scheduled task를 자동 삭제하지 말고 새 task와 동시 실행 위험을 경고하는 unregister
  런북을 제공한다.

#### 웹

- canonical key는 `airvoice-receiver-dashboard-token`이다.
- 새 key가 없고 legacy key만 있을 때만 값을 1회 복사한다.
- 새 key가 있으면 legacy 값으로 덮어쓰지 않는다.
- token은 DOM, console, server log, error message에 출력하지 않는다.

### 5. 실제 구현 순서

계획 정본의 세부 체크리스트를 따르되 다음 실행 순서를 유지한다.

1. **Phase 0 — 기준선**
   - Git SHA, package/brand inventory, 현재 Android/Python/Web 테스트 결과를 기록한다.
   - 허용 목록 기반 legacy literal scan script를 준비한다.

2. **Phase 1 — Android compile identity**
   - Gradle root, application ID, 네 모듈 namespace를 변경한다.
   - main/test/androidTest Kotlin·Java·AIDL tree와 package/import를 원자적으로 이동한다.
   - AIDL Binder descriptor, Hilt/KSP/Room/ProGuard 경계를 확인한다.

3. **Phase 2 — Android runtime/brand**
   - `AirVoiceApplication`, `AirVoiceApp`, `AirVoiceTheme`, `AirVoiceDatabase`로 변경한다.
   - label, notification, manifest theme/application/deep link를 변경한다.
   - DB/DataStore/schema 경로와 launcher/adaptive/round icon을 변경한다.
   - 사용자가 보는 모든 앱 문자열과 현재 UI 테스트를 갱신한다.

4. **Phase 3 — OAuth/cloud summary**
   - `THINKTANK_*` build/property key를 `AIRVOICE_*`로 변경한다.
   - tracked public client/model defaults가 계속 주입되게 한다.
   - schema name, package-boundary 검사와 문서를 갱신한다.
   - SDK Maven 좌표, callback, redaction, fallback 정책은 그대로 보존한다.

5. **Phase 4 — Web**
   - title/header/aria/footer/visible brand와 icon을 변경한다.
   - sessionStorage migration을 구현하고 3가지 상태를 테스트한다.

6. **Phase 5 — Python**
   - `src/thinktank`를 `src/airvoice`로 이동한다.
   - pyproject/hatch/wheel/import/monkeypatch/migration/script/module 실행을 갱신한다.
   - `uv.lock`을 재생성하고 불필요한 dependency update가 없는지 확인한다.
   - runtime path와 안전한 legacy migration을 구현한다.

7. **Phase 6 — 운영/배포 script**
   - Windows task, receiver, pipeline, Samsung QA, APK/zip/release 이름을 갱신한다.
   - Cloud resource의 표시명/label만 점검하고 운영 리소스를 임의 삭제·재생성하지 않는다.

8. **Phase 7 — 현재 문서/법적 고지**
   - README, SETUP, DISTRIBUTE, HANDOFF, current OAuth/cloud/web 런북을 갱신한다.
   - 현재 앱 스크린샷을 새 build로 교체한다.
   - 과거 QA/계획/provenance 원본은 역사로 유지한다.

9. **Phase 8 — 전체 자동 검증**
   - clean Android/Python/Web test와 artifact/secret/native/license/legacy scan을 실행한다.
   - 실제 명령, 결과, artifact SHA-256을 새 QA 보고서에 기록한다.

10. **Phase 9 — 실기기/운영 smoke**
    - 연결된 Android 중 Samsung 단말만 식별해 새 `.qa` APK를 설치한다.
    - launcher, cold start, 녹음, 재생, receiver upload, notes, local AI, 재설치 지속성을 검증한다.
    - 실계정 OAuth는 사용자 credential 없이 실행하거나 성공으로 주장하지 않는다.

11. **Phase 10 — repository/handoff**
    - 권한이 있으면 GitHub 저장소명을 `ai-r-voice`로 바꾸고 origin/fresh clone을 검증한다.
    - 권한이나 네트워크가 없으면 미완료로 정확히 보고하고 실행 명령을 남긴다.
    - 최종 HANDOFF에 commit, checksum, 기기, 미실행 gate를 기록한다.
    - commit/push는 사용자의 현재 지시 또는 승인 범위 안에서만 수행한다.

### 6. 필수 검증 명령

환경에 맞게 JDK 17과 Android SDK 35를 사용한다. 명령이 실패하면 원인을 수정하고 동일 명령을
재실행한다. 현재 환경에서 불가능한 검증은 성공으로 표시하지 않는다.

#### Python

```bash
cd /Volumes/Eprojects/project_202607/thinktank-release-v0.1.22
uv sync --extra dev --extra server --extra tls
uv run pytest --tb=short -q
uv run ruff check .
uv build
```

생성된 wheel을 격리 환경에 설치해 아래를 확인한다.

```bash
python -c "import airvoice"
python -m airvoice.main --help
```

#### Android

```bash
cd /Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app
./gradlew --no-configuration-cache --no-parallel clean \
  :feature-cloud-summary:testDebugUnitTest \
  :feature-ondevice:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDevicePreview \
  :app:assembleRelease
```

instrumentation/Room migration task는 실제로 존재하는 variant/task를 먼저 확인한 뒤 Samsung
단말에서 실행한다. `adb devices -l` 결과 중 제조사/모델을 확인하지 않고 임의 기기에 설치하지
않는다.

#### Android artifact

- `apkanalyzer` 또는 `aapt dump badging`으로 package, version, launcher label을 확인한다.
- merged manifest로 Application/Activity/Service/provider authority/scheme을 확인한다.
- APK DEX/resources/assets/native payload를 검사한다.
- 새 debug/QA/release APK의 SHA-256을 기록한다.

#### Legacy/secret scan

- `build`, `.gradle`, `.venv`, cache, 과거 dev-plan/QA/provenance 허용 목록을 분리한다.
- 활성 source, current docs, HTML, manifest, DEX, wheel, release zip에서 허용되지 않은
  `ThinkTank`, `thinktank`, `com.thinktank`, `THINKTANK_`가 0건이어야 한다.
- broad exclude만 사용하지 말고 허용 파일과 literal을 명시한다.
- access/refresh token, client secret, signing password, authorization code, PKCE verifier,
  private key, raw Provider body pattern이 산출물에 없어야 한다.

### 7. 구현 중 보고 원칙

- 긴 작업은 Phase 단위로 현재 상태, 변경 파일, 통과/실패 테스트를 간결하게 보고한다.
- 테스트 실패를 숨기거나 이전 성공 결과로 대체하지 않는다.
- 파일 이동 후 compile error가 많아도 package rename을 되돌리지 말고 같은 Phase 안에서 수정한다.
- 현재 환경에 필요한 SDK/JDK/dependency가 없어 검증할 수 없으면 정확한 차단 원인과 다음 실행
  명령을 남긴다.
- 실제 계정, signing key, GitHub 관리 권한처럼 사용자만 제공할 수 있는 항목은 안전한 범위까지
  구현을 계속한 뒤 마지막에 별도 미완료 gate로 보고한다.

### 8. 완료 시 필수 산출물

1. 실제 변경된 Android/Web/Python/source/script/doc 파일
2. 체크박스와 이슈가 실제 상태로 갱신된
   `dev-plan/implement_20260813_205318.md`
3. 새 rebrand 구현·검증 QA 보고서
4. 새 `HANDOFF.md`
5. Android APK와 Python wheel/release zip의 package/version/SHA-256 목록
6. 실행한 테스트 명령과 결과 요약
7. 미실행 실계정 OAuth, release signing, 외부 repository rename 등의 정확한 잔여 gate
8. 허용 목록 밖 legacy literal 0건과 secret scan 결과

### 9. 최종 응답 형식

최종 응답은 다음 순서로 작성한다.

1. **개요** — 완료 여부와 새 제품 정체성
2. **변경 내용** — Android, Web, Python, 운영/문서별 실제 변경
3. **검증 결과** — 명령, pass/fail/skip, APK/wheel checksum
4. **실기기 결과** — Samsung package/install/update/smoke 결과
5. **남은 항목** — 실계정 OAuth, signing, 외부 권한 등만 명시
6. **Git 상태** — branch, commit, push 여부와 remote

모든 완료 조건을 충족하지 못했으면 `완료`라고 표현하지 말고 `구현 완료 / 외부 gate 대기`,
`부분 완료`, `차단` 중 실제 상태를 사용하라.

---
