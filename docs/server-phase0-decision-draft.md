# 서버 연동 Phase 0 결정 초안

작성 일시: `2026-07-24 KST`

이 문서는 실제 GCP 리소스를 만들기 전에 구현에 사용할 기본값과 아직 승인이 필요한
값을 분리한다. 미승인 값이 하나라도 남아 있는 동안 `terraform apply`, 유료 API 활성화,
도메인 구매·변경, 운영 데이터 업로드는 수행하지 않는다.

## 현재 고정한 구현 원칙

| 항목 | 구현 기준 | 상태 |
|---|---|---|
| 로컬 개발 호스트 | Mac mini M4, Docker/Colima 기반 개발·테스트 | 사용 가능 |
| 운영 형태 | Compose 앱은 GCP HTTPS API, legacy 앱은 기존 LAN Receiver 유지 | 기준안 |
| API 계약 | `/api/v1`, Bearer 인증, UUID/SHA-256 멱등 receipt 유지 | 고정 |
| 데이터 정본 | PostgreSQL receipt/note/job, Cloud Storage 원본 오디오 | 기준안 |
| 비동기 처리 | receipt transaction outbox → 인증된 worker | 기준안 |
| 현재 STT | `faster-whisper` `large-v3` | 기존 동작 |
| Mac 최적화 STT | 별도 adapter로 MLX Whisper 검토 | 미구현 |
| cloud AI 인증 | interactive CLI login을 사용하지 않고 API/managed secret 사용 | 고정 |
| 인프라 변경 | Terraform plan 검토와 명시 승인 뒤 apply | 고정 |

## 승인 전 입력이 필요한 값

| 결정 | 필요한 입력 | 미결정 시 영향 |
|---|---|---|
| GCP project/billing | project ID, billing owner, staging/production 분리 | 리소스 생성 금지 |
| region | 데이터 저장 국가와 Run/SQL/Storage/Tasks region | IaC 변수만 설계 가능 |
| domain | staging/production host와 DNS owner | 실기기 public-CA E2E 불가 |
| 보존 | audio/transcript/note/archive/backup 기간과 삭제 요청 처리 | lifecycle/PITR apply 금지 |
| 사용량·예산 | 사용자 수, 일/월 녹음 시간, 평균·최대 청크 | instance/SQL/AI 사양 확정 불가 |
| AI provider | cloud STT/LLM provider, credential owner, 비용 상한 | worker 배포 금지 |
| 대용량 계약 | 4/28/40MiB Cloud Run PoC 합격 기준 | direct/resumable 최종 선택 불가 |

## 환경 이름 초안

실제 이름에는 승인된 project와 region을 주입한다. Terraform state, service account,
database, bucket, secret은 환경 간 공유하지 않는다.

| 환경 | 용도 | 데이터 |
|---|---|---|
| `local` | Mac mini 회귀와 adapter 개발 | 합성 fixture |
| `staging` | Samsung 실기기·장애·복구 E2E | 합성/승인된 QA 데이터 |
| `production` | 운영 | Phase 7 승인 뒤에만 생성 |

## 다음 승인 게이트

1. 위 미결정 값과 개인정보 안내 범위를 소유자가 승인한다.
2. 저/기준/고 사용량 비용표와 보존 충돌 검토를 완료한다.
3. Terraform은 먼저 `plan`만 생성해 IAM·region·비용 자원을 검토한다.
4. staging apply와 실제 데이터 사용은 각각 별도 승인한다.
