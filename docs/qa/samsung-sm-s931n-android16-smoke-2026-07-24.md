# Samsung SM-S931N Android 16 실기기 Smoke

작성 일시: `2026-07-24 08:10 KST`

## 고정 테스트 대상

| 항목 | 값 |
|---|---|
| 제조사 / 모델 | Samsung / `SM-S931N` |
| ADB serial | `R3CY40PXCAP` |
| device | `pa1q` |
| Android / API | Android 16 / API 36 |
| 보안 패치 | `2026-07-05` |
| 빌드 fingerprint | `samsung/pa1qksx/pa1q:16/BP4A.251205.006/S931NKSSBCZG3_OKRBCZG3:user/release-keys` |
| 화면 | `1080x2340`, density override `450` |
| 계측·녹음 앱 | `com.thinktank.recorder.next.debug` (`1.0.0-debug`) |
| QA 설치 후보 | `com.thinktank.recorder.next` (`1.0.0`, versionCode 1) |

다른 연결 기기(`H84225J181G02072`, IM-H842)로 명령이 넘어가지 않도록 모든 실행에
삼성 serial을 지정했다. 반복 실행은 저장소 루트에서 다음 명령을 사용한다.

```bash
./scripts/run_samsung_device_tests.sh
```

스크립트는 serial뿐 아니라 제조사와 모델까지 일치해야 진행하며, SDK ADB를 우선 사용하고
Compose 테스트 APK를 직접 설치한 뒤 계측 runner를 실행한다.

## 실행 결과

| 시나리오 | 결과 | 관찰 증거 |
|---|---|---|
| ADB 연결·기기 식별 | 통과 | Samsung `SM-S931N`, Android 16/API 36 확인 |
| 기존 앱과 package 충돌 | 통과 | 시험 전 `com.thinktank.recorder*` 설치 없음 |
| debug/test APK 설치 | 통과 | 두 APK 모두 streamed install `Success` |
| QA v1.0.0 설치·cold start | 통과 | `com.thinktank.recorder.next`, non-debuggable, launcher 기동 성공 |
| QA/debug 병행 설치 | 통과 | `com.thinktank.recorder.next`, `.debug`, `.debug.test` 동시 확인 |
| 고정 스크립트 재현 | 통과 | 빌드·두 APK 설치·Compose `OK (3 tests)` 전체 재실행 |
| Compose 계측 | 통과 | `ComposeScreensTest` **3/3**, `OK (3 tests)` |
| 온보딩 | 통과 | `1 / 2` → `2 / 2` → 녹음 화면 전환 |
| 권한 | 통과 | `RECORD_AUDIO`, `POST_NOTIFICATIONS` granted |
| 실제 마이크 녹음 시작 | 통과 | UI `기록 중`, control `녹음 정지` |
| 포그라운드 서비스 | 통과 | `RecorderService`, `isForeground=true`, microphone type `0x80` |
| 진행 파일 | 통과 | 32KB `.m4a.part` 생성 |
| 정상 종료·파일 확정 | 통과 | `.part` 제거, 91KB `.m4a` 확정, 서비스 종료 |
| 홈 이동 중 녹음 | 통과 | 홈 이동 8초 후 FGS type `0x80`와 40KB `.part` 유지 |
| 앱 복귀·두 번째 종료 | 통과 | 101KB `.m4a` 확정, FGS 잔존 없음 |
| 크래시 점검 | 통과 | 실행 구간 `FATAL EXCEPTION`/`AndroidRuntime` crash 없음 |

## 환경 이슈

- 삼성 단말의 USB ADB transport가 시험 중 여러 번 재등록됐다
  (`transport_id` 6 → 8 → 10 → 11 → 12).
- 앱 설치와 계측, shell 기반 녹음 검증은 재연결 후 성공했으나 약 600KB PNG를
  `adb pull`할 때 연결이 다시 끊겨 스크린샷은 저장소에 회수하지 못했다.
- 제품 실패와 분리한 시험 환경 이슈다. 다음 장시간 시험 전 직결 데이터 케이블·USB 포트로
  교체하고, `run_samsung_device_tests.sh`의 고정 serial/모델 검사로 다른 기기 오실행을 막는다.

## 이번 Smoke가 대체하지 않는 항목

- Android 14/15 대표 실기기 검증
- 잠금 화면, 회전, 알림 action 정지, 권한 취소
- Bluetooth/USB route 변경, 전화·알람 interruption
- START/STOP 20회와 process recreation
- 5/20/120분 청크 codec·PTS gap/overlap
- 1시간 반복, 8시간 및 24시간 soak
- release/운영 서명 APK의 실기기 설치·업데이트

따라서 이번 결과는 Samsung Android 16의 짧은 실기기 기준선 통과이며, 장시간 안정화
Phase 완료 또는 운영 배포 승인으로 해석하지 않는다.
