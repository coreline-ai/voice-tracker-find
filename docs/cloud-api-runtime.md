# Cloud API runtime

`airvoice.cloud_api`는 Cloud Run용 Receiver V1 API다. TLS는 Cloud Run에서 종료하며
container는 `0.0.0.0:$PORT`의 h2c/HTTP 서버만 연다.

## 필수 환경 변수

| 이름 | 용도 |
|---|---|
| `DATABASE_URL` | `postgresql+psycopg://...` SQLAlchemy URL |
| `GCS_BUCKET` | immutable recording object bucket |
| `TOKEN_PEPPER` 또는 `TOKEN_PEPPER_B64` | 32 byte 이상 HMAC pepper |
| `PORT` | Cloud Run 주입값, 기본 `8080` |
| `GOOGLE_CLOUD_PROJECT` | Cloud Trace resource correlation |

DB pool 기본값은 `DB_POOL_SIZE=5`, `DB_MAX_OVERFLOW=2`,
`DB_CONNECTION_BUDGET=7`이다. 합이 budget보다 크면 process가 기동하지 않는다.
`REQUEST_TIMEOUT_SECONDS` 기본값은 900초, `GRACEFUL_TIMEOUT_SECONDS`는 Cloud Run의
SIGTERM 10초 창보다 짧은 8초다.

## Migration과 token bootstrap

배포 전에 별도 migration job에서 다음을 실행한다.

```bash
alembic -c alembic.ini upgrade head
```

사용자 token은 다음 명령으로 한 번 발급한다. JSON의 `token`은 이때만 표시되며 DB에는
peppered HMAC digest만 저장된다. 출력은 Secret Manager로 옮기고 shell/CI log에 남기지
않는다.

```bash
python -m airvoice.cloud_admin issue-token \
  --user-id user1 \
  --version 1 \
  --expires-in-days 90
```

폐기는 원문 token 없이 `tokenId`로 수행한다.

```bash
python -m airvoice.cloud_admin revoke-token --token-id UUID
```

## Container build

Apple Silicon Mac mini에서도 Cloud Run의 Linux x86_64 계약에 맞춰 교차 빌드한다.

```bash
docker build \
  --platform linux/amd64 \
  --file Dockerfile.api \
  --tag REGION-docker.pkg.dev/PROJECT/REPOSITORY/airvoice-api:TAG \
  .
```

image는 UID/GID `65532`, `/app` read-only, container TLS key/certificate 없음이 기준이다.
Cloud Run service port 이름은 end-to-end HTTP/2 사용 시 `h2c`로 설정한다.

실제 project·region·bucket·Cloud SQL·Secret Manager 생성과 image push/deploy는 Phase 0
승인 및 Phase 5 Terraform 범위다.
