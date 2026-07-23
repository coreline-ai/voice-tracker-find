# thinktank

음성 메모 자동 처리 파이프라인: 수집 → VAD 무음 제거 → STT 전사 → LLM 분류·추출 → Obsidian 정리.

> **설치하려는 경우** 이 README 가 아니라 **`DISTRIBUTE.md`**(빠른 안내) 또는
> **`SETUP.md`**(Claude 실행 런북)를 보세요. 아래는 개발/참조용입니다.

## 개발 환경 설정

```bash
uv venv
uv pip install -e ".[dev]"
```

## 설치 (운영 환경)

실제로 오디오(VAD/STT)와 LLM(분류·추출·창발) 처리를 수행하려면 `audio`, `llm` extras를
함께 설치해야 한다.

```bash
uv venv
uv pip install -e ".[audio,llm]"
```

- `audio`: silero-vad, pydub, faster-whisper (VAD + STT, GPU 권장)
- `llm`: anthropic (`AI_PROVIDER=api` 일 때). `claude_cli` 면 로컬 Claude CLI 로그인을 써서 이 키가 불필요

## 설정 (.env)

프로젝트 루트에 `.env` 파일을 만들고 아래 항목을 설정한다 (`src/thinktank/config.py` 참고).

| 환경변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `AI_PROVIDER` | 선택 | `api` | `api`(키 사용) 또는 `claude_cli`(로컬 Claude 로그인) |
| `CLAUDE_API_KEY` | 조건부 | - | `AI_PROVIDER=api` 일 때만 필수 (Anthropic API 키) |
| `INGEST_DIR` | 선택 | `~/.thinktank/inbox` | 녹음 파일 수신 폴더 (폰 → PC 수신기가 여기 저장) |
| `OBSIDIAN_VAULT` | 선택 | `~/thinktank-vault` | Obsidian 볼트 루트 경로 |
| `DB_PATH` | 선택 | `~/.thinktank/db/pipeline.db` | SQLite 상태 DB 경로 |
| `TEMP_DIR` | 선택 | `~/.thinktank/temp` | VAD/전사 임시 파일 경로 |
| `WHISPER_MODEL` | 선택 | `large-v3` | faster-whisper 모델 이름 |
| `VAD_SAMPLE_RATE` | 선택 | `16000` | Silero VAD 샘플레이트 |
| `VAD_THRESHOLD` | 선택 | `0.5` | Silero VAD 발화 판정 임계값 |
| `RETENTION_DAYS` | 선택 | `7` | 원본/전사/아카이브 노트 보존 기간(일) |

### 폰 연동 (LAN 수신기)

안드로이드 앱이 녹음을 PC 의 HTTP 수신기(`http://<PC IP>:8765`)로 직접 업로드한다.
수신기가 받아 `INGEST_DIR` 에 저장하면 파이프라인이 인계받는다. 설치·연결은
`SETUP.md`(Claude 실행 런북) 참고. (예전 Syncthing 방식은 제거됨.)

## 실행

### 수동 실행

```bash
.venv\Scripts\python -m thinktank.main
```

### 옵션

- `--force-emerge`: 30일 주기와 무관하게 창발(emerge) 단계를 강제 실행
- `--date YYYY-MM-DD`: 실행 기준 날짜 지정 (기본: 오늘)

```bash
.venv\Scripts\python -m thinktank.main --force-emerge --date 2026-07-05
```

### 스케줄러 등록 (Windows 작업 스케줄러)

관리자 권한 PowerShell에서 실행한다.

```powershell
.\scripts\register_task.ps1
```

- 매일 02:00에 "thinktank-nightly" 태스크로 `python -m thinktank.main` 을 실행하도록 등록한다.
- 이미 등록되어 있으면 설정을 갱신한다 (멱등하게 재실행 가능).
- `-ProjectRoot`, `-Time` 파라미터로 경로/시각을 변경할 수 있다.
- `-Unregister` 로 등록된 태스크를 제거할 수 있다.

```powershell
.\scripts\register_task.ps1 -Time "03:30"
.\scripts\register_task.ps1 -Unregister
```

## 모니터링 & 문제 해결

- 실행 결과는 볼트 루트의 `_pipeline.md` 에 실행 일시/처리 건수/실패 내역이 최신순으로 기록된다.
- 레코딩 한 건의 처리 실패는 배치 전체를 막지 않는다: 해당 건은 실패 상태로 남고, 다음 배치
  실행 때 자동으로 재시도된다 (멱등한 상태 기반 처리).
- 스케줄러 태스크 자체가 실패(크래시 등)한 경우, `register_task.ps1` 이 설정한 재시도 규칙에
  따라 1시간 간격으로 최대 3회 재시도한다.
- PC가 꺼져 있어 예정 시각에 실행되지 못했다면, 다음 부팅 시 바로 실행된다(catch-up).

## 폴더 구조 (Obsidian 볼트)

| 경로 | 설명 |
|---|---|
| `10-daily/YYYY-MM-DD.md` | 데일리 노트 (하루 처리 요약) |
| `20-notes/*.md` | 주제별 위키 노트 |
| `30-ideas/*.md` | 30일 주기 창발(emerge) 아이디어 노트 |
| `90-archive/*.md` | 전사본 아카이브 (보존 기간 후 자동 삭제) |
| `_pipeline.md` | 파이프라인 실행 로그 (볼트 루트) |

## 테스트

```bash
pytest --tb=short -q
```

E2E(전체 파이프라인) 테스트만 실행하려면:

```bash
pytest tests/e2e/ -v
```

## 린팅 & 포맷

```bash
ruff check .
ruff format .
```
