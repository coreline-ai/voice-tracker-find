# ThinkTank Receiver — GCP Cloud Run 전환 준비도

작성 일시: `2026-07-23 22:49 KST`  
판정: **현재 Python Receiver를 그대로 Cloud Run 운영 배포하는 것은 NO-GO**

이 문서는 Android 앱의 `HTTPS base URL` 설정이 Google Cloud 원격 주소도 받을 수
있다는 점과, 현재 파일·SQLite 기반 Receiver가 Cloud Run에서 그대로 영속 운영될 수
있다는 점을 구분한다. 이번 V1 범위에는 GCP 인프라 구축·데이터 저장소 이관이 포함되지
않는다.

## 현재 코드와 가능한 연결

```text
Android Compose app
  └─ public-CA HTTPS base URL + Bearer token
       └─ Cloud Run ingress (TLS 종료)
            └─ Receiver V1 API
                 ├─ upload receipt / note revision metadata
                 ├─ audio object
                 └─ processing queue / worker
```

- release 앱은 HTTPS URL만 저장하도록 검증하고, `Authorization: Bearer` 및 V1의
  idempotency/recording/chunk/SHA-256 헤더를 그대로 보낸다.
- 현재 `src/thinktank/receiver.py`는 `0.0.0.0`에 바인딩할 수 있고, `--port`로
  Cloud Run의 `PORT`를 전달할 수 있다.
- Cloud Run은 ingress container가 `0.0.0.0`의 주입된 `PORT`를 수신해야 하며,
  HTTPS TLS는 Cloud Run에서 종료된다. 따라서 Cloud Run Receiver에
  `RECEIVER_CERT`를 넣어 이중 TLS를 구성하면 안 된다. [Cloud Run container
  contract](https://cloud.google.com/run/docs/container-contract)

## 그대로 배포할 수 없는 이유

| 현재 구현 | Cloud Run에서의 문제 | 필요한 대체 |
|---|---|---|
| `INGEST_DIR`에 파일을 원자적 rename | 컨테이너 파일 시스템은 인메모리이고 instance 종료 시 사라진다. | Cloud Storage object 업로드를 요청 경로에서 완료·검증한다. |
| `receiver-v1.sqlite3` receipt/identity ledger | 여러 instance 간 공유되지 않고 instance 종료 시 유실된다. | Cloud SQL(PostgreSQL 권장) 또는 동등한 트랜잭션 DB에 unique/revision 제약을 구현한다. |
| vault의 Markdown 파일 read/write/rename | 다중 instance 동시 쓰기에서 revision/원자성 보장이 없다. | note 본문·revision을 DB 트랜잭션으로 관리하거나 단일 writer를 둔다. |
| `threading.Timer`의 150초 후 pipeline spawn | request 기반 Cloud Run에서는 background thread가 안정적인 작업 실행 수단이 아니다. | receipt commit 뒤 Cloud Tasks/Pub/Sub로 enqueue하고 Cloud Run Job/worker가 처리한다. |
| Python `ThreadingHTTPServer`의 HTTP/1.1 | Cloud Run HTTP/1 request body는 32 MiB 상한이다. | HTTP/2(h2c) 가능한 ingress 또는 업로드 크기·청크 정책을 명시적으로 제한한다. |

Cloud Run의 기본 파일 시스템은 메모리를 사용하고 instance 종료 시 데이터가 보존되지
않는다. [공식 container contract](https://cloud.google.com/run/docs/container-contract)
Cloud Storage FUSE mount도 여러 writer의 lock을 제공하지 않고 마지막 writer가 앞선
writer를 덮어쓸 수 있으므로 SQLite DB나 note revision lock의 대체로 사용하면 안 된다.
[Cloud Storage volume mount 제한](https://cloud.google.com/run/docs/configuring/services/cloud-storage-volume-mounts)

현재 앱의 constrained AAC는 32 kbit/s로 설정돼 120분 이론값이 약 27.5 MiB이지만,
기기별 unconstrained AAC와 PCM/WAV fallback은 32 MiB를 초과할 수 있다. 현재
`ThreadingHTTPServer`는 HTTP/2(h2c)를 제공하지 않으므로 5/20/120분 설정을 그대로
Cloud Run HTTP/1 endpoint에 약속할 수 없다. Cloud Run의 HTTP/1 request body 상한은
32 MiB이며 HTTP/2 server에는 이 상한이 없다. [Cloud Run quotas](https://cloud.google.com/run/quotas)

## 승인 가능한 목표 아키텍처

1. **Cloud Run API** — stateless V1 API만 담당한다. `PORT`, public-CA HTTPS,
   Secret Manager token/DB credential, Cloud Logging requestId를 사용한다.
2. **Cloud SQL** — `upload_receipts`, note identity/revision, 처리 상태를 저장한다.
   `(user_id, idempotency_key)`와 `(user_id, recording_id, chunk_id)` unique 제약 및
   optimistic revision update를 DB transaction으로 보장한다.
3. **Cloud Storage** — 오디오 원본은 `user/recording/chunk` immutable object key로
   저장한다. object hash/size를 receipt transaction 전에 검증하고, lifecycle/retention
   정책을 별도로 설정한다.
4. **Cloud Tasks 또는 Pub/Sub → Cloud Run Job/worker** — API request 안에서 STT/LLM을
   실행하지 않는다. 작업은 receipt ID를 입력으로 하며 at-least-once delivery를
   receipt 상태 전이로 멱등 처리한다.
5. **노트 저장소** — V1 API가 노트를 편집해야 하므로 GCS 파일 직접 수정이 아니라 DB
   본문+revision 또는 단일 writer API를 사용한다. Obsidian 동기화는 후속 exporter로
   분리한다.
6. **업로드 크기 정책** — HTTP/2 endpoint를 실제 검증하거나, PCM fallback까지 포함한
   보수적 chunk/파일 상한과 `413` UI 안내를 앱·서버 계약에 함께 추가한다.

Cloud Run은 shutdown 전에 SIGTERM 뒤 최대 10초만 제공하므로, local temp를 종료 시점에
복사해 영속성을 보장하는 설계는 금지한다. receipt·object commit은 각 요청이 성공 응답을
보내기 전에 끝나야 한다. [Cloud Run shutdown behavior](https://cloud.google.com/run/docs/container-contract)

## GCP staging 착수 전 체크리스트

- [ ] GCP project/region, billing, data residency, retention, 삭제 정책 승인
- [ ] Cloud SQL·Cloud Storage·Secret Manager·service account 최소 IAM 생성
- [ ] file/SQLite receiver를 stateless API + persistent adapter로 교체하고 migration test 추가
- [ ] Cloud Run concurrency, max instances, request timeout, HTTP/2 또는 request size 정책 결정
- [ ] 5/20/120분 AAC 및 PCM fallback의 실제 파일 크기와 `413` 복구/분할을 Android 실기기에서 검증
- [ ] public-CA HTTPS, token rotation, Cloud Armor/rate limiting, structured log redaction 검증
- [ ] Cloud Run API → object/receipt → queue/worker → note 조회까지 staging E2E 수행
- [ ] 기존 LAN Receiver와 새 remote Receiver가 동일 OpenAPI fixture/멱등 결과를 반환하는지 검증

## 현재 결론

Android 클라이언트는 GCP public HTTPS URL을 저장·연결할 준비가 되어 있다. 그러나 현재
Receiver는 **LAN/VPN의 단일 host + 영속 로컬 디스크/SQLite** 운영을 전제로 한다. 위
persistent adapter와 staging E2E를 완료하기 전에는 Cloud Run URL을 운영 서버 주소로
안내하거나 QA APK를 원격 운영 배포에 사용하지 않는다.
