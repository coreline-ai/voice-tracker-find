# 2차 전문가 검토 아카이브 — implement_20260723_201248.md

검토 일시: `2026-07-23 21:49~21:50 KST`

검토 방식: 서버 계약, Android, 디자인·에셋·라이선스, QA 프로세스 4개 전문 에이전트가 독립·병렬로 저장소를 실사(전 항목 파일:라인 근거, 읽기 전용)하고, 오케스트레이터가 상충 판정을 실코드 재확인으로 해소했다. 이 파일은 보고서 전문 보존용이며, 합의된 판정 요약은 [implement_20260723_201248.md](implement_20260723_201248.md)의 "2차 다중 에이전트 검토" 절에 있다.

스냅샷 주의: 검토 도중에도 저장소가 병행 세션에 의해 계속 갱신됐다(아래 타임라인). 각 보고서는 자신이 명시한 최종 안정 스냅샷 기준이다.

## 오케스트레이터 교차 검증 메모

1. **OpenAPI 계약 문서 상충 해소** — QA 보고서는 "OpenAPI 또는 동등 계약 문서 없음"으로, 서버 보고서는 "`docs/receiver-api-v1.yaml` 존재·구현 일치"로 판정이 갈렸다. 오케스트레이터가 직접 재확인한 결과 해당 파일은 **실재**한다(10,096 bytes, OpenAPI 3.1, mtime 21:32). QA 에이전트의 확인 시점 이후에 생성된 것으로, 서버 보고서가 정본이다. 따라서 G1의 산출물(계약 문서) 조건은 물적으로 충족됐고, 남은 것은 승인 기록과 CI 고정이다.
2. **서버 회귀 테스트 이중 확인** — QA 에이전트(21:33, Python 3.14.3)와 서버 에이전트(Python 3.13.12/3.14.3 교차, 동시성 테스트 3회 반복)가 독립적으로 실행해 동일하게 **80 passed**(legacy 65 + v1 15)를 얻었다. `requires-python >=3.12`는 충족하나, 정확한 3.12 인터프리터는 이 머신에 없어(3.11/3.13/3.14만 확인) 문자 그대로의 3.12 재검증은 미수행 상태다.
3. **병행 수정 타임라인(관측)** — 21:29 `res/values/colors.xml`·adaptive icon 디렉터리 이동, 21:30~21:32 `ReceiverApi.kt`/`SyncRepository.kt`/`receiver_v1.py`에 recordingId/chunkId 계약 추가, 21:32 `docs/receiver-api-v1.yaml` 생성, 21:47~21:50 `docs/qa/`에 Android 12 기기 스크린샷·`device-db/` 수집. 계획서(20:52 저장)와 코드의 드리프트는 일회성이 아니라 **진행 중**이다.
4. **기기 증거 주의** — 현재 수집 중인 `docs/qa/screenshots/`는 `android12-*` 명명의 Android 12 기기 증거다. Phase 5·8 완료 조건이 요구하는 Android 14/15 실기기 검증을 이것으로 대체할 수 없다.
5. **이슈 집계** — 코드 P0(착수 차단) **0건**(4개 영역 공통). 프로세스 P0 **4건**(QA 보고서). P1 **18건**(서버 5, Android 5, 디자인 4, QA 4). P2 **35건**(서버 9, Android 13, 디자인 10, QA 3).
6. **판정** — 1차 CONDITIONAL GO를 유지하되 조건을 갱신한다. 세부는 계획서 본문 참조.

---

## 부록 A. 서버 계약 전문가 보고서 (전문)

### 사전 안내: 검토 중 소스가 실시간으로 변경됨

검토를 시작한 시점(`receiver.py` 1129줄 / `receiver_v1.py` 618줄 / `test_receiver_v1.py` 495줄)과 검토 도중 재확인한 시점 사이에 세 파일이 실제로 수정되고 있었다(`ls -la` 타임스탬프 21:31~21:32, 라인 수가 각각 1169 / 662 / 562로 증가). SHA-256 해시를 15초 간격으로 4회 연속 대조해 21:32:58 이후 내용이 고정된 것을 확인했고, 이후 21:43:51에 동일 해시로 재확인했다. **아래 모든 판정은 이 안정화된 최종 상태(1169 / 662 / 829 / 562줄) 기준이다.** 최초 버전(recordingId/chunkId 헤더가 없던 버전)을 기준으로 판정했다면 결과가 크게 달라졌을 것이므로 이 점을 먼저 밝힌다.

### 1. 판정 요약

계획서의 "현재 서버의 신규 안전 V1 대응률 55%"는 `receiver_v1.py` 구현 완료 후 **약 94%(구간: 90~96%)**로 갱신되어야 한다.

근거:
- 55%였던 이유는 계획서 자체가 명시한 대로 "legacy happy path는 있으나 receipt·revision·versioning 부족" 때문이었다. 이 세 가지(receipt, revision, API versioning)는 현재 모두 구현되어 있고 자동 테스트로 green이다(`tests/test_receiver.py` 65 passed + `tests/test_receiver_v1.py` 15 passed = 80 passed, Python 3.13.12/3.14.3 두 인터프리터로 교차 확인).
- "Phase 2 + P0 계약 완료: 목표 96% 이상" 기준과 비교하면, 핵심 계약(버전 분리 라우팅, 업로드 필수 헤더, 멱등/충돌 상태코드, 영속 ledger, 노트 id/revision/If-Match, per-note 원자적 쓰기, POST 신규 노트, 구조화 오류/버전 응답)은 구현·테스트 모두 확인되지만, Phase 2가 명시적으로 요구한 자체 테스트 12개 항목 중 5~6개(노트 동시 수정 레이스, symlink 거부, 폴더별 동명 노트 id 구분, APK v1 구조화 응답/해시 불일치, stale temp 상태)가 자동화되어 있지 않고, "stale temp 정리 정책"은 구현 자체가 없다. 따라서 G2 "계약 테스트 100%" 게이트는 아직 완전히 충족되지 않는다.
- "서버 대응 판단" 표의 "신규 Compose 안전 운영 → 현재 불가" 행은 이제 **"가능(핵심 계약 충족, 잔여 테스트 갭 존재)"**으로 격상되어야 한다.

### 2. Legacy 결함 재확인표

| 주장 | 확인 결과 | 파일:라인 |
|---|---|---|
| 새 노트 PUT이 404 (기존 노트만 허용) | **확인됨.** `_handle_note_put`은 `_find_note()`가 `None`을 반환하면 본문을 버리고 404를 응답하며, 새 파일을 생성하는 경로가 없다. | `receiver.py:853-866`(`_handle_note_put`), `:181-191`(`_find_note`); 회귀 테스트 `tests/test_receiver.py:393-400`(`test_없는_노트에_쓰면_404`) |
| `_find_note()`가 이름만으로 탐색해 폴더 간 동명 노트 구분 불가 | **코드 근거로 확인됨.** `_find_note`는 `NOTE_FOLDERS = ["1 wiki", "30-ideas", "10-daily"]` 순서로 순회하며 이름이 일치하는 첫 파일을 반환한다. `folder` 인자가 없어 두 폴더에 동명 파일이 있으면 항상 같은 폴더(우선순위상 "1 wiki")의 파일이 수정/삭제된다. 다만 이 시나리오를 직접 재현하는 전용 회귀 테스트는 `test_receiver.py`에 없다(코드 검사로만 결함 확정). | `receiver.py:181-191` |
| `uploaded_files` 파일명 Set 기반 중복 판정 (앱 쪽 주장 → 서버 대응 근거) | **서버 쪽도 동일한 결함 구조임을 확인.** legacy `_handle_upload`는 `target.exists()`만으로 재업로드 여부를 판정하고 해시를 비교하지 않는다. 따라서 같은 파일명·다른 내용의 두 번째 업로드는 조용히 200으로 무시되고 내용은 유실된다. `test_receiver.py`의 `test_같은_파일을_다시_올리면_덮어쓰지_않는다`(260-267행)는 이 "이름만 비교" 동작을 오히려 **정상 동작으로 고정**하고 있어, 결함이 회귀 테스트에 의해 의도적으로 보존되고 있음을 보여준다. | `receiver.py:769-774`; `tests/test_receiver.py:260-267` |
| `/apk/info` 평문 버전 문자열 | **확인됨.** legacy `_handle_apk_info`는 `.version.txt` 원문 또는 `f"{apk.name} ({size} bytes)"` 형태의 순수 텍스트를 반환한다(JSON 아님). 테스트도 `response.text()`에 부분 문자열 포함 여부만 확인한다. | `receiver.py:820-835`; `tests/test_receiver.py:695-699`(`test_apk_정보를_보여준다`) |
| `/apk?token=` 쿼리스트링 토큰 | **확인됨.** `_authorized_for_download()`가 헤더 인증 실패 시 쿼리스트링 `token=` 값을 허용하며, `/apk`·`/apk/info`(legacy) 두 엔드포인트에만 적용된다. | `receiver.py:363-374`; `tests/test_receiver.py:671-679, 687-692` |
| hostname/TLS 결함 (legacy 기준) | **범위 밖으로 정정 필요.** 이 결함은 안드로이드 클라이언트 `Pinning.clientFor()`가 hostnameVerifier를 항상 `true`로 반환하는 문제(APK 디컴파일 근거)이며, **파이썬 서버 자체의 TLS 코드에는 해당 결함이 없다.** `ensure_cert`/`_cert_covers`는 SAN(IP/DNS)을 실제로 검증해 인증서를 발급하고, `create_server`는 `TLSv1_2` 이상을 강제한다. 이 저장소에는 안드로이드 소스가 없어 클라이언트 측 결함 자체는 직접 재확인할 수 없다. | `receiver.py:961`(`context.minimum_version = ssl.TLSVersion.TLSv1_2`), `:951-966`(`_cert_covers`) |
| 업로드 receipt/영속 ledger/revision 부재 (legacy 기준) | **확인됨.** legacy 업로드 응답은 201/200/4xx/5xx 평문뿐이고 JSON receipt가 없다. legacy 노트 PUT/DELETE에는 revision·ETag·If-Match 개념이 전혀 없어 동시 편집 시 나중에 저장한 쪽이 무조건 이긴다. | `receiver.py:750-795`(`_handle_upload`), `:853-877`(`_handle_note_put`), `:879-898`(`_handle_note_delete`) |

### 3. Phase 2 태스크 대조표

| 태스크 | 판정 | 근거 파일:라인 | 남은 작업 |
|---|---|---|---|
| RED 계약 테스트 + OpenAPI/동등 문서 선(先)작성·커밋 | 구현됨(문서 존재, 이력 확인 불가) | `docs/receiver-api-v1.yaml`(전체, OpenAPI 3.1) — 구현과 필드/상태코드 일치 확인 | 이 저장소는 git 저장소가 아니어서 RED→GREEN 순서로 실제 작성되었는지 이력 검증 불가. CI에 OpenAPI 스키마 자동 검증(예: schemathesis)이 없어 문서-코드 드리프트를 자동으로 못 잡음 |
| 기존 경로/평문 응답 유지 + `/api/v1` additive 신규 계약 | **구현됨+테스트됨** | `receiver.py:442-501`(라우팅에서 `segments[:2]==["api","v1"]` 우선 분기, 나머지는 legacy로 폴백) | 없음 |
| legacy 제거 시점·사용량 확인 방법을 Phase 0 결정 기록에 연결 | **미구현** | `docs/phase0-decision-record.md` 전체에 제거 시점/사용량 측정 방법 없음; `receiver.py`에 legacy/v1 트래픽 구분 로깅·카운터 없음 | 결정 기록에 제거 조건(예: v1 트래픽 비율 임계치) 명시 + 최소한의 사용량 로그/카운터 추가 |
| legacy `PUT /upload/{user}/{filename}` 계약을 테스트로 고정 | **구현됨+테스트됨**(기존) | `tests/test_receiver.py` 다수(인증 401, 확장자 400, ADS/예약어 400, 507 등) | 411/413(길이 누락/초과) 테스트는 legacy에도 없음(P2) |
| 신규 업로드에 client uploadId, recordingId, chunkId, X-Content-SHA256, Content-Length 필수 | **구현됨+테스트됨** | `receiver.py:658-711`(Content-Length, X-Content-SHA256, Idempotency-Key[UUID, uploadId 역할], X-Recording-ID, X-Chunk-ID 모두 필수+UUID 형식 검증); 테스트 `tests/test_receiver_v1.py:275-306`(4개 헤더 각각 누락 시 400 파라미터라이즈) | "uploadId"라는 헤더명 대신 "Idempotency-Key"를 그 역할로 채택(`receiver_v1.py:344`) — 기능은 동등하나 명명 규약이 계획 문구와 다름(P2, 문서 정정 권고) |
| receipt 필드: uploadId, recordingId, chunkId, filename, size, sha256, status, receivedAt | **구현됨+테스트됨** | `receiver_v1.py:43-66`(`UploadReceipt`/`as_dict`, 요구 필드 전부 + 추가 `idempotencyKey`); 테스트 `tests/test_receiver_v1.py:243-250` | 없음(추가 필드는 상위 호환) |
| 영속 upload ledger, PK/unique를 userId 범위로, 사용자 간 hash dedupe 금지 | **구현됨+테스트됨** | `receiver_v1.py:159-211`(스키마: `PRIMARY KEY(user_id, idempotency_key)`, `UNIQUE(user_id, upload_id)`, `UNIQUE(user_id, filename)`, `UNIQUE(user_id, recording_id, chunk_id)` — 전부 `user_id` 스코프); 테스트 `tests/test_receiver_v1.py:327-352`(ingest 파일 이동 + 서버 재시작 후에도 receipt 유지) | 없음 |
| 같은 userId+uploadId+hash 재전송=200, 신규=201, 이름/uploadId 다른 hash=409 | **구현됨+테스트됨** | `receiver_v1.py:263-368`(`receive_upload`, `_same_upload` 245-261); 테스트 `tests/test_receiver_v1.py:237-272` | 없음 |
| UUID 임시파일 + DB unique 또는 per-upload lock으로 병렬 PUT 직렬화 | **구현됨+테스트됨** | `receiver_v1.py:282`(UUID temp), `:307-308`(`self._write_lock` + `BEGIN IMMEDIATE`); 테스트 `tests/test_receiver_v1.py:355-376`(20 스레드 동시 업로드, 201 정확히 1개, 잔여 `.part` 없음) — 재실행 3회 + Python 3.13/3.14 교차 검증 모두 안정 통과 | 전역(단일) 락이라 업로드/노트 전체가 함께 직렬화됨(정확성 문제는 없으나 처리량 병목 가능, P2) |
| streaming hash, 선언 size/hash 검증, flush/fsync→원자적 replace→ledger commit 순서 | **구현됨+테스트됨** | `receiver_v1.py:283-303`(스트리밍 해시+flush+fsync), `:339-341`(replace+디렉터리 fsync), `:343-363`(replace 이후 INSERT, 동일 트랜잭션) | 없음 |
| 0-byte, Content-Length 누락/오류, 짧거나 긴 body, 끊긴 전송, stale temp 정리 | **부분 구현** | 0-byte: `receiver.py:659-660`(`EMPTY_UPLOAD`) + 테스트 `test_receiver_v1.py:309-324`. 끊긴 전송(짧은 body): `receiver_v1.py:288-292`(`INCOMPLETE_BODY`) — **테스트 없음**. Content-Length 누락/초과: `receiver.py:400-416`(411/400/413) — **v1 전용 테스트 없음**. **stale temp 정리 정책은 코드 어디에도 없음** | stale temp GC 루틴 신규 구현 필요; INCOMPLETE_BODY/411/413/v1-507 회귀 테스트 추가 |
| 노트 목록에 안정적 id/folder/updatedAt/revision | **구현됨+테스트됨** | `receiver_v1.py:392-467`; 테스트 `tests/test_receiver_v1.py:379-399`(서버 재시작 후에도 id/revision 동일) | 없음 |
| note id 생성·rename·archive 안정성 규칙(opaque id store 없으면 rename 제외) | **구현됨(제한적)** | opaque id store 있음(`note_identities`, `receiver_v1.py:180-188`), rename API는 없음(V1 범위와 일치) | archive 후 동일 folder/name 재생성 시 과거 note_id가 "부활"하는 `ON CONFLICT...DO UPDATE SET archived_at=NULL`(`receiver_v1.py:392-427`) 동작의 테스트/문서화 없음(P2) |
| 노트 수정 If-Match 428/412/ETag | **구현됨+테스트됨** | `receiver_v1.py:545-573`; 테스트 `tests/test_receiver_v1.py:402-455`(428→200→412 순서 검증) | 없음 |
| revision 비교 + 임시파일 write/flush/fsync/replace를 동일 per-note 임계구역에서 수행 | **구현됨(코드 검증)+동시성 테스트 없음** | `receiver_v1.py:553-573`(`update_note`: `self._write_lock`+`BEGIN IMMEDIATE` 안에서 revision 비교와 `atomic_write` 모두 수행) | "두 클라이언트의 같은 revision 동시 수정에서 정확히 하나만 성공" 자체 테스트 미충족(현재는 순차 시나리오뿐) |
| 노트 경로 resolve 후 vault 내부 확인, symlink 외부 read/write 차단 | **구현됨+테스트 없음** | `receiver_v1.py:374-390`(`_note_path`: `is_symlink()` 체크 + `resolved_parent.is_relative_to(root)`) | symlink를 실제로 만들어 거부되는지 확인하는 테스트가 전무(`grep -ni symlink` 0건) |
| POST create 계약 (V1 포함 결정과 일치 여부) | **구현됨+테스트됨, 결정과 일치** | `docs/phase0-decision-record.md:21`("새 노트 — `/api/v1` POST 계약을 구현해 포함") ↔ `receiver.py:591-629`, `receiver_v1.py:512-543`; 테스트 `tests/test_receiver_v1.py:402-420` | 없음 |
| 삭제 archive/tombstone + 재시도 멱등 | **구현됨+테스트됨** | `receiver_v1.py:575-626`; 테스트 `tests/test_receiver_v1.py:458-485`(같은 If-Match 재삭제 시 동일 `archivedAt`, 파일 중복 생성 없음) | 없음 |
| `/apk/info` 구조화 JSON(versionCode/versionName/sha256/size/releaseNotes) | **구현됨+테스트 없음** | `receiver_v1.py:628-662`(`apk_info`), `receiver.py:518-527` | `/api/v1/apk/info`, `/api/v1/apk` 테스트가 전혀 없음 — Phase 2 자체 테스트 "구조화된 앱 버전 응답과 APK hash 불일치 검증" 미충족 |
| APK 다운로드 Bearer/단기 토큰 계약 + 장기 쿼리토큰 제거(V1 한정) | **구현됨+테스트 없음** | `receiver.py:528-534, 734-746`(`_handle_v1_apk_download`)는 `_authorized()`(헤더 Bearer)만 사용, 쿼리 토큰 미지원 — legacy `/apk`는 쿼리 토큰 유지(마이그레이션 기간 의도된 병행) | 테스트 없음 |
| 오류 JSON errorCode/message/requestId + 상태코드 의미 고정 | **구현됨+테스트 부분적** | `receiver.py:302-311`(`error:{code,message}` 중첩 + 최상위 `requestId`, `docs/receiver-api-v1.yaml:282-292`에 동일 구조 명문화); 상태코드 400/401/409/411/412/413/422/428/507/5xx 전부 실사용 확인. 테스트는 `code`/`message`만 확인(`tests/test_receiver_v1.py:190-198`), **`requestId` 존재 검증 없음** | requestId 검증 테스트 추가 권고. "errorCode"라는 계획 문구는 실제로는 `error.code`(중첩) 구조 — 계획서 표현 정정 대상 |
| legacy `/apk/info` 평문 유지 + 구조화 JSON을 versioned route로 분리 | **구현됨+테스트됨(legacy)/없음(v1)** | `receiver.py:453-455`(legacy 유지) vs `:518-527`(v1 분리) | 위 apk 테스트 갭과 동일 |
| Python 3.12 격리 환경 + 의존성 설치 명령 CI 고정 | **미구현(문서화 없음), 실행 가능성은 확인** | 이 머신에는 3.11/3.14(및 프로젝트 `.venv`의 3.13.12)만 존재; scratchpad venv(3.14)와 프로젝트 `.venv`(3.13.12, 읽기 전용 사용)로 각각 80 passed 확인 | `.github/workflows` 등 CI 설정 파일 전무. 재현 가능한 설치 명령과 3.12(또는 승인된 3.13) 고정이 문서/CI에 없음 |

### 4. 테스트 실행 결과

| 항목 | 내용 |
|---|---|
| 실행 명령 | `python -m pytest tests/test_receiver.py tests/test_receiver_v1.py -q` |
| Python 버전 #1 | 3.14.3 (scratchpad venv 신규 생성, `python-dotenv`/`pytest`/`cryptography` 설치) |
| Python 버전 #2(교차) | 3.13.12 (프로젝트 `.venv`, `uv` 관리 — 설치·수정 없이 읽기 전용 실행) |
| `tests/test_receiver.py` | **65 passed** (두 인터프리터 동일) |
| `tests/test_receiver_v1.py` | **15 passed** (두 인터프리터 동일; 병렬 업로드 동시성 테스트 3회 반복에도 flaky 없음) |
| 합계 | **80 passed, 0 failed** |
| 비고 | `>=3.12` 요구는 충족하나 계획서가 명시한 "Python 3.12" 자체는 환경에 없음(3.11, 3.14만 homebrew 존재). 프로젝트 디렉터리에는 아무것도 설치/수정하지 않음. `ruff check src/thinktank/receiver.py src/thinktank/receiver_v1.py` → All checks passed(참고) |

### 5. 이슈 목록

**P0 (착수 차단)**: 해당 없음. 핵심 정합성(멱등 업로드, 사용자 스코프 ledger, 노트 원자적 쓰기/충돌 판정, 경로 안전 가드)이 구현되어 있고 legacy 65개 테스트가 전부 유지되며 신규 15개 테스트가 모두 green이므로, Phase 3 착수를 막을 수준의 결함은 발견하지 못했다.

**P1 (G2 게이트 전 수정 권고)**
1. `stale temp 정리 정책` 미구현 — 프로세스 강제 종료(SIGKILL 등) 시 `.thinktank-v1.*.part` 임시 파일이 영구 잔존. Phase 2 명시 항목인데 코드가 전혀 없음.
2. `/api/v1/apk`, `/api/v1/apk/info` 테스트 전무 — 구현은 존재(`receiver_v1.py:628-662`, `receiver.py:518-534, 734-746`)하나 "구조화 버전 응답·APK hash 불일치 검증" 자체 테스트 미자동화.
3. 노트 동시 수정(optimistic concurrency) 다중 스레드 레이스 테스트 없음 — 코드 추적상 안전해 보이나(전역 lock+BEGIN IMMEDIATE) 업로드처럼 실측된 적 없음.
4. symlink/vault-escape 거부 테스트 없음 — 가드 코드(`receiver_v1.py:374-390`)는 있으나 실제 심볼릭 링크 차단 검증 부재.
5. 서로 다른 폴더의 동명 노트가 별개 id로 구분되는지 검증하는 테스트 없음 — 스키마(`UNIQUE(user_id, folder, name)`)는 지원하나 전용 테스트 부재.

**P2 (권고)**
1. 동일 `recording_id`+`chunk_id` 재사용 + 새 `Idempotency-Key` + 다른 파일명 조합에서 `_find_receipt`(`receiver_v1.py:232-243`)가 기존 행을 못 찾아 `uq_upload_receipt_chunk` 위반 → 구조화 409가 아닌 일반 500 노출 가능. `os.replace`(`:339-341`)가 INSERT보다 선행해 receipt 없는 orphan 파일 이론적 가능성.
2. `get_note`/`list_notes`의 사전 read 일부가 lock 밖 또는 symlink 점검 후 시점에 수행돼 로컬 공격자 전제의 TOCTOU 이론적 여지.
3. archive 후 동일 folder+name 재생성 시 예전 note_id "부활"(`ON CONFLICT ... SET archived_at=NULL`) — 의도 여부 테스트/문서화 없음.
4. Content-Length 누락(411)/초과(413)/미달(INCOMPLETE_BODY)·v1 507 회귀 테스트 없음(legacy 507만 monkeypatch 검증).
5. legacy API 제거 시점·사용량 확인이 Phase 0 결정 기록에 없음; legacy/v1 트래픽 구분 로그·카운터 없음.
6. 단일 사용자 모드에서 POST 노트 생성 `Location` 헤더가 `/api/v1/notes//<id>` 이중 슬래시 가능(`receiver.py:618`).
7. 전역 단일 `threading.RLock`(`receiver_v1.py:149`)이 업로드·노트 전체를 직렬화 — 처리량 병목 가능.
8. `docs/receiver-api-v1.yaml` ↔ 구현 간 자동 계약 검증(schemathesis 등)이 CI에 없어 조용한 드리프트 위험.
9. Python 3.12 정확 판 재검증은 인터프리터 부재로 미수행(`>=3.12`는 3.13/3.14로 충족).

### 6. 계획서 문구 수정 제안

1. "확정 점수" 표의 `현재 서버의 신규 안전 V1 대응률 | 55%` → **"약 94% (90~96% 구간, 잔여 테스트 갭 5~6건·stale temp 정리 정책 미구현)"**.
2. "서버 대응 판단" 표의 `신규 Compose 안전 운영 | 현재 불가` → **"가능 — 핵심 계약(ledger/revision/versioning) 구현·테스트 완료, APK v1·symlink·노트 동시성 레이스 회귀 테스트 미비"**.
3. "검토 시점 서버 테스트" 절의 `65 passed`/"Python 3.11" → **"2차 검토에서 Python 3.13.12/3.14.3 교차로 80 passed 확인, 정확한 3.12 인터프리터는 여전히 환경 부재"**.
4. Phase 2 완료 조건의 "RED→GREEN 자동 테스트로 고정" — git 이력이 없어 RED 선행 여부 검증 불가함을 각주로 명시.
5. Phase 2 완료 조건의 "G1/G2 통과" → **"G1 통과(계약 문서·fixture 존재), G2 부분 통과(잔여 회귀 테스트는 P1 목록 참조)"**로 조정.
6. "legacy API 제거 시점 연결" 태스크의 미이행을 명시하고 후속 보강 각주 추가.
7. 업로드 필수 계약 문구를 실구현에 맞게 정정: **"client가 생성한 Idempotency-Key(=uploadId 역할), recordingId, chunkId, X-Content-SHA256, Content-Length"**.

---

## 부록 B. Android 전문가 보고서 (전문)

검토 스냅샷: 2026-07-23 21:32 KST 이후 안정 상태. **검토 도중 작업 트리가 실시간으로 갱신되고 있었음**(예: `ReceiverApi.kt`/`SyncRepository.kt`/`receiver_v1.py`가 21:30~21:32에 recordingId/chunkId 계약을 추가하며 동시 수정됨). 아래 판정은 전부 최종 스냅샷을 재확인한 결과다. 정적 분석만 수행했으며 코드 수정·빌드 실행은 하지 않았다.

기준 문서: `dev-plan/implement_20260723_201248.md`
기준 코드: `android-app/` (main Kotlin 26개 + test 4개), `src/thinktank/receiver.py`, `receiver_v1.py`

### 1. 판정 요약

- **계획의 Android 기술 타당성: 타당(결함 없음, 개선 여지 1건).** microphone FGS 가시 상태 시작 전제, START_NOT_STICKY, WorkManager 제약, Room lease claim, Keystore AEAD, wall/monotonic clock 분리 모두 Android 14/15(API 34/35) 정책과 정합한다. 유일한 보완점은 청크 회전 gap을 기정사실로 둔 것 — `MediaRecorder.setNextOutputFile()`(API 26+)로 gap을 크게 줄일 수 있는 대안이 계획에서 검토되지 않았다(결함이 아니라 개선 여지).
- **구현 상태: "MainActivity와 Compose UI 없음, 19개 파일" 브리핑은 이미 낡았다.** 실제로는 MainActivity, 3탭 Compose UI 전체, 온보딩, 노트 상세/Markdown/위키링크, 7종 webp 에셋(asset-manifest.json 체크섬 전수 일치, 총 238KB), 폰트 3종, 서버 `/api/v1`(receiver_v1.py + tests/test_receiver_v1.py)까지 존재한다. Manifest가 참조하는 클래스는 전부 실재하며 **빌드를 깨는 참조 불일치는 발견되지 않았다.**
- **기존 구현의 계획 준수도: 약 75~80%.** 핵심 계약(사용자 시작 FGS, `.part` 제외, UUID 파일명, hash 일치 시에만 UPLOADED, lease claim, Keystore, 제외 권한 부재, /api/v1 계약 일치, 토큰 비노출)은 준수. 반면 P1 결함 5건(FAILED 상태 소실, 청크 중 시간 창 이탈 미검사, 오류 후 FGS 잔존, NoteConflict CASCADE 자멸, PENDING_DELETE 교착)이 있다.
- **"실제 Android 구현 완료율 0%" 문구 갱신안:** "Phase 1·3~7의 코드 골격 구현률 약 70~75%, 다만 G0~G3 게이트·실기기 PoC·자체 테스트 체크리스트 기준 완료율 0%(전 Phase 체크박스 미체크 상태 유지가 실제와 불일치)"로 이원 표기해야 한다. G0 결정 기록 없이 기능 구현이 진행된 것 자체가 "공통 진행 규칙" 위반 상태다.

### 2. 계획 타당성 표

| 항목 | 판정 | 근거 |
|---|---|---|
| "Android 14+에서 mic FGS는 앱이 보이는 상태에서 시작" 전제 | 타당 | API 31+ 백그라운드 FGS 시작 제한 + API 34 while-in-use 마이크 접근 제한의 결합 효과와 일치. 녹음 목적이면 사실상 가시 상태 시작이 필수 |
| foregroundServiceType="microphone" + FOREGROUND_SERVICE_MICROPHONE + RECORD_AUDIO 런타임 권한 | 타당 | targetSdk 34+ 필수 요건을 정확히 반영. 구현도 일치(Manifest:11-13,47) |
| START_NOT_STICKY로 무인 재시작 방지 | 타당 | NOT_STICKY는 프로세스 사망 후 시스템 재생성을 막고, 재생성돼도 while-in-use 제약으로 mic 접근 불가 — 이중 안전 |
| MediaRecorder stop→start 청크 gap을 측정·허용치 승인으로 처리 | 타당(개선 여지) | gap 존재 인정은 정직한 설계. 단 `setNextOutputFile()`(API 26+) + setMaxDuration/OnInfoListener로 near-gapless 회전 가능한 대안 미검토. 현 구현은 stop 후 SHA-256 해싱·메타데이터 추출까지 동기 수행해 gap을 더 키움 |
| WorkManager 30분 periodic + unmetered + backoff + unique work | 타당 | 최소 주기 15분 제약 충족, flex 15분 유효, 표준 패턴 |
| Room 상태전이 + lease 기반 claim 회수 | 타당(주석) | CAS UPDATE(WHERE state IN) + @Transaction으로 단일 active claim 강제는 성립. 단 "상태전이 자체를 unique index/transaction으로 강제"는 SQLite CHECK/트리거 없이는 불가능해 코드 규율 의존 — 계획 문구가 실제 강제 가능 범위보다 강함 |
| Keystore AEAD + allowBackup=false/dataExtractionRules | 타당 | AES-GCM Keystore + 백업 전면 제외는 표준. key invalidation 재로그인 흐름도 계획에 명시 |
| 시간 창 wall clock / 청크 duration monotonic clock 분리 | 타당 | DST·수동 시계 변경 하에서 올바른 역할 분리 |
| 시간 창은 "실행 중 FGS 내부"에서만 적용, 무인 복구 미지원 | 타당 | OS 제약을 지원되는 것처럼 표시하지 않는 원칙과 일치 |
| libs.versions.toml 버전 조합 | 대체로 정합, 1건 검증 필요 | Kotlin 1.9.24 ↔ Compose Compiler 1.5.14 매핑 일치, KSP 1.9.24-1.0.20 일치, Gradle 8.9 ↔ AGP 8.5.2 요건 충족, BOM 2024.09.00 ↔ navigation 2.8.0 ↔ lifecycle 2.8.4 정합, Room 2.6.1/WorkManager 2.9.1/Hilt 2.51.1 정합. **단 compileSdk=35 + AGP 8.5.2는 AGP 공식 지원 상한(API 34) 초과 — 경고/suppress 필요, AGP 8.6+ 권장(검증 필요)** |

### 3. 구현 대조표

| 계약 | 근거 (android-app/app/src/ 기준) | 판정 | 남은 작업 |
|---|---|---|---|
| 단일 active claim CAS/transaction 강제 | main/.../data/local/Daos.kt:76-105 (compareAndClaim + @Transaction claimNextReady), test/.../RecordingDaoTest.kt:42-92 | 준수 | — |
| lease 만료 회수 | Daos.kt:56-63 releaseExpiredClaims; LEASE_MS 15분 > 업로드 callTimeout 12분(di/AppModule.kt:45) | 준수 | receipt 존재 시 UPLOADED 수렴은 재업로드 왕복(서버 200)으로 대체 — 대역폭 최적화 여지 |
| 상태전이 제한의 스키마 강제 | Entities.kt:18-29 상태 상수, Daos.kt:41-42 updateChunk는 임의 상태 기록 가능 | 부분 준수 | 전이 검증 헬퍼 또는 UPDATE … WHERE state IN 패턴으로 축소 |
| `.part` 제외 + UUID 파일명 | recording/RecordingFileManager.kt:32-43 (`rec_<UTC>_<uuid>.m4a.part`→rename), Daos.kt:65-74 (READY/RETRY만 후보, 디렉터리 스캔 없음) | 준수 | — |
| 사용자 이벤트로만 서비스 시작 | ui/recording/RecordingScreen.kt:135-151 → ui/AppViewModels.kt:92-97 → recording/RecorderController.kt:14-19. 다른 시작 경로 없음, BootReceiver/알람 없음 | 준수 | — |
| START_NOT_STICKY | recording/RecorderService.kt:87 | 준수 | — |
| 알림 stop action | RecorderService.kt:295-313 | 준수 | — |
| 모든 종료 경로 wake lock 해제 | RecorderService.kt:90-97, 252-257, 153-163; 청크+2분 타임아웃(266-276) | 준수 | — |
| MediaRecorder 예외 시 finalize/격리 | RecorderService.kt:201-213(prepare 실패→quarantine+FAILED), 241-251(stop 실패→QUARANTINED) | 준수(P1 예외: 이슈 1·3) | FAILED 보존, 서비스 종료 처리 |
| 시간 창: 창 밖에서 청크 닫고 대기 | RecorderService.kt:112-126(대기 시 30초 재확인) — 녹음 중 내부 루프(130-141)는 창 이탈 미검사 | **결함** | 내부 루프에 창 검사 추가(기존 APK CHECK_MS=30s 동등성) |
| wall/monotonic 분리 | RecorderService.kt:114-118(LocalTime) vs 129-133(elapsedRealtime); RecordingWindow.kt + 자정 횡단 테스트 | 준수 | — |
| Manifest: mic 타입/권한/제외 권한/exported/cleartext | main/AndroidManifest.xml:11-13,44-48; MANAGE_EXTERNAL_STORAGE·USE_EXACT_ALARM·BOOT 부재; usesCleartextTraffic=false(:27); debug만 localhost cleartext | 준수 | — |
| Manifest 참조 클래스 실재 | .ThinkTankApplication/.MainActivity/.recording.RecorderService 전부 존재; WorkManager Configuration.Provider 일치 | 준수 | — |
| TokenCipher: Keystore AEAD, IV, invalidation | data/settings/TokenCipher.kt:19-45(GCM, IV prefix, 랜덤 IV), 복호 실패→null→재입력(평문 fallback 없음), deleteKey(:47-49) | 준수 | key() 동시 호출 경합 보호(P2) |
| 백업 제외 | AndroidManifest.xml:19-21 + data_extraction_rules.xml(cloud/device 전체 제외) | 준수 | — |
| SyncWorker: retry vs failure | worker/SyncWorker.kt:31-47; SyncRepository.kt:85-107(408/429/5xx→RETRY, 409→CONFLICT, 401→Failure, 기타→FAILED 후 다음 파일 진행) | 준수 | 422(HASH_MISMATCH) 별도 격리 검토 |
| unmetered 제약·unique work | worker/SyncScheduler.kt:24-62(manual=CONNECTED/KEEP, periodic=UNMETERED·30분·UPDATE) | 준수(정책 편차 P2) | metered에서 노트만 동기화하는 기존 동작(분류 A)과 달리 노트도 보류 — worker 분리 또는 결정 기록 |
| 서버 hash 일치 시에만 UPLOADED | SyncRepository.kt:54-83(sha256+recordingId+chunkId echo 검증 후 확정) | 준수 | — |
| Room 단일 진실 공급원 | RecordingRepository.kt(DB Flow만 노출), AppViewModels.kt(Repository Flow 수집) | 준수 | — |
| ReceiverApi ↔ receiver_v1.py 계약 | ReceiverApi.kt:69-101 ↔ receiver.py:652-724; notes GET/POST/PUT(If-Match 412/428)/DELETE, error envelope, apk/info JSON 일치; ReceiverApiTest가 헤더·echo 검증 | 준수 | 서버 `_same_upload`의 sha256 중복 비교 1줄 정리(서버측) |
| 토큰 로그/URL 비노출 | Log 호출 0건, HttpLoggingInterceptor 미장착, URL 쿼리 금지(AppPreferences.kt:108), 알림에 토큰 없음, PasswordVisualTransformation | 준수 | okhttp-logging 의존성 제거 권장(P2) |
| 노트 412 충돌 시 로컬 보존 | NotesRepository.kt:117-151(412→서버본 취득, CONFLICT+baseContent, 로컬 content 유지) | 준수(P1 예외: 이슈 4·5) | conflict 행 소실·PENDING_DELETE 교착 수정 |

### 4. 이슈 목록

**P0 — 없음.** 빌드를 깨는 참조·계약 불일치는 최종 스냅샷 기준 발견되지 않았다(검토 중반에 존재했던 ReceiverApiTest↔ReceiverApi 시그니처 불일치는 21:30~21:31 갱신으로 해소 확인).

**P1**
1. **세션 FAILED 상태가 즉시 STOPPED로 덮어써지고 lastError 소실** — RecorderService.kt:145-152에서 FAILED 기록 후 finally(153-163)가 무조건 `setSessionState(STOPPED)` 호출. Daos.kt:22-36이 chunkId/stoppedAt/lastError를 null로 덮어씀. UI(RecordingScreen.kt:191, 232) 오류 표시 무력화, `Failed(code, recoverable)` 상태 계약 위반.
2. **청크 녹음 중 시간 창 이탈 미검사** — RecorderService.kt:130-141 내부 루프가 청크 경계까지 창을 재평가하지 않음. 창 이탈 후 최대 chunkMinutes(120분 설정 시 2시간) 초과 녹음. Phase 5 "창 밖에서 청크를 닫고 대기" 계약 위반.
3. **비정상 종료 후 microphone FGS 잔존** — runRecordingSession 오류 종료 시 stopForeground/stopSelf 미호출(101-165). 녹음 없는 mic FGS와 ongoing 알림이 사용자 정지까지 유지. ACTION_STOP(68-75)만 정리 경로.
4. **NoteConflictEntity가 저장 즉시 CASCADE로 삭제** — NotesDao.upsert가 REPLACE(Daos.kt:162-166)라 기존 note 행 DELETE 후 INSERT, note_conflicts의 onDelete=CASCADE(Entities.kt:128-139)가 방금 insert한 conflict 행을 삭제. observeConflicts(Daos.kt:180-181)는 항상 빈 결과 — 충돌 보존 계약 자멸. @Update 또는 UPSERT로 교체 필요.
5. **PENDING_DELETE의 404/412가 노트 동기화 전체를 영구 차단** — NotesRepository.syncAll(64-76)에서 archive 예외가 forEach를 탈출해 매 동기화가 listNotes 전에 Failed 종료. Phase 7 "삭제 실패 후 다음 동기화 검증" 항목 위반.

**P2**
1. compileSdk/targetSdk 35 + AGP 8.5.2 조합(libs.versions.toml:2,7-8) — AGP 8.6+ 권장(검증 필요).
2. MediaRecorder OnError/OnInfoListener 미등록(RecorderService.kt:182-197) — 청크 중간 오디오 서버 오류 미감지, 죽은 녹음이 "기록 중" 표시 가능.
3. STOP 직후 START 연타 경합 — ACTION_STOP의 stopSelf()(no startId, :73)가 새 START 처리 후 실행되면 신규 세션 사망. stopSelf(startId) 또는 명령 큐 직렬화 필요.
4. rename 성공 후 DB 갱신 전 crash 시 완료 파일 orphan 유실 — reconcile(RecordingFileManager.kt:69-88)에 동명 `.m4a` 승계 로직 없음(서버 receiver_v1의 orphan 승계와 대조적).
5. UploadAttemptEntity가 "STARTED"로만 남고 종결(outcome/finishedAt/errorCode) 미기록(SyncRepository.kt:47-53).
6. 수동 동기화 ExistingWorkPolicy.KEEP(SyncScheduler.kt:33) — backoff 대기 중 수동 탭 무시. REPLACE/APPEND_OR_REPLACE 검토.
7. 자동 동기화 UNMETERED 제약이 노트 다운로드까지 보류 — 기존 APK는 metered에서 노트만 동기화(분류 A). worker 분리 또는 G0 결정 기록 필요.
8. TokenCipher.key() 동시 호출 시 키 재생성 경합 가능(TokenCipher.kt:51-67).
9. 미사용 의존성: security-crypto, okhttp-logging(libs.versions.toml:19-20, app/build.gradle.kts:149,154) 제거 권장.
10. 알림이 동기화 완료 1종뿐(SyncWorker.kt:50-83) — 계획의 3종 채널·deep link·dedupe 미구현. 알림 탭 시 onNewIntent 미처리로 MainActivity 중복 적층(MainActivity.kt:21-39).
11. ReceiverApi.executeJson 응답 크기 제한이 contentLength=-1(chunked)일 때 우회(ReceiverApi.kt:213-219).
12. 청크 회전 gap에 SHA-256 해싱·MediaMetadataRetriever 동기 포함(RecorderService.kt:216-258) — 다음 청크 시작 후로 지연 가능.
13. NavigationBar 이중 인셋 패딩(ThinkTankApp.kt:90-92, M3 1.3은 자체 인셋 처리).

### 5. 구조 드리프트 목록 (계획 "예상 구조" 대비)

**구현됐으나 배치가 다른 것(계획 위반 아님, 병합 단순화):** AppNavigation→ui/ThinkTankApp.kt 인라인, Entity/DAO 단일 파일, UploadApi·NotesApi·UpdateApi→ReceiverApi 단일, data/security→data/settings/TokenCipher, MarkdownRenderer·WikiLinkParser→NotesScreen 내장(+MarkdownParserTest), UploadWorker·NotesSyncWorker→SyncWorker 단일(P2-7의 원인), RecorderNotification→Service 인라인, AppNotificationCoordinator·AppUpdateRepository·domain/model 부재(알림은 SyncWorker, 버전 확인은 SettingsViewModel에 인라인), ui/sync→RecordingScreen 진단 행+Notes 상단 action으로 축소.

**미구현(잔여 작업):** 노트 폴더 접기/펼치기, 폴더별 상한(`1 wiki=100, 30-ideas=200, 10-daily=60`)·daily 필터 계약, 충돌 해소 전용 화면(NoteConflict 목록 UI), 알림 3종 분리·deep link·dedupe, dev/staging/production flavor 경계, 저장 공간 경고·보존 정책, SyncCursorEntity 활용.

**계획 문서 관리 드리프트:** Phase 0~8 체크박스 전부 미체크인 채 Phase 3~7 수준 코드 존재 — 진행 규칙·G0 게이트 위반 상태. "실제 Android 구현 완료율 0%"·확정 점수 표 재산정 필요. 시간 창 기본값: 계획 "기본 07:00~22:00 적용" ↔ 구현 scheduleEnabled=false 기본(AppPreferences.kt:26-28) — 합리적이나 결정 기록 필요.

**권장 다음 단계(우선순위순):** P1 1~3(RecorderService 상태·수명주기) 및 P1 4~5(NotesRepository) 수정 → 계획서 체크박스·완료율 문구 소급 갱신·G0 기록 보강 → AGP 8.6+ 상향 검증 → Phase 5 실기기(Android 14/15) PoC와 청크 gap 측정 승인.

---

## 부록 C. 디자인·에셋·라이선스 전문가 보고서 (전문)

검토 대상: 계획서 디자인 절 + Phase 0/1/8, `docs/design/` 6종 + `licenses/`, `android-app/app/src/main/res/` 실물 에셋. 전 과정 읽기 전용.

주의: 검토 도중(21:29) 저장소가 동시 수정되는 것을 관측했다. `res/mipmap-anydpi-v26/`의 adaptive icon 2종이 `res/mipmap-anydpi/`로 이동했고 `values/colors.xml`도 같은 시각에 갱신됐다. 본 보고서는 21:29 이후 최종 관측 상태 기준이다.

### 1. 판정 요약

| 영역 | 판정 |
|---|---|
| 디자인 계획 타당성 | **타당(상)** — UX 의도→금지 원칙→토큰→에셋 예산→권리 규칙→QA 게이트가 일관된 체계로 연결. 비-AI 원칙이 검증 가능한 금지 목록으로 구체화됨. 단 QA 게이트 4개 항목의 측정 방법 모호(하단 참조) |
| WebP 7종 실물 | **100% 통과** — 개별 예산·총예산·해상도·checksum 모두 계획/manifest 일치. 총 243,686B(238KB)로 목표 1.5MiB의 16%. 시각 검수에서 문자·로고·손·가짜 UI·네온 요소 0건 |
| asset-manifest.json 스키마 | **부분 준수** — 요구 6필드 중 `modification`/`SHA-256`만 완전. `author`/`license`/파일별 `generatedAt` 누락, 생성형 필수 기록 **prompt 0건**(ASSET_LICENSES.md 자체 서술과 모순) |
| 폰트 라이선스 | Pretendard **적합**(OFL 전문+저작권 고지 동봉, name table에도 OFL 명시). MaruBuri **저장소 증빙 부족(P1)** |
| 색 대비 | 다크 5조합 전부 AA 본문 통과. 라이트는 moss 1건 본문 기준 미달(4.48:1). 구현부의 다크 상수 하드코딩으로 라이트 화면 2곳 AA 실패 |
| 계획서 문구 정확성 | 실물과 3건 드리프트: Pretendard Variable→정적 otf 2종, mipmap-anydpi-v26→mipmap-anydpi, texture 2~4%→코드 12% |

종합: **계획 승인 가능, 에셋은 P1 4건 해소 조건부 통과.** P0(권리 침해·예산 초과·위조 증빙) 없음.

### 2. 에셋 실측표

WebP 7종 — `android-app/app/src/main/res/drawable-nodpi/` (계획 지정 위치 일치)

| 파일 | 실측 크기 | 실측 해상도 | 계획 예산 | manifest 대조 |
|---|---:|---|---|---|
| hero_recording_chamber.webp | 39,604 B | 1080×1080 | ≤300KB, 1080×1080 | bytes/sha256/dimensions 모두 일치 |
| texture_archive_paper.webp | 1,742 B | 512×512 | ≤60KB, 512×512 | 일치 — 단 픽셀 통계상 사실상 평면(P2-3) |
| empty_notes_desk.webp | 37,558 B | 960×720 | ≤180KB, 960×720 | 일치 |
| empty_sync_bridge.webp | 18,566 B | 960×720 | ≤160KB, 960×720 | 일치 |
| error_server_offline.webp | 26,026 B | 960×720 | ≤180KB, 960×720 | 일치 |
| onboarding_record.webp | 28,036 B | 1080×1350 | ≤250KB, 1080×1350 | 일치 |
| onboarding_archive.webp | 92,154 B | 1080×1350 | ≤250KB, 1080×1350 | 일치 |
| **합계** | **243,686 B (238KB)** | — | 목표 1,572,864 / 상한 2,621,440 | manifest `totalRasterBytes: 243686` 정확히 일치 |

- SHA-256 7건 전부 실측값과 manifest 기재값 일치, 누락·불일치 0건.
- 시각 검수: 모두 계획 아트 디렉션과 부합. 문자·로고·손·가짜 파형·가짜 UI·네온/보라 그라데이션 0건.
- CI 게이트 실재: `android-app/app/build.gradle.kts:172-200`의 `verifyRasterAssets` task가 2.5MiB 상한·manifest 존재·파일별 sha256·에셋 7개 고정을 검사하고 preBuild에 연결 — Phase 1 태스크 "2.5MB 초과 시 빌드 실패"는 구현 상태.

폰트 실물 — `android-app/app/src/main/res/font/`

| 파일 | 크기 | 내부 메타데이터 |
|---|---:|---|
| pretendard_regular.otf | 1,574,352 B | family "Pretendard", © 2023 Kil Hyung-jin, name table에 OFL 1.1 명시, 정적 |
| pretendard_semibold.otf | 1,583,704 B | family "Pretendard SemiBold", 동일 OFL 명시, 정적 |
| maruburi_semibold.otf | 742,176 B | family "MaruBuriOTF", © NAVER Corp./NAVER Cultural Foundation Corp., **name table에 라이선스 항목 없음**, 정적 |

폰트 합계 3,900,232B(3.72MiB) — raster 예산 대상은 아니나 APK 풋프린트 최대 항목. 폰트는 asset-manifest.json 미등재.

### 3. 라이선스 판정표

| 항목 | 요구 증빙 | 실제 증빙 | 판정 |
|---|---|---|---|
| Pretendard 2종 | 공식 배포 파일 + OFL 전문/저작권 고지 동봉 | `docs/design/licenses/PRETENDARD-OFL-1.1.txt`에 저작권 고지+OFL 1.1 전문 완결. 폰트 내부 name table에도 OFL 명시. 원본 무수정이라 Reserved Font Name 저촉 없음 | **적합**. 단 릴리즈 APK에는 docs/가 포함되지 않으므로 앱 내 고지 동선 필요(P2-9) |
| MaruBuri 1종 | 앱 임베딩·재배포 허용의 저장소 내 증빙 + 라이선스 전문 보존 | ASSET_LICENSES.md에 NAVER 안내 페이지 URL 링크만. licenses/에 전문 없음, 폰트 내부에도 없음. 공식 페이지는 검토 환경에서 접근 차단. 2차 출처(눈누)는 OFL 표기·임베딩/재배포 허용·단독 판매 금지 안내 | **오프라인 증빙 부족 — P1**. 임베딩 허용 가능성 높으나 저장소에 입증 전문 없음 — 자체 규칙 위반 상태 |
| 생성형 WebP 7종 | source/author/license/generatedAt/modification/SHA-256 + model·tool·prompt·보정 내역 | manifest에 sha256·modification·dimensions·bytes·sourceId·최상위 generator/generatedAt만. **prompt 0건** — ASSET_LICENSES.md 서술과 모순. author/license/파일별 일시 없음 | **부분 준수 — P1** |

### 4. 대비 계산표 (WCAG 상대 휘도 기반 실측)

| 조합 | 대비율 | AA 판정 |
|---|---:|---|
| Dark: paper #EEE8DC on ink #151614 (본문) | 14.88:1 | AAA |
| Dark: fog #A9AAA3 on #151614 | 7.75:1 | AAA |
| Dark: copper #C07148 on #151614 | 4.92:1 | AA 본문 가능 |
| Dark: error #D06A60 on #151614 | 5.11:1 | AA 본문 가능 |
| Dark: moss #7E8C78 on #151614 | 5.11:1 | AA 본문 가능 |
| Light: ink #24241F on #F7F3EA | 14.08:1 | AAA |
| Light: fog #6E706A on #F7F3EA | 4.53:1 | AA 본문 통과(경계값) |
| Light: copper #A95632 on #F7F3EA | 4.67:1 | AA 본문 가능 |
| Light: error #A73F39 on #F7F3EA | 5.56:1 | AA 본문 가능 |
| **Light: moss #66745F on #F7F3EA** | **4.48:1** | **본문 AA 미달** — 대형 텍스트·UI 아이콘(3:1)만 허용 |
| 참고: ink on copper(다크 버튼) | 4.92:1 | AA 본문 |
| 참고: white on copper(라이트 버튼) | 5.17:1 | AA 본문 |
| 참고(구현): copper #C07148 on paper #EEE8DC — WikiLink | 3.02:1 | 본문 AA 실패 |
| 참고(구현): copper #C07148 on light bg #F7F3EA | 3.33:1 | 본문 AA 실패 |
| hairline #343530/#DDD6CA | 1.47/1.30:1 | 장식 divider 한정(계획 용도 부합) |

- 색 단독 구분 금지 원칙: 계획·문서·구현 모두 준수 방향(StatusPill 색 dot+라벨, SoundThread 상태별 contentDescription, 장식 이미지 contentDescription=null). sync 상태 아이콘 표준세트는 미구현 — Phase 4 이후 재검 필요.
- 토큰 대조: `ui/theme/Theme.kt:16-61` 다크/라이트 14색 계획 토큰과 전부 일치(dynamic color 미사용 포함). `values/colors.xml`은 archive_ink/archive_copper 2건(시스템 바용, 다크 고정), values-night 부재로 라이트 모드에서 시스템 바만 다크로 남음.

### 5. 이슈 목록

**P0 — 없음.**

**P1**
1. **MaruBuri 라이선스 전문 미동봉** — Phase 8 "라이선스 누락 시 배포 차단" 기준으로 현재 배포 불가 상태. NAVER 공식 전문을 취득일과 함께 `licenses/MARUBURI-LICENSE.txt`로 추가.
2. **asset-manifest.json 필드 스키마 미달 + 문서 간 모순** — author·license·파일별 generatedAt·prompt 누락. ASSET_LICENSES.md 서술과 실물 모순.
3. **라이트 테마에서 다크 상수 하드코딩으로 AA 실패** — `ui/notes/NotesScreen.kt:397`(WikiLink ArchiveCopper on paper 3.02:1), `ui/settings/SettingsScreen.kt:226,259` 등. 라이트에서는 colorScheme.primary(#A95632) 사용으로 교체 필요.
4. **Light moss 팔레트 값 본문 AA 미달** — #66745F → #5F6D58(4.97:1) 또는 #637159(4.70:1) 조정 권고.

**P2**
1. 아이콘 디렉터리 드리프트 + 빈 mipmap-anydpi-v26 잔존(21:29 이동 관측). adaptive icon 구성·notification icon 규칙 자체는 적합.
2. adaptive icon 전경 safe zone 초과(ic_launcher_foreground.xml, y≈96 > 안전 원 y≤87) — 원형 마스크에서 하단 잘림 예상.
3. paper texture 사실상 평면(stddev 0.96) + NotesScreen.kt:319의 alpha 0.12가 계획 2~4% 초과.
4. ImageState 공통 wrapper의 loading/missing fallback 미구현(Components.kt:178).
5. SoundThread reduced-motion 대체 미구현(Components.kt:53-91).
6. 녹음 타이머 tabular number(fontFeatureSettings "tnum") 미적용.
7. verifyRasterAssets가 파일↔checksum 페어링을 보장하지 않고 1.5MiB 목표 경고 없음(build.gradle.kts:189-193).
8. 폰트 3.72MiB — 서브셋 시 OFL Reserved Font Name 규칙(개명 필요) 문서 병기.
9. 앱 내 폰트/OSS 고지 동선 부재 — 설정 "앱 정보"에 고지 화면 태스크 추가 권고.
10. XML 테마 라이트 미지원(values-night 부재, statusBarColor 다크 고정).

**QA 게이트 모호 4건**: (a) "대형 폰" 해상도 미지정 (b) "cold start hero decode" 지표·임계 없음(Macrobenchmark TTID/TTFD 수치화 필요) (c) "asset 누락 fallback"은 번들 리소스 특성상 재현 불가 — "decode 실패 주입 테스트"로 재정의 필요 (d) "AI 앱처럼 보이는가" 휴리스틱의 판정자·증빙 보존 방식 미정.

### 6. 계획서 문구 수정 제안

1. 타이포그래피 절: "Pretendard Variable 400/500/600/700" → "Pretendard 정적 otf(Regular 400, SemiBold 600)", MaruBuri "600/700" → "SemiBold 600 단일".
2. 산출물 구조: 폰트 파일명 `.otf`로 정정, mipmap 디렉터리 하나로 통일(권고: -v26 복원 후 빈 디렉터리 제거), `docs/design/licenses/` 추가.
3. 에셋 소싱·권리 규칙: manifest 필드명을 실물 스키마와 단일화(sourceId·파일별 generatedAt·model/tool·prompt 필수).
4. 컬러 시스템: Light moss 값 조정 또는 "대형/아이콘 전용" 단서 추가.
5. 디자인 QA 기준: 3번째 해상도 수치 고정, cold start 측정 도구·임계 명시, fallback 항목 재정의, 휴리스틱 판정자·증빙 방식 1줄 추가.
6. 이미지 에셋 계획: texture 항목에 grain 소실 검사 추가 또는 평면 배경색 대체 명시.

---

## 부록 D. QA 프로세스 전문가 보고서 (전문)

**대상 문서**: `dev-plan/implement_20260723_201248.md` (1117줄 전문 정독)
**대조**: `docs/phase0-decision-record.md`, `docs/design/` 6종
**검토 시점**: 2026-07-23 21:39 KST 스냅샷 (저장소가 검토 도중에도 계속 갱신됨을 직접 확인)
**방식**: 읽기 전용. 서버 회귀 테스트는 scratchpad에 python-dotenv를 설치해 1회 실행(저장소 변경 없음).

### 1. 판정 요약

**핵심 결론: 문서의 체크박스·"실제 Android 구현 완료율 0%" 서술은 저장소 현실과 명백히 배치된다. 실제로는 Phase 0~3 수준의 실질 구현이 상당 부분 완료되어 있고 Phase 5~7 요소까지 코드가 존재하며, 서버는 즉시 실행 가능한 회귀 테스트가 GREEN 상태다.**

1. **저장소가 감사 도중에도 실시간으로 갱신됨을 직접 확인.** `receiver_v1.py`·`ReceiverApi.kt`·`ReceiverApiTest.kt`의 mtime이 검토 세션 중 여러 차례 변경(21:01→21:31에 recording_id/chunk_id 컬럼 추가). 계획 문서(20:52 저장)는 "박제"된 채 코드는 전진 중 — 체크박스 드리프트는 일회성 누락이 아니라 **구조적으로 진행 중인 드리프트**다.
2. **"실제 Android 구현 완료율 0%"는 사실이 아니다.** Kotlin 소스 29개(빌드 산출물 제외), Room 스키마, Hilt DI, FGS, WorkManager, OkHttp, Keystore, Compose 3탭 UI 존재. 실제 빌드 결과물: `app-debug.apk`(22MB), `lint-results-debug.txt` "0 errors, 71 warnings", 유닛 테스트 4클래스 전부 성공.
3. **서버는 직접 재현·검증.** `PYTHONPATH=src` + python-dotenv 설치로 `test_receiver.py`+`test_receiver_v1.py` 실행 → **80 passed**(21:33 KST). 계획서가 언급한 python-dotenv 부재 문제도 동일 재현 — G1의 "재현 가능한 의존성 설치 명령" CI 고정이 여전히 없음을 재확인.
4. **프로세스 규칙 위반이 문서 자체 내에 존재.** "공통 진행 규칙"(순차 진행)과 "최종 착수 판정"(Phase 0/1/2 동시 시작 가능)이 같은 문서 안에서 직접 충돌. 실제 구현은 이 예외마저 넘어 Phase 2 GREEN 본구현, Phase 3/5/6/7 코드까지 진행.
5. **게이트 표와 완료 조건 연결이 성글다.** G3는 어느 Phase 완료 조건에도 이름으로 인용되지 않음(Phase 2만 G1/G2, Phase 8만 G0~G4). Python 3.12 재검증 요구는 산문에만 있고 게이트 표에 없음.

**종합**: 문서 신뢰도 낮음(기록-현실 불일치), 프로세스는 "선언한 순서보다 빠르게, 게이트 승인 없이 실질 구현이 진행된 상태". 다만 코드 품질 자체는 계획서의 P0 요구사항을 상당히 충실히 구현 — 정확한 진단은 "계획이 부실하다"가 아니라 "**진행 기록과 게이트 절차가 실제 구현 속도를 따라가지 못하고 있다**".

### 2. 드리프트 표

| Phase | 문서 상태 | 저장소 증거 | 실제 판정 |
|---|---|---|---|
| Phase 0 | 전부 미체크 | phase0-decision-record.md(72줄): 태스크 15개 중 13개 반영(§5 참조 2개 gap). reference-board.md로 자체 테스트 6번 충족. wireframe 산출물 전무(자체 테스트 7번 미충족) | **부분 진행(완료 근접)** — 실행 증거(병행 설치 테스트, wireframe) 부재, 순서 위반 정황 |
| Phase 1 | 미체크 | docs/design 6종, Gradle 프로젝트 정상, Hilt+단일 Activity+M3 theme+Navigation, 폰트·WebP 실물, verifyRasterAssets 자동 게이트 구현. 실빌드: app-debug.apk, lint 0 errors/71 warnings, 유닛 4클래스 성공 | **부분 진행(완료 근접)** — "CI 명령 기록" 산출물 부재, 실기기(26/34+) 검증 로그 없음 |
| Phase 2 | 미체크 | receiver_v1.py(660줄+: 영속 원장, note_identities, symlink 차단, If-Match/ETag, atomic_write), receiver.py `/api/v1` 라우팅, test_receiver_v1.py(562줄). 직접 실행 80 passed | **부분 진행(가장 진척)** — Python 3.12 CI 고정 없음(uv.lock 존재 — 재현 설치 부분 충족) |
| Phase 3 | 미체크 | Entities.kt(6 엔티티, FK+unique), Daos.kt(compareAndClaim CAS, releaseExpiredClaims), TokenCipher.kt(Keystore AES/GCM), network_security_config(localhost 한정), RecordingDaoTest(동시 claim·lease 회수 Robolectric GREEN) | **부분 진행** — migration 재실행/backup-extraction 자동 검증 증거 없음 |
| Phase 4 | 미체크 | ThinkTankApp.kt(3탭+note/{id}+온보딩), RecordingScreen, NotesScreen(586줄), SettingsScreen(354줄), ComposeScreensTest(2건) | **부분 진행** — androidTest 실행 증거(connectedAndroidTest 리포트) 전무, 스크린샷/접근성 QA 없음 |
| Phase 5 | 미체크 | RecorderService(START_NOT_STICKY, commandMutex, wake lock, stop action), RecordingWindow+Test(경계 3테스트 GREEN) | **부분 진행(핵심 미충족)** — 완료 조건 필수인 Android 14/15 실기기 검증 증거 전무 |
| Phase 6 | 미체크 | SyncRepository(지수 backoff, hash 재검증), SyncWorker(retry/success/failure), DAO 동시성 테스트 선반영 | **부분 진행** — PTS gap 실측, 1시간·soak 리소스 측정 전무 |
| Phase 7 | 미체크 | NotesRepository(revision 충돌 로컬 보존+NoteConflict 기록, archive/pending-delete), notify()(POST_NOTIFICATIONS 체크, 토큰 미포함), MarkdownParserTest GREEN | **부분 진행** — 위키링크/충돌 화면 등 깊이 검증 부족 |
| Phase 8 | 미체크 | release 서명 분기 골격만. release APK·connectedCheck·매트릭스·soak 증거 전무 | **미착수** |

### 3. 게이트·규칙 위반 목록

**[P0] 심각**
1. **문서 자기모순(순서 규칙 vs 착수 판정)** — "공통 진행 규칙"(:610)과 "최종 착수 판정"(:441)이 직접 충돌. 예외 발동 조건·범위 미정의로 "규칙이 규칙을 무효화".
2. **G1 게이트 실질 우회** — G1 미통과 시 "서버·Repository 본 구현 보류"(:450)인데 GREEN 구현이 이미 완료(80 tests 직접 확인). ※ 오케스트레이터 주석: OpenAPI 계약 문서(docs/receiver-api-v1.yaml)는 21:32에 생성돼 물적 조건은 사후 충족 — 남은 것은 승인 기록·CI 고정.
3. **Phase 순서 위반 정황(mtime 근거, git 부재로 확정 불가)** — test_receiver_v1.py(20:59)·receiver_v1.py(21:01) 수정이 phase0-decision-record.md·docs/design(21:03) 저장보다 앞섬. Phase 0 자체 테스트 8번("G0 기록 없으면 Phase 2 이후 시작 금지") 미충족 가능성. 정황 증거로 취급.
4. **"실제 Android 구현 완료율 0%" 사실 오류** — (:425) 현재 시점 독자에게 명백한 오정보.

**[P1] 중요**
5. G3 게이트가 어느 Phase 완료 조건에도 미인용 — 통과 판정 시점 불명확.
6. Python 3.12 재검증 요구가 게이트 표에 없음(산문 :472에만 존재). 본 검토에서도 기본 환경 collection 실패 재현(dotenv 부재) 후 80 passed.
7. 구현 태스크↔자체 테스트 1:1 미대응 — Phase 4 "일회성 Snackbar/Navigation 이벤트 중복 방지"(:874)의 자체 테스트 부재.
8. Phase 0 결정 기록 부분 커버리지 — 태스크 7번(동기화 action 배치)·15번(서명 rotation 정책) 대응 문구 없음.

**[P2] 경미**
9. "리뷰 반영 우선순위" 표의 완료 위치가 한 셀에 뭉뚱그려져 Phase 1·4 누락(3탭/전환 정책 행).
10. QA 관점 항목 수 표기(16 vs 실제 17).
11. ASSET_LICENSES.md의 "프롬프트 기록" 서술 vs asset-manifest.json 실물(prompt 없음, opaque sourceId만) 불일치.

### 4. 모호한 자체 테스트 목록

수치가 이미 있는 항목(커버리지 80/70/90%, 1.5/2.5MB, 병렬 20건, START/STOP 20회, "0건" 계열)은 인정. 이외:

| 항목 | 문제 | 측정 가능한 수정안 |
|---|---|---|
| Phase 5 완료 조건 "안정적으로 동작한다"(:945) | 지속시간·반복·허용 실패율 없음 | "대표 기기 3종 × 4시간 연속 × 3회, 청크 손실 0·FGS 강제 종료 0·wakelock 미해제 0" |
| "휴리스틱 리뷰"(:891, :1090, :400) | 절차(인원, 체크리스트, 이견 처리) 미정의 | "독립 리뷰어 2인 사전 정의 10항목 개별 판정, 불일치 시 3인째, 근거 스크린샷 보존" |
| Phase 6 "1시간 메모리·배터리·저장 측정"(:988) | 합격 임계 없음 | "메모리 증가 <50MB, 배터리 <5%/h, 저장 증가 이론치 ±5%" |
| Phase 6 soak(:989) | 상·하한 없음 | "표피 43°C 이하, 8시간 배터리 <25%, OOM/ANR 0건" |
| Phase 8 "대표 property·fuzz"(:1087) | 반복·시드 수 불명 | "seed 100개 이상, 실패 seed 아티팩트 보존" |
| Phase 0 자체 테스트 다수 "검토한다/확인한다" | 승인자·산출물 형식 미지정 | 각 항목에 "승인자 역할 + 산출물 경로" 명시 |

### 5. P0 추적성 표

| P0 항목 | 문서상 완료 위치 | 실제 대응 | 저장소 증거 | 추적 가능성 |
|---|---|---|---|---|
| 3탭/전환 정책 | Phase 0,2,3,5,6 | Phase 0+**1**+**4** | phase0-decision-record, ThinkTankApp.kt 3탭 실장 | 부분(표에 Phase 1·4 누락) |
| legacy+v1 계약 | Phase 2 | Phase 2 | receiver.py `/api/v1` 라우팅, test_v1_and_legacy_contracts_work_on_the_same_server | 추적 가능(80 tests 실측) |
| 영속 upload ledger | Phase 2 | Phase 2 | upload_receipts 테이블, 재시작 생존 테스트 | 추적 가능 |
| note revision 원자성 | Phase 2 | Phase 2 | update_note(BEGIN IMMEDIATE+If-Match 412), atomic_write | 추적 가능 |
| Room/Worker/FGS invariant | Phase 3,5,6 | 동일 | Daos.kt CAS, RecorderService NOT_STICKY, SyncWorker | 추적 가능(세부 매핑은 재구성 필요) |
| 측정 가능한 청크 경계 | Phase 5,6 | 동일 | RecordingFileManager(size/sha256/duration 기록)까지만 | 인프라만 추적 가능 — **실측치·승인 기록은 미착수** |

### 6. 문서 현행화 권고 (13건)

1. "실제 Android 구현 완료율 0%"(:425) 삭제 또는 시점 명시("계획 수립 시점 20:12 기준"), Phase별 상태 표 편입.
2. Phase 상태 요약(:626~634) 체크박스를 "완료/부분 진행/미착수" 3단계로 갱신 — 최소 Phase 2는 "부분 진행(핵심 구현 완료, 계약 문서·CI 고정 잔존)".
3. "공통 진행 규칙"(:610)과 "지금 시작 가능"(:441) 모순 해소 — 예외 범위 명시적 재작성.
4. G1/G2 게이트 표에 "Python 3.12 격리 재검증" 명시.
5. G3를 Phase 3·5·6 완료 조건에 명시적 인용.
6. Phase 2 완료 조건 체크 전에 ① 계약 문서(또는 "테스트가 계약 문서 겸함" 결정) ② CI 고정 파일 산출물 추가.
7. Phase 1 "CI 명령 기록"(:724) 산출물 실제 생성 — 빌드 증거는 이미 존재, 문서화만 필요.
8. "리뷰 반영 우선순위" 표의 완료 위치를 P0 항목별로 분리(3탭 행에 Phase 1·4 추가).
9. 모호한 자체 테스트에 수치·절차 추가(§4).
10. phase0-decision-record.md에 동기화 action 배치·서명 rotation 승인 보완.
11. grayscale wireframe 산출물 생성 또는 생략 예외를 결정 기록에 명시.
12. asset-manifest.json에 prompt 필드 추가 또는 ASSET_LICENSES.md 서술 수정.
13. 문서 서두에 "코드 동기화 확인 시각" 필드 추가 — 근본 문제는 "진행 기록과 실제 구현 속도 간 동기화 메커니즘 부재".
