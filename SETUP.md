# thinktank 설치 — Claude 실행용 런북

> **이 문서는 Claude Code 가 읽고 순서대로 실행하는 설치 안내서다.**
> 새 PC에서 Claude Code 를 열고 이 폴더에서 "SETUP.md 대로 설치해줘" 라고 하면,
> Claude 가 아래 단계를 하나씩 실행하고 검증한다. 🙋 표시는 사람이 직접 해야 하는
> 단계(로그인·물리 장치·관리자 권한)로, Claude 는 여기서 멈추고 사용자에게 요청한다.

**대상 환경(전제):** Windows 10/11 · NVIDIA GPU(빠른 STT) · Claude Code 구독.
STT 백엔드는 각자의 Claude 로그인(`AI_PROVIDER=claude_cli`)을 쓴다 — 추가 API 비용 없음.

Claude 는 각 단계에서 **먼저 검증 명령을 돌려 이미 된 단계는 건너뛰고**, 실패하면
원인을 사람에게 설명한 뒤 다음으로 넘어가지 않는다.

---

## 0. 전제 확인 (여기서 막히면 이후가 무의미)

1. **OS**: `Windows` 인지 확인. 아니면 중단하고 "현재 Windows 전용"이라 안내.
2. **GPU**: `nvidia-smi` 실행 → GPU 목록이 나오는지.
   - 없으면 🙋 사용자에게: "NVIDIA GPU 가 없어 STT 가 매우 느립니다. 계속할까요?"
3. **Claude Code 로그인**: `claude -p "1+1"` 가 답을 돌려주는지.
   - 실패(미로그인)면 🙋 사용자에게 "Claude Code 에 로그인하세요(`claude` 실행 후 로그인)"
     요청하고, 로그인 후 재확인.
4. **Python 3.12 이상**: `python --version`. 없으면 🙋 "winget install Python.Python.3.13"
   또는 python.org 안내(자동 설치 시도해도 됨).
5. **Node/npm**(Claude CLI 설치용, 이미 있으면 통과): `node --version`.

---

## 1. ffmpeg (오디오 디코더) + Python 환경 + 의존성

**ffmpeg** 는 pip 패키지가 아니라 **시스템 실행파일**이다. m4a→파형 디코드에 쓰며,
없으면 VAD 단계(첫 처리)에서 "ffmpeg 명령을 찾을 수 없습니다" 로 죽는다. 먼저 설치:
```powershell
winget install Gyan.FFmpeg    # PATH 에 자동 등록. 등록 반영은 새 터미널부터.
```
그다음 Python 환경:
```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install --upgrade pip
.\.venv\Scripts\python -m pip install -e ".[audio,llm]"
# GPU STT 라이브러리 (faster-whisper 가 CUDA 로 돌게)
.\.venv\Scripts\python -m pip install nvidia-cudnn-cu12 nvidia-cublas-cu12
```
**검증:** (새 터미널에서) `ffmpeg -version` 이 나오고,
`.\.venv\Scripts\python -c "import faster_whisper, silero_vad; print('deps ok')"`

---

## 2. 로컬 임베딩 모델 (Ollama) — 정규화용

```powershell
winget install Ollama.Ollama    # 없으면 https://ollama.com/download
# 설치 직후엔 새 터미널이 필요할 수 있고, Ollama 서비스가 뜨는 데 몇 초 걸린다.
ollama pull nomic-embed-text
```
**검증:** `Invoke-RestMethod http://127.0.0.1:11434/api/tags` 에 `nomic-embed-text` 존재.
(정규화 임베딩에만 쓴다. Ollama 가 없어도 파이프라인 본체(STT·노트)는 동작한다.)

---

## 3. GPU 실동작 확인 (STT 가 실제로 CUDA 로 도는지)

⚠️ `torch.cuda.is_available()` 로 판정하지 말 것. faster-whisper 는 torch 가 아니라
CTranslate2 로 돌고, 여기 torch 는 silero-vad 가 딸려온 CPU 전용 휠이라 GPU 가 멀쩡해도
`False` 가 나온다. **실제 faster-whisper 를 CUDA 로 띄워** 판정한다:
```powershell
.\.venv\Scripts\python -c "from thinktank.transcribe import _add_cuda_dll_dirs; _add_cuda_dll_dirs(); from faster_whisper import WhisperModel; WhisperModel('tiny', device='cuda', compute_type='float16'); print('GPU STT ok')"
```
- `GPU STT ok` 가 뜨면 성공 → 4단계에서 `WHISPER_DEVICE=cuda` 그대로 둔다.
- 실패(DLL/CUDA 오류)면 🙋 사용자에게 알리고, 1단계의 `nvidia-cudnn-cu12`/`nvidia-cublas-cu12`
  설치를 재확인. 그래도 안 되면 `WHISPER_DEVICE=cpu`(느림)로 진행.

---

## 4. 설정 (.env + 토큰 + 볼트 + APK 배치)

1. **`.env` 생성** (`.env.example` 를 복사 후 아래로 채움):
   ```
   AI_PROVIDER=claude_cli
   INGEST_DIR=D:/thinktank            # 녹음 유입 폴더 (원하는 경로)
   OBSIDIAN_VAULT=~/thinktank-vault   # 노트 볼트
   WHISPER_DEVICE=cuda                # GPU 없으면 cpu
   RECEIVER_AUTO_PROCESS=1            # 업로드 즉시 처리
   ```
   `CLAUDE_API_KEY` 는 **비워둔다**(claude_cli 는 로그인으로 인증).
2. **수신기 토큰 생성**:
   ```powershell
   .\.venv\Scripts\python -c "import secrets; print(secrets.token_urlsafe(32))"
   ```
   출력값을 `~/.thinktank/receiver-token.txt` 에 저장(폴더 없으면 생성). 폰 설정에도 이 값을 넣는다.
3. **볼트 폴더 생성**: `New-Item -ItemType Directory -Force ~/thinktank-vault`
4. **APK 를 수신기가 서빙할 위치로 복사** (폰이 `/apk` 로 받으려면 필요 — 런처는
   `~/.thinktank/thinktank-recorder.apk` 한 곳만 본다):
   ```powershell
   New-Item -ItemType Directory -Force ~/.thinktank | Out-Null
   Copy-Item .\thinktank-recorder.apk ~/.thinktank/
   ```

---

## 5. 자동 실행 등록 (수신기 상시 + 야간 정규화)

```powershell
.\scripts\register_receiver_task.ps1     # 로그온 시 수신기 자동 시작
.\scripts\register_task.ps1              # 매일 02:00 파이프라인+증분 정규화
# 트리거가 "로그온 시"라 등록만으론 지금 안 뜬다 — 이번 세션에선 직접 시작한다:
Start-ScheduledTask -TaskName thinktank-receiver
```
**검증:** `Get-ScheduledTask thinktank-receiver, thinktank-nightly` 존재 +
기동 확인(~10초 후): `Invoke-WebRequest http://127.0.0.1:8765/health` → `ok`.

---

## 6. 🙋 방화벽 (관리자 권한, 폰이 붙으려면 필수)

관리자 PowerShell 에서(사용자에게 실행 요청):
```powershell
New-NetFirewallRule -DisplayName "thinktank LAN 수신기" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 `
  -Profile Private -RemoteAddress 192.168.0.0/24
```
> PC 자기 자신 curl 은 방화벽을 안 거치므로, 실제 검증은 **폰 브라우저로**
> `http://<PC IP>:8765/health` → `ok` 여야 한다. PC IP: `ipconfig` 의 IPv4.

---

## 7. 🙋 폰 앱 (두 번째 기기라 손이 필요)

1. 같은 폴더의 `thinktank-recorder.apk` 를 USB 등 안전한 전달 방식으로 폰에 설치한다.
   Receiver bearer token을 URL에 넣는 브라우저 APK 다운로드는 지원하지 않는다.
2. 앱 ③ 설정 탭:
   - 서버 주소: `http://<PC IP>:8765`
   - 토큰: 4-2 에서 만든 값
   - 유저 ID: 아무 값(예: `me`)
3. "저장하고 연결 확인" → "연결 성공" 이 떠야 한다.
4. **권한 부여** (앱이 프롬프트를 띄우지만, 일부는 설정에서 수동):
   - 마이크(RECORD_AUDIO)·알림(POST_NOTIFICATIONS): 앱 첫 실행 시 허용.
   - **통화녹음을 올리려면** 설정 → 앱 → 권한 → "모든 파일 액세스"(MANAGE_EXTERNAL_STORAGE)를
     **수동으로 켜야** 한다(안드 11+ 특수 권한). 안 켜면 통화녹음만 안 올라간다(앱 녹음은 무관).
   - 밤샘 녹음 생존: 배터리 → "제한 없음" + 심층 잠자기 제외(삼성).

---

## 8. 첫 실행 검증 (엔드투엔드)

1. 🙋 폰에서 녹음 몇 초 → 앱에서 "동기화"(또는 자동). PC `INGEST_DIR` 에 `.m4a` 도착 확인.
2. 파이프라인 1회 수동 실행:
   ```powershell
   .\.venv\Scripts\python -m thinktank.main
   ```
3. **검증:** `~/thinktank-vault/10-daily/` 에 오늘 날짜 노트가 생겼는지.
   생겼으면 설치 성공 — 이후엔 업로드 즉시(수신기 트리거) + 야간 정규화가 자동으로 돈다.

---

## 9. (선택) 밖에서도 쓰기 — Tailscale

원격 접속을 원하면 PC·폰에 Tailscale 설치 후 로그인, 앱 서버 주소를 PC 의 Tailscale
IP(`100.x`)로 바꾼다. 그러면 집·밖 어디서나 동작한다. (LAN IP 는 집에서만.)

---

## 문제 대응 (Claude 참고)
- 수신기가 안 뜸 → `~/.thinktank/receiver.log` 확인. 기동에 ~10초 걸리니 성급히 실패 판정 X.
- `/apk` 가 404 → APK 를 `~/.thinktank/thinktank-recorder.apk` 로 복사했는지(4-4) 확인.
- STT 가 CPU 로 돎 → 3단계 검증 재실행 + `nvidia-cudnn-cu12`/`nvidia-cublas-cu12` 설치 여부
  + `.env` 의 `WHISPER_DEVICE=cuda`(미설정이면 torch 자동감지로 cpu 로 떨어진다).
- claude CLI 순간 실패(rate limit) → 재시도. 정규화는 미분류로 흘렸다가 다음 야간에 자가치유.
- 코드 구조는 `src/thinktank/` 참고.
