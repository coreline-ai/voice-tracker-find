# 3차 전문가 소스 분석 — Android / 서버 분리 검토

검토 일시: `2026-07-25 09:xx KST`
대상 HEAD: git `3e4656d` ("fix: restore playback and transcript links"), 직전 `82d6ece`("feat: complete local sync and dashboard"), `3e5e08c`("feat: implement Android Compose recorder and receiver v1")

사용자 요청("안드로이드와 서버 소스를 잘 분류해서 각각 분석")에 따라 **Android 소스**와 **Python 서버 소스**를 별도 전문가가 병렬로 정밀 분석하고, 오케스트레이터가 배포 차단급 발견을 실코드·테스트로 교차 검증했다. 2차 검토(`review_20260723_2149_expert_round2.md`) 대비 변화를 추적한다.

## 종합 판정 (3차)

**2차 P1 총 10건(서버 5 + Android 5)이 전부 코드+전용 회귀 테스트로 해소됐다.** 큰 진전이다. 그러나 이번 심층 분석에서 **서버 신규 P0 1건(CRLF 헤더 삽입, 실증)**과 **현재 HEAD에서 실패 중인 회귀 테스트 1건**을 새로 발견해, 최종 배포 판정은 **CONDITIONAL — 서버 P0 수정과 실패 테스트 해소가 선행 조건**으로 유지한다.

| 영역 | 2차 | 3차 | 근거 |
|---|---|---|---|
| 서버 P1 5건 이행 | — | **5/5 해소** | stale temp GC, APK v1 테스트, 노트 동시성 레이스, symlink 거부, 폴더별 동명 id — 전부 전용 테스트 |
| Android P1 5건 이행 | — | **5/5 해소** | FAILED 보존, 창 이탈 검사, FGS 해제, NoteConflict 보존, PENDING_DELETE 격리 — 전부 회귀 테스트 |
| 서버 신규 안전 V1 대응률 | 약 94% | 개별 갭 기준 **약 99%**, 단 신규 P0로 보안 판정은 조건부 | 아래 서버 §5 |
| Android 계획 준수도 | 75~80% | **약 90%** | 아래 Android §1 |
| 서버 회귀 | 80 passed | **596 passed / 1 failed**(6개 지정 파일 136 passed / 1 failed) | 오케스트레이터 재현 확인 |

**오케스트레이터 교차 검증 결과:**
- **P0 CRLF 실증** — [receiver.py:688](../src/thinktank/receiver.py#L688)이 unquote된 경로 세그먼트 `segments[1]`을 검증 없이 `Location` 헤더에 반영. 같은 클래스 [_request_id()](../src/thinktank/receiver.py#L265-L278)는 CR/LF·제어문자를 거르는데 이곳만 누락(비대칭 확인). Python `http.server`의 `send_header`는 값 CRLF를 거르지 않으므로 raw socket 요청으로 `Set-Cookie` 등 임의 헤더 주입 가능(CWE-113). 단일 사용자 모드(기본 배포)에서 `_v1_user_for`([:387-399](../src/thinktank/receiver.py#L387-L399))는 `multi_user`일 때만 이름을 검증해 임의 userId가 통과. 유효 Bearer 토큰이 필요하나 결함 자체는 배포 모드 무관하게 존재하며, 다중 테넌트(클라우드 어댑터) 전환 시 심각도 상승.
- **실패 테스트 재현** — `.venv`(Python 3.13.12)로 `tests/test_receiver.py::test_삭제한_노트는_목록에서_사라진다` 실행 → 결정적 FAILED(`assert '2026-07-19.md' not in {'2026-07-19.md'}`). 원인: `NOTE_SPECS`([:88-96](../src/thinktank/receiver.py#L88-L96))에 `("90-archive", 100, None)`이 필터 없이 추가돼 보관 노트가 legacy `/notes/{user}` 목록에 재노출. `_collect_notes`가 legacy·V1 공유라 legacy 계약까지 파급.
- **계획서 수치 불일치** — `implement_20260724_163736.md`의 "Python 635 passed" 최종 요약은 현재 HEAD 실측(597 passed, 1 failed, 4 skipped, 4 deselected)과 다름.

## 우선 조치 (권고 순서)

1. **[서버 P0]** [receiver.py:688](../src/thinktank/receiver.py#L688) Location 헤더를 `segments[1]` 원문 대신 인증으로 확정된 `user.name`으로 구성하거나, `_request_id()`와 동일하게 CR/LF·제어문자 거부.
2. **[서버 P1]** `90-archive` 노출 정책을 명시적으로 결정 — legacy는 `_is_transcript_archive` 필터 복원 후 실패 테스트 GREEN, 또는 "legacy도 보관함 노출"을 확정하고 테스트를 새 계약에 맞춰 갱신. 어느 쪽이든 `phase0-decision-record`에 기록.
3. **[Android P2]** DIRTY/FAILED 노트의 서버 404가 push 경로에서 목록 갱신을 차단하는 교착(P1-5와 동형) 해소, `stopSelf(startId)` 적용.
4. **[증적]** 최신 커밋(`3e4656d`) 기준 Android 단위 테스트를 재실행해 증적 갱신(현재 `build/` 증적은 그 이전 상태).
5. **[문서]** 계획서 "635 passed" → 실측치로 정정, 3차 발견 반영.

---

## 부록 A. 서버 소스 분석 보고서 (전문)

작업 디렉터리는 git 저장소이며 최신 3개 커밋은 `3e5e08c→82d6ece→3e4656d`. 2차 검토는 `82d6ece` 직전/중 스냅샷, 현재 HEAD는 `3e4656d`.

### A-1. 판정 요약

**2차 P1 5건은 전부(코드+전용 회귀 테스트) 해소됐지만, 신규 P0급 HTTP 응답 헤더 삽입(CRLF injection)과 현재 HEAD에서 실패 중인 회귀 테스트 1건을 새로 발견했다 — "안전 배포 가능" 최종 판정은 P0 수정 전까지 조건부.**

- 개선(2차 대비): stale temp 정리, APK v1 테스트, 노트 동시수정 레이스, symlink 거부, 폴더별 동명 노트 구분 5건 모두 닫힘. `recording_id+chunk_id` 재사용 500 우려도 구조적 해소, 오류 응답 `requestId` 검증도 테스트 헬퍼로 전수 적용. 대시보드(`web_dashboard.py`)·녹음별 메모(`notes/recording_memo.py`)는 설계 문서가 약속한 보안 경계(인증·경로검증·XSS방지·토큰 비노출)를 코드 수준에서 실제 준수.
- 신규 발견: ① [P0] POST 노트 Location 헤더 CRLF 삽입([receiver.py:688](../src/thinktank/receiver.py#L688), raw-socket PoC 실증). ② [P1] legacy `/notes/{user}` "삭제 노트 사라짐" 계약 붕괴(`90-archive` 무필터 노출, HEAD `3e4656d`에서 회귀, 테스트 RED). ③ [P1] V1/대시보드 경로의 다중 사용자 격리 회귀 테스트 부재(구조는 안전, 테스트만 없음).
- "신규 안전 V1 대응률": 개별 갭 기준 94%→약 99%. 그러나 신규 P0 반영 시 종합 보안 판정은 "조건부 가능 — `receiver.py:688` 수정 선행".

### A-2. 구조 분류 지도

| 모듈 | 계층 | 책임 | 핵심 의존 |
|---|---|---|---|
| `receiver.py` (1326줄) | HTTP 라우팅 | legacy(`/upload`,`/notes`,`/apk*`) + `/api/v1/*` + `/dashboard`(정적) + `/api/v1/dashboard/*` 라우팅, 인증/토큰 판별, TLS 인증서 발급, 서버 부트스트랩 | `server.ports`, `adapters.local_receiver`, `server.contracts`, `web_dashboard` |
| `receiver_v1.py` (39줄) | 호환 shim | `adapters.local_receiver`의 심볼 재수출, `ReceiverV1State=LocalReceiverV1Adapter` 별칭 | `adapters.local_receiver` |
| `server/ports.py` (182줄) | 포트(추상 경계) | 현재 소비 포트 `ReceiverV1Persistence` + 미래 클라우드용 세분 포트(UploadStore/ReceiptRepository/NoteRepository/JobOutbox, 아직 어댑터 없음) + 값 객체 | 순수 typing/dataclass |
| `server/contracts.py` (42줄) | 계약 상수/검증 | MAX_V1_UPLOAD_BYTES, UPLOAD_STATUS_*, UPLOAD_MEDIA_TYPES, is_safe_leaf_name, NOTE_FOLDERS(미사용) | 순수 함수 |
| `server/upload_domain.py` (42줄) | 순수 도메인 | UploadFingerprint, receipt_matches, orphan_matches (I/O 없이 단위 테스트 가능) | 없음 |
| `adapters/local_receiver.py` (759줄) | 어댑터(로컬 영속) | 업로드 수신/검증/원장(SQLite), stale temp GC, 노트 CRUD(id/revision/If-Match/archive), APK 메타 — `ReceiverV1Persistence` 구현 | `server.upload_domain`, stdlib |
| `web_dashboard.py` (134줄) | 요약 빌더(순수) | `/dashboard/summary` JSON 생성(절대경로·토큰 미포함) | `ingest.AUDIO_EXTENSIONS` |
| `notes/recording_memo.py` (83줄) | 파이프라인 산출물 | 전사 완료 녹음별 `30-ideas` 멱등 메모 렌더/저장 | `notes.renderer/archive/emerged`, `extract` |
| `notes/renderer.py` (164줄) | 렌더링 유틸 | frontmatter/위키링크/파일명 규칙(신규 recording_memo_filename) | 없음 |
| `web/dashboard/{index.html,app.js,styles.css}` | 프런트(정적) | 인증 카드·요약/큐/노트 패널·MD 뷰어(textContent)·M4A 플레이어(Blob URL), 외부 CDN 미사용 | 없음 |

기존 `/api/v1` 계약 보존됨(`test_v1_and_legacy_contracts_work_on_the_same_server`, 라우팅 우선순위 dashboard→api/v1→legacy 겹침 없음). 단 legacy `/notes` 내용 변경(90-archive 노출)은 A-5 §P1 참조.

### A-3. P1 이행 확인표 (2차 서버 5건 — 5/5 해소)

| # | 지적 | 판정 | 근거 |
|---|---|---|---|
| 1 | stale temp `.part` 정리 미구현 | 해소 | `cleanup_stale_upload_parts()`(`adapters/local_receiver.py:95-127`)가 `__init__`에서 호출(:215). 테스트 `test_v1_startup_cleans_only_stale_request_temp_files`(24h 경과분만 삭제·최종 파일 보존) |
| 2 | `/api/v1/apk(/info)` 테스트 0건 | 해소 | `test_v1_apk_info_and_download_are_authenticated_and_hashed` — 구조화 버전 응답·다운로드 바이트·무인증 401 |
| 3 | 노트 동시수정 레이스 테스트 없음 | 해소 | `test_v1_concurrent_note_updates_allow_exactly_one_revision_winner` — ThreadPoolExecutor(2) 동시 PUT, `[200,412]` 1개씩(`_write_lock`+`BEGIN IMMEDIATE`) |
| 4 | symlink/vault-escape 거부 테스트 없음 | 해소 | `test_v1_rejects_note_symlink_after_its_identity_exists` — 실제 심볼릭 링크 400 `UNSAFE_NOTE_PATH`. 가드 `_note_path`(is_symlink + is_relative_to) |
| 5 | 폴더별 동명 노트 id 구분 테스트 없음 | 해소 | `test_v1_notes_keep_same_name_in_separate_folders_as_distinct_notes` — 서로 다른 note_id 2개. 스키마 `UNIQUE(user_id, folder, name)` |

### A-4. P2 추적표

| # | 지적 | 상태 |
|---|---|---|
| 1 | recording_id+chunk_id 재사용→500 | 해소 — `_find_receipt`가 idempotency_key OR filename OR (recording_id AND chunk_id) 사전 조회 후 구조화 409. 테스트 있음 |
| 2 | POST Location 이중 슬래시 | **격상 → P0(CRLF)**, A-5 참조 |
| 3 | 전역 단일 RLock 병목 | 미해소(정확성 문제 아님) |
| 4 | 오류 응답 requestId 검증 테스트 부재 | 해소 — `_assert_structured_error`가 requestId+X-Request-ID 강제, 17개 지점 재사용 |
| 5 | legacy/v1 트래픽 카운터·제거 시점 | 미해소 — decision-record 갱신·카운터 없음 |
| 6 | OpenAPI↔구현 자동 검증(CI) | 미해소 — `.github/` 워크플로 부재 |
| 7 | get_note/list_notes TOCTOU | 미해소(이론적) |
| 8 | archive 후 동명 재생성 note_id 부활 | 미해소(테스트/문서 없음) |
| 9 | Python 3.12 정확판 재검증 | 미해소(환경 제약, `.venv`는 3.13.12) |

### A-5. 신규 코드 이슈 목록

**[P0] HTTP 응답 헤더 삽입 / 응답 분할 — `POST /api/v1/notes/{userId}` Location 헤더**
- 근거: `receiver.py:661-691`, 특히 `:688` `"Location": f"/api/v1/notes/{segments[1]}/{note['id']}"`. `segments[1]`은 `_segments()`가 `unquote()`만 한 원문. 단일 사용자 모드에서 `_v1_user_for`가 이름 미검증.
- 실증: `userId`에 `evil%0D%0AX-Injected%3A%20pwned%0D%0ASet-Cookie%3A%20sess%3Dabc` 주입 → 응답에 `X-Injected: pwned`, `Set-Cookie: sess=abc/...`가 실제로 삽입됨(CWE-113). `X-Request-ID`는 `:265-278`에서 이미 검증하는데 `segments[1]`엔 같은 보호 누락.
- 영향: 단일 사용자 모드(주 배포)에서 유효 Bearer만으로 트리거. 다중 사용자 모드는 `supplied_name`이 등록 이름과 일치해야 해 외부 트리거 난이도 높음. 결함은 모드 무관 존재, 클라우드 다중 테넌트 전환 시 심각도 상승.
- 권고: `_request_id()`식 CR/LF·제어문자 거부, 또는 서버가 인증으로 확정한 `user.name`으로 Location 구성.

**[P1] legacy `/notes/{user}` "삭제 노트 사라짐" 계약 붕괴 (회귀, 테스트 RED)**
- 근거: `receiver.py:88-96` `NOTE_SPECS`에 `("90-archive", 100, None)`(무필터). `git show 3e4656d`로 직전은 `("90-archive", 100, _is_transcript_archive)`였고, 그 필터 주석이 정확히 "앱에서 보관한 일반 노트가 되살아남"을 경고했음 — 이 안전장치가 HEAD에서 제거됨. `_collect_notes`(`:141-157`)가 legacy·V1 공유라 legacy까지 파급.
- 실증: `tests/test_receiver.py::test_삭제한_노트는_목록에서_사라진다`(`:590-598`) 결정적 FAILED.
- 참고: V1은 이 동작이 의도된 것으로 보이며 전용 테스트(`test_v1_notes_expose_archive_contents_to_mobile`)로 검증됨. 문제는 legacy까지 공유 헬퍼로 영향받았는데 legacy 테스트는 미갱신인 채 RED라는 점.
- 권고: legacy/V1 NOTE_SPECS 분리(legacy는 필터 복원) 또는 "legacy도 노출" 확정+테스트 갱신, 결정 기록.

**[P1] V1/대시보드 다중 사용자 격리 회귀 테스트 부재**
- 근거: `two_users` 격리 테스트가 legacy(`test_receiver.py:715-830`)에만 존재. V1/대시보드 테스트엔 2번째 사용자 구성 없음.
- 평가: 대시보드 경로엔 `{user}` 세그먼트가 없고 토큰이 사용자를 결정하므로 구조적으로 안전(레거시보다 단순·견고). 다만 불변식을 지키는 자동 테스트가 없어 향후 리팩터링이 조용히 깰 수 있음.

**[P2] 기타**

| 파일:라인 | 내용 |
|---|---|
| `server/contracts.py:21` | `NOTE_FOLDERS`가 `receiver.py:90-96`과 중복+미import 사장 코드 |
| `docs/receiver-api-v1.yaml:29-46` | `/api/v1/ready` 문서화됐으나 구현 없음(문서-구현 드리프트) |
| `receiver-api-v1.yaml:151` | POST 노트 `folder` enum에 90-archive 없으나 구현은 허용(직접 생성 가능) |
| `receiver-api-v1.yaml:319-323` | If-Match 패턴이 `"*"` 와일드카드 미문서화 |
| `receiver.py:858-879` | `/dashboard/audio` Range 미지원(계획상 의도적 제외, 초대용량 Blob 부담 잔존) |
| `notes/recording_memo.py:82` | write_text 비원자(기존 파이프라인 전체 동일 패턴, 회귀 아님) |
| `main.py:204-222` | `_backfill_recording_memos`가 매 실행마다 ORGANIZED 전체 temp 재읽기 후 멱등 스킵 — I/O 증가 |

**대시보드 보안 점검(항목별)**: 인증 전 핸들러 적용 **예**(summary/notes/audio 모두 `_authorized()`, 401 테스트). path traversal/symlink **차단**(`_dashboard_file`이 is_safe_leaf_name + `.resolve()` + parents 검사, 인코딩 `../` 테스트). 사용자 격리 **구조적 예/회귀 테스트 부재**. Range **미지원(의도)**. XSS **안전**(app.js가 `textContent`, 목록은 `escapeHtml`, CSP `script-src 'self'`). 토큰 URL 노출 **없음**(sessionStorage + Bearer 헤더, 오디오는 fetch+Blob).

### A-6. 테스트 실행 결과
- 환경: `.venv`(uv), Python 3.13.12(`>=3.12` 충족, 리터럴 3.12 부재).
- 6개 지정 파일: **136 passed, 1 failed**(유일 실패 = `test_삭제한_노트는_목록에서_사라진다`, 재실행 결정적, 플레이키 아님).
- 전체: **597 passed, 1 failed, 4 skipped, 4 deselected** (계획서 "635 passed"와 불일치).
- `ruff check`(6개 모듈): All checks passed.

### A-7. 계획 문서 대비 상태 (`implement_20260724_163736.md`)
- Phase 1(녹음별 메모): 일치 — 결정적 파일명+존재 시 스킵 멱등, 실패 시 ORGANIZED 전이 차단. `test_recording_memo.py` 4건 통과.
- Phase 2(대시보드 콘텐츠 API): 일치 — 401/400/404, MD `no-store`+UTF-8, M4A `audio/mp4`+`nosniff`.
- Phase 3(뷰어/플레이어 UI): 일치 — textContent, Blob URL, revokeObjectURL 정리.
- Phase 4(통합 검증): **불일치** — "635 passed" vs 실측 597 passed/1 failed. 90-archive 노출 확대가 이슈 로그에 미기록, 회귀 테스트 미갱신.

---

## 부록 B. Android 소스 분석 보고서 (전문)

분석 기준: `android-app/` HEAD `3e4656d`, main Kotlin 29 + test 10 + androidTest 1 = 40개. 정적 분석 전용.

### B-1. 판정 요약

**2차 P1 5건이 전부 실코드+회귀 테스트로 해소됐고 Samsung SM-S931N 실기기 완전 E2E 1회가 증적과 함께 통과한, "운영 서명·TLS·장기 soak만 남은 내부 QA 통과 상태".** 후퇴 없음. 계획 준수도 75~80%→**약 90%**(P1 5/5, P2 13건 중 해소 4·부분 3·미해소 6, 디자인 P1 4건 중 해소 3·부분 1). 남은 갭: 충돌 해소 전용 UI 부재(NoteConflict 저장만 되고 소비자 없음), 알림 오류 채널 부재, 노트 폴더 접기/상한, MediaRecorder OnError 리스너, orphan 승계, 접근성 3건(reduced-motion/tnum/ImageState fallback), 미사용 의존성 2종. 단 **마지막 로컬 단위테스트 증적(07-24 03:21, 22건 통과) 이후 커밋 2건에서 main 9파일 수정+테스트 11건 추가됐는데 이 상태 실행 증적이 `build/`에 없음** — 다음 빌드에서 우선 재확인.

### B-2. 구조 분류 지도

| 파일 (…/next/) | 계층 | 책임 | 2차 이후 |
|---|---|---|---|
| MainActivity.kt | UI 진입 | 딥링크/extra→초기 route, 3 VM | 변경(destinationRoute) |
| ThinkTankApplication.kt | App/DI | 알림 채널 2종, WorkManager 설정, 자동동기화 reconcile | 변경 |
| ui/ThinkTankApp.kt | UI | 3탭 NavHost, 온보딩, 시스템바 | 변경 |
| ui/AppViewModels.kt | ViewModel | Recording/Notes/Settings 3VM + 순수함수(isManualSyncInProgress, validateAndCommitServer) | 변경 |
| ui/recording/RecordingScreen.kt | UI | 녹음 제어·권한·최근 녹음 재생(MediaPlayer)·마이크 입력 감지·진단 | 변경(재생·입력감지 신규) |
| ui/notes/NotesScreen.kt | UI | 노트 목록(필터/폴더 그룹)+상세, frontmatter 은닉, Markdown/위키링크/전사 별칭 | 변경 |
| ui/settings/SettingsScreen.kt | UI | 서버/청크/시간창/동기화/버전 설정 | 변경(하드코딩 색 제거) |
| ui/common/Components.kt | UI 공통 | SoundThread, RecordControl, StatusPill, ImageState | 변경 |
| ui/theme/Theme.kt | UI 토큰 | 다크/라이트 팔레트, ArchiveNoteCopper·LightArchiveMoss 신설 | 변경(대비 수정) |
| recording/RecorderService.kt | Service | mic FGS, 청크 루프, 시간창, PCM/WAV fallback 캡처, wake lock | 대폭 재작성 |
| recording/RecordingSessionOutcome.kt | Domain | 세션 종결 상태 계약(FAILED 보존) | 신규 |
| recording/RecordingFileManager.kt | Data/파일 | .part→확정 rename, SHA-256, quarantine, reconcile | 변경 |
| recording/{RecorderController,RecordingRuntime,RecordingWindow}.kt | Service 제어/상태/도메인 | 시작·정지 인텐트 / amplitude Flow / 시간창 | 소폭 |
| data/local/Entities.kt | Data | 6 엔티티(UploadAttempt에 outcome/finishedAt/errorCode/requestId) | 변경 |
| data/local/Daos.kt | Data | CAS claim/lease, finishAttempt, Note upsert=update-then-insert(ABORT) | 변경(P1-4·P2-5) |
| data/local/ThinkTankDatabase.kt | Data | Room v1, exportSchema | 유지 |
| data/remote/ReceiverApi.kt | Remote | /api/v1 업로드·노트·apk, bounded body, WAV 타입 | 변경(P2-11) |
| data/remote/NotesRemoteGateway.kt | Remote 계약 | 노트 전용 인터페이스(테스트 대체) | 신규 분리 |
| data/repository/SyncRepository.kt | Repository | claim→업로드→receipt 대조→attempt 종결→notes | 변경 |
| data/repository/NotesRepository.kt | Repository | push/충돌 보존/archive 연기(ArchiveOutcome)/원격 반영 | 변경(P1-5) |
| data/repository/RecordingRepository.kt | Repository | DB Flow 노출 | 변경 |
| data/settings/AppPreferences.kt | Data/설정 | DataStore, URL 정규화(release=https 강제), wifiOnly 기본 true | 변경 |
| data/settings/SettingsReader.kt | Data 계약 | current() 협소 인터페이스(테스트용) | 신규 |
| data/settings/TokenCipher.kt | Data/보안 | Keystore AES-GCM | 유지 |
| di/AppModule.kt | DI | Room/OkHttp(12분)/@Binds Gateway·SettingsReader | 변경 |
| worker/SyncWorker.kt | Worker | 동기화 실행+완료 알림(딥링크·dedupe) | 변경 |
| worker/SyncScheduler.kt | Worker | manual=REPLACE/CONNECTED, periodic=30분·wifiOnly 토글 | 변경(P2-6·7) |

test 10: RecordingWindowTest, RecordingSessionOutcomeTest(신규), RecordingDaoTest(확장), ReceiverApiTest(확장), NotesRepositoryTest(신규), UploadReceiptValidationTest(신규), MarkdownParserTest(확장), ThemeContrastTest(신규), ManualSyncUiStateTest(신규), ServerSettingsCommitTest(신규). androidTest: ComposeScreensTest(3건). launcher는 mipmap-anydpi(+v33)·monochrome 재구성, values-night 없음.

### B-3. P1 이행 확인표 (2차 Android 5건 — 5/5 해소)

| # | 2차 P1 | 판정 | 근거 |
|---|---|---|---|
| 1 | FAILED가 finally에서 STOPPED로 덮임 | 해소 | `RecorderService.kt:341-354` finally가 `terminalRecordingOutcome` 결과로 상태·error 기록. `RecordingSessionOutcome.kt:11-18`이 캡처 실패 보존(정리 성공해도 FAILED, finalize 실패→FINALIZE_FAILED). `Daos.kt:22-36` setSessionState에 error 파라미터. UI 표면화 `RecordingScreen.kt:232-236,400`. 테스트 3건 |
| 2 | 청크 중 시간 창 이탈 미검사 | 해소 | 내부 루프 `RecorderService.kt:311,320-327`이 30초(WINDOW_CHECK_MS)마다 `isRecordingWindowOpen()` 재평가→이탈 시 stopChunk 후 WAITING 전환 |
| 3 | 비정상 종료 후 FGS·알림 잔존 | 해소 | 세션 job이 `finally { stopForeground(REMOVE); stopSelf() }`로 감싸짐(`:255-262`). ACTION_STOP도 cancelAndJoin 후 동일(:241-247). wake lock 세션·stopChunk finally 해제 |
| 4 | NoteConflict REPLACE+CASCADE 즉시 삭제 | 해소 | `Daos.kt:195-213` insert를 ABORT로 강등, upsert를 update-then-insert @Transaction으로 교체(주석 명시). 테스트 `updatingNoteDoesNotDeleteItsPersistedConflict` |
| 5 | PENDING_DELETE 404/412가 동기화 영구 차단 | 해소 | `NotesRepository.kt:182-214` archivePending: 404→로컬 삭제, 408/429/5xx→Retryable, 기타→Failed(노트 보존). syncAll(:76-128)이 archive 결과를 연기하고 listNotes·원격 반영 완주 후 종합 판정. 테스트 3건 |

### B-4. P2·디자인 추적표

| 항목 | 판정 | 근거 |
|---|---|---|
| P2-1 compileSdk35+AGP 8.5.2 | 해소 | agp=8.9.1, Gradle 8.12. lint 0 errors. `gradle.properties`의 suppressUnsupportedCompileSdk 잔재 |
| P2-2 MediaRecorder OnError/OnInfo | 미해소(부분 완화) | 리스너 없음. PCM 경로만 maxAmplitude workerError로 감지, MediaRecorder 경로 청크 중간 사망 미감지 |
| P2-3 STOP→START 연타 경합 | 부분 해소 | commandMutex+cancelAndJoin 도입. 그러나 stopSelf()가 startId 없이 호출(:246,:260)—START 소실 잔존 |
| P2-4 rename 후 crash orphan 승계 | 미해소 | reconcile이 .part 부재→FILE_MISSING만, 완료 .m4a READY 승계 없음(서버와 비대칭) |
| P2-5 UploadAttempt 종결 미기록 | 해소 | Entities+Daos finishAttempt, SyncRepository 전 경로 종결. 테스트 있음 |
| P2-6 수동 동기화 KEEP | 해소 | REPLACE+주석. ManualSyncUiStateTest |
| P2-7 UNMETERED 노트까지 보류 | 부분 해소(정책 대체) | worker 분리 대신 wifiOnly 토글(기본 true), 수동은 항상 CONNECTED. 노트만 metered 세분화는 없음 |
| P2-8 TokenCipher key() 경합 | 미해소 | 동기화 없음 그대로 |
| P2-9 미사용 의존성 | 미해소 | security-crypto, okhttp-logging 잔존 |
| P2-10 알림 3종·deep link·dedupe | 부분 해소 | 채널 2종, 동기화 완료 딥링크(thinktank://notes)+해시 dedupe. 오류 채널 없음, onNewIntent 미처리로 Activity 중복 적층 |
| P2-11 executeJson chunked 우회 | 해소 | readBoundedBody가 contentLength 무관 10MiB 실측 제한. 테스트 있음 |
| P2-12 회전 gap SHA-256 동기 | 미해소 | stopChunk가 rename+retriever+SHA-256을 다음 startChunk 전 동기. setNextOutputFile 미채택 |
| P2-13 NavigationBar 이중 인셋 | 미해소 | windowInsetsPadding 잔존 |
| 디자인 P1-3 라이트 AA 하드코딩 | 해소 | SettingsScreen 전부 colorScheme 참조. NotesScreen 위키링크 ArchiveNoteCopper #8C452B(5.7:1). ThemeContrastTest가 3조합+ 4.5:1 게이트 |
| 디자인 P1-4 light moss #66745F | 해소 | LightArchiveMoss #5F6D58(4.97:1) 채택, 테스트 커버 |
| 디자인 P1-1 MaruBuri 라이선스 | 부분 해소 | licenses/엔 Pretendard만. 대신 APK 동봉 assets/licenses/FONT-LICENSES.txt(Pretendard+MaruBuri OFL 전문)+verifyBundledFontLicenses 게이트. 취득일·MARUBURI-*.txt 규칙 미이행 |
| 디자인 P1-2 manifest 필드 미달 | 해소 | asset-manifest 7건 전수 author/license/prompt/purpose. verifyRasterAssets 누락 시 빌드 실패 |
| ImageState fallback / reduced-motion / tnum | 3건 미해소 | Components.kt:176-216 / :49-88 / RecordingScreen.kt:135-139 |
| adaptive icon safe zone | 미해소 | 전경 하단 y≈96>87. monochrome 레이어는 신설 |

### B-5. 신규 이슈 목록

**P0 — 없음. P1 — 없음.**(2차 P1 5건 전부 해소, 신규 P1급 유입 없음)

**P2**
1. **DIRTY/FAILED 노트의 서버 404가 노트 목록 갱신을 차단** — `NotesRepository.kt:73-75` push 오류는 archive와 달리 연기되지 않고 forEach 탈출, `:130-135`에서 404가 비재시도 Failed로 종결돼 listNotes 미도달. 대시보드/볼트에서 이미 보관된 노트에 로컬 편집이 남으면(`local_receiver.py:648` archived 노트 update가 404) 사용자가 수동 보관할 때까지 매 동기화 Failed. P1-5와 동형 교착이 push 경로에 잔존.
2. **정지 직후 시작 연타 시 신규 세션 소실 가능** — `RecorderService.kt:246,:260`의 stopSelf()가 startId 미사용. STOP 코루틴이 START의 onStartCommand 후 stopSelf 실행 시 onDestroy→scope.cancel로 새 세션 취소·알림 제거. START_NOT_STICKY라 재전달 없음. stopSelf(startId) 필요.
3. **노트 상세 충돌 배너가 다크 테마에서 AA 미달** — 상세 배경은 테마 무관 고정 종이 ArchivePaper #EEE8DC(`NotesScreen.kt:372`)인데 충돌 문구가 colorScheme.error(:393-399). 다크에서 ArchiveError #D06A60 on paper ≈ 2.9:1. ThemeContrastTest 미검사. 고정 종이 위 요소는 고정 색으로 통일 필요.
4. **테스트 실행 증거 갭** — 마지막 로컬 단위테스트 증적 07-24 03:21(8클래스 22건). 이후 커밋 82d6ece(17:09)·3e4656d(17:29)가 main 9파일 수정+테스트 11건 추가했으나 build/test-results에 실행 기록 없음. 계획서 "Android unit test 통과" 주장 재현 산출물이 이 트리에 없음.
5. **WAITING 대기 중 wake lock 미보유로 창 재진입 지연 가능** — 창 이탈 시 stopChunk finally가 wake lock 해제(:506-511) 후 delay(WINDOW_CHECK_MS) 폴링 대기 — doze에서 30초 폴링이 수 분 지연될 수 있어 창 시작 시각 정확도 저하(기능 상실 아님).

**P3**: 녹음 중 재생 배타 처리 없음(스피커 혼입), dedupe 텍스트 해시 기반 누적, suppressUnsupportedCompileSdk 잔재·미사용 의존성·SyncCursor 미활용·충돌 해소 UI 부재(observeConflicts 소비자 0건), E2E 계획 참조 문서(samsung smoke md) 부재.

**서버 계약 정합 — 불일치 없음.** 표본: 업로드 헤더(X-Content-SHA256/Idempotency-Key UUID/X-Recording-ID/X-Chunk-ID, `receiver.py:722-767`↔`ReceiverApi.kt:78-87`), receipt 8필드+status, 201/200, 노트 id/folder/name/content/revision/updatedAt, If-Match 428/412/404/409, DELETE archived:true, apk/info, 오류 envelope+X-Request-ID, 경로 unquote, WAV 업로드 양측 지원. 서버 2GiB 상한 > 최장 청크(120분 WAV ≈230MB) 여유.

### B-6. 테스트 커버리지 분류와 빌드 증거

단위 33 + 계기 3건 보호: 세션 종결(RecordingSessionOutcomeTest 3, `recoverable` 플래그는 미구현/계획 대비 축소), claim/lease/attempt(RecordingDaoTest 5, Robolectric+in-memory), 노트 교착(NotesRepositoryTest 3, 실 Room+Fake), 업로드 확정(UploadReceiptValidationTest 1), /api/v1 와이어(ReceiverApiTest 6, MockWebServer), 시간창(RecordingWindowTest 3), UI 상태(ManualSyncUiStateTest 2, ServerSettingsCommitTest 2), Markdown(MarkdownParserTest 5), 대비(ThemeContrastTest 3), Compose(ComposeScreensTest 3).
미보호: RecorderService 상태 머신, SyncRepository 전체, SyncWorker/Scheduler, TokenCipher, reconcile.

빌드 증거(실행 없이 판독): 단위 test-results mtime **07-24 03:21, 8클래스 22건, 0 failures**(debug/release 동일). lint **debug 0 errors 67 warnings / release 0 errors 45 warnings**. 계기 07-23 22:14 AVD API35 **2/2**, 물리 "PD20-12" 2/2 실패(외부 com.coreline.cbot 화면 점유로 무효 분리, API35 재실행 3/3). APK `release/...r{9,10,11}.apk`(r11 4.58MB). 소스 버전 versionCode **1** / versionName **1.0.0**(r번호는 파일명 관리, versionCode 미증가 — apk/info 업데이트 감지에 릴리스마다 증가 필요). Room 스키마 1.json 존재. **주의: 증거 전부 3e4656d 이전 상태**.

### B-7. 실기기 E2E 리스크 (Samsung → 맥미니 LAN → 노트 재동기화)

1. debug 기본 URL 하드코딩 `http://192.168.0.71:8765`(`build.gradle.kts:25`) — DHCP 변경 시 무효. release는 https 강제라 **평문 수신기로는 release E2E 불가**(TLS 전까지 debug 전용).
2. 자동 처리 debounce 150초 동안 앱 "처리 중" 상태 부재 — 1차 동기화 직후 "노트 0개"가 정상인데 실패 오인 가능.
3. wifiOnly 기본 true — Wi-Fi가 metered 판정되면 자동 동기화 무기한 보류. 수동(CONNECTED)로 우회.
4. 알림 탭 시 MainActivity 중복 적층(launchMode 기본, onNewIntent 부재).
5. PCM/WAV fallback 미검증 — MediaRecorder 3단 fallback 전부 실패 시 16kHz WAV 업로드. 서버 수신 정합하나 **VAD/STT의 WAV 입력이 이번 E2E(m4a)에서 미검증**.
6. 대시보드 보관+앱 로컬 편집 교차 시 P2-1 404 교착 가능.
7. 정지→즉시 재시작 연타 시 P2-2로 녹음 헛동작 가능.
8. 긴 청크(120분) 검증 시 SHA-256 동기 해싱(P2-12)이 gap 확대→발화 유실 구간 관측 가능.
9. 시간창 시나리오는 WAITING wake lock 미보유(P2-5)로 doze 지연 — 충전/화면 조건 함께 기록 필요.
10. queue.count(대시보드 inbox 보존 수)와 앱 업로드 대기 0의 의미 차이 주의(문서화됨).
