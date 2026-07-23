# ThinkTank Recorder Next — 최종 QA 보고서

작성 일시: `2026-07-23 KST`
최근 갱신: `2026-07-24 04:10 KST`

## 판정

**코드·자동 테스트·Android 15 emulator E2E·QA 서명 APK 검증 완료.**

운영 배포 승인은 아래 외부 항목이 충족된 뒤에만 가능하다.

1. 운영용 서명키 및 안전한 보관 위치 제공
2. Android 14/15 실제 기기에서 녹음·잠금 화면·오디오 경로 장시간 검증
3. 5/20/120분 청크의 PTS 연속성 및 8~24시간 soak 검증
4. 영속 저장소 구조를 갖춘 GCP HTTPS staging smoke 검증

## 검증 결과

| 구분 | 명령/환경 | 결과 |
|---|---|---|
| Python 회귀·계약 | `UV_CACHE_DIR=/tmp/thinktank-uv-cache uv run --extra dev --extra tls pytest -q` | **575 passed, 4 skipped, 4 deselected** |
| Python 정적 검사 | `uv run --extra dev ruff check src tests` | 통과 |
| Android 단위 테스트 | `:app:testDebugUnitTest`, `:app:testReleaseUnitTest` | **각 22 tests 통과** |
| Android lint | `:app:lintDebug`, `:app:lintRelease` | 통과 |
| Android 빌드 | AGP 8.9.1 / Gradle 8.12, `:app:assembleDebug`, `:app:assembleRelease` | 통과 |
| Compose 기기 테스트 | Android 15 API 35 emulator | **3/3 통과** — 다크·라이트 primary action/상태 화면 |
| 디자인·에셋 게이트 | `verifyRasterAssets`, `verifyBundledFontLicenses` | 7개 WebP SHA/provenance 및 APK 내 font license 검증 통과 |
| PD20 재실행 | Android 12 PD20 | 외부 `com.coreline.cbot`가 테스트 Activity의 전면을 점유해 Compose hierarchy가 사라져 자동 UI test는 무효 실패. 기존 수동 화면 검증 증거는 유지하되, 이 실행은 통과로 집계하지 않음. |
| QA APK 설치 | Android 15 API 35 emulator | 최신 R8 축소 release APK 설치·cold start·패키지 정보 확인 통과 |

Android 15 Compose 재검증은 `adb shell am instrument -w`로 실행해 `OK (3 tests)`를 확인했다. Android 12 PD20의 Gradle 연결 테스트 실패는 제품 코드 오류가 아니라 외부 화면 점유 환경으로 분리했다.

## 서버 V1 후속 계약 보강

최종 QA 검토에서 확인된 서버의 회귀 위험을 코드·실제 HTTP 계약 테스트로 보강했다.

- receiver 시작 시 24시간을 넘긴 `.thinktank-v1.*.part` 요청 임시 파일만 정리하고, 최근 임시 파일과 확정 녹음은 보존한다.
- 같은 `recordingId`·`chunkId`를 새 idempotency key/다른 파일명으로 재사용하면 SQLite unique 오류(500)가 아니라 구조화된 `409 UPLOAD_CONFLICT`를 반환한다.
- `Content-Length` 없음·형식 오류·2 GiB 초과·중간 연결 종료와 디스크 부족 `507`을 실제 socket/HTTP 요청으로 확인한다.
- 동일 revision의 동시 노트 수정은 정확히 하나만 `200`으로 확정되고 다른 요청은 `412 REVISION_CONFLICT`가 되는 것을 HTTP 병렬 테스트로 검증했다.
- 폴더별 동명 노트의 서로 다른 ID, identity 생성 후 symlink 치환 차단, V1 APK 정보·Bearer 다운로드·응답 SHA-256을 검증했다.
- V1 오류 응답은 body의 `requestId`와 `X-Request-ID`가 일치하는지 검사한다.

위 보강 후 전체 Python 회귀와 Ruff 정적 검사를 다시 실행해 통과했다. 이는 서버 계약·정합성 위험을 낮추지만, 실제 GCP 영속 저장소/Android 실기기 장기 녹음 검증을 대체하지는 않는다.

## Android 데이터 정합성 후속 보강

- Room의 Note upsert를 SQLite `REPLACE`에서 update-then-insert transaction으로 교체했다. 이로써 노트 행 갱신이 FK cascade를 일으켜 저장된 `NoteConflict`를 삭제하던 문제를 제거했다.
- 업로드 성공은 HTTP 2xx만으로 확정하지 않고, receipt의 uploadId·recordingId·chunkId·파일명·크기·SHA-256·status가 로컬 chunk와 모두 일치할 때만 `UPLOADED`로 전이한다.
- `PENDING_DELETE` 노트는 server `404`를 이미 보관된 상태로 인정해 로컬에서 제거한다. `408`/`429`/`5xx`/네트워크 실패는 `PENDING_DELETE`와 오류 원인을 보존하되, 다른 노트 저장과 원격 목록 새로고침을 막지 않고 worker에 재시도를 반환한다.
- 활성 녹음 중에도 30초마다 현재 시간 창을 재평가한다. 창 밖으로 전환되면 현재 청크를 안전하게 마감하고 `WAITING`으로 전이한다. 종료 결과는 단일 정책으로 계산해 capture 실패가 성공적인 cleanup에 의해 `STOPPED`로 덮어써지지 않도록 했다.
- 위 동작을 Room repository 회귀 3건과 recording terminal-state 회귀 3건으로 고정했다. 아래 디자인 대비 회귀 3건까지 포함해 Android debug/release unit test가 **각 22건**이 되었고, lint·debug/release build·R8 축소를 다시 통과했다.

## 디자인·에셋·라이선스 보강

- 7개 bundled WebP마다 `author`·`license`·생성 브리프(`prompt`)·source ID·SHA-256을 `asset-manifest.json`에 기록하고 Gradle `verifyRasterAssets`가 항목별로 확인한다.
- Pretendard·MaruBuri copyright notice와 SIL OFL 1.1 전문을 `assets/licenses/FONT-LICENSES.txt`로 APK에 포함하고 `verifyBundledFontLicenses`로 packaging 전 확인한다. NAVER의 공식 글꼴 안내는 MaruBuri에 동일한 OFL 1.1 규정을 적용한다고 명시한다. [NAVER 글꼴 라이선스 안내](https://help.naver.com/service/30016/contents/18088?osType=PC)
- 공통 액션/상태는 hard-coded dark copper·moss 대신 `ColorScheme.primary/secondary`를 사용한다. 라이트 primary `#A95632`은 archive paper에 **4.67:1**, 라이트 moss `#5F6D58`은 **4.97:1**, Markdown paper 전용 copper `#8C452B`은 **5.73:1** 대비를 unit test로 고정했다.
- Android 15 API 35에서 라이트 테마 3탭 녹음 화면을 육안 검수했다. [라이트 녹음 화면](screenshots/android15-release-qa-r11-light-recording.png)

## Android 15 녹음·동기화 E2E

테스트 흐름:

```text
사용자 녹음 시작
→ microphone foreground service (type=0x80)
→ AAC/M4A 확정
→ Room READY
→ WorkManager 수동 동기화
→ Receiver V1 SHA-256 검증·영수증 저장
→ Room UPLOADED
→ 서버 Markdown 노트 다운로드·Compose 렌더링
```

최종 E2E 파일 증거:

| 항목 | 값 |
|---|---|
| 로컬/서버 파일 | `rec_20260723_130926_c3edfbb0-127f-46a7-bc4e-5b9a8e2862b7.m4a` |
| 코덱 | AAC, 16,000 Hz, mono |
| 길이 | 4.928초 |
| 크기 | 22,944 bytes |
| SHA-256 | `6672fae9b5788d2bd17485cd2993335c5e142d9197f2070a2976564666dbad3e` |
| 서버 영수증 | `stored`, recording/chunk/upload UUID 일치 |
| 앱 업로드 이력 | `SUCCEEDED`, requestId 기록 |

증거 파일:

- [녹음 M4A](android15-e2e-sample.m4a)
- [최종 emulator Room DB](emulator-db/android15-e2e-final.sqlite3)
- [서버 영수증 DB](server-e2e/receiver-v1-e2e.sqlite3)
- [서버 노트 fixture](server-e2e/welcome.md)
- [노트 목록 화면](screenshots/android15-e2e-notes-list.png)
- [Markdown 표·위키링크 화면](screenshots/android15-e2e-note-detail.png)

## PD20 녹음 불가 분석

Android 12 PD20에서 `RECORD_AUDIO` 권한과 microphone FGS 시작은 정상이나, 시스템 audio policy가 `REMOTE_SUBMIX` 외 실제 입력 장치를 제공하지 않았다. 그 결과 `MediaRecorder`와 `AudioRecord`가 Android HAL 단계에서 실패했다.

앱은 이를 정상 기능 미지원으로 오인하지 않도록 다음을 적용했다.

- 실제 입력 장치가 없으면 녹음 시작을 막고 USB 마이크 연결을 안내
- AAC `MediaRecorder` 실패 시 PCM/WAV fallback 시도
- 녹음 실패 상태와 원인을 Room에 남김
- 실패 시 foreground service·알림을 종료

현재 PD20 수동 실행에서도 녹음 control이 비활성화되고 `사용 가능한 마이크 입력이 없습니다`라는 정확한 상태 문구가 표시되는 것을 확인했다. [PD20 상태 화면](screenshots/android12-posttest-recording-screen.png)

따라서 PD20에서는 지원되는 USB 마이크 연결 후 재검증이 필요하며, 동일 앱은 Android 15 emulator의 built-in mic에서 실제 녹음·업로드까지 성공했다.

## 배포 산출물

| 항목 | 값 |
|---|---|
| QA APK | `release/thinktank-recorder-next-1.0.0-qa-debug-signed-r11.apk` |
| applicationId | `com.thinktank.recorder.next` |
| version | `1.0.0 (1)` |
| APK SHA-256 | `2fc058b127e68c00a8d1801c1a1db9d7b4c35679d602f4c749cb95d08bd5e38f` |
| 서명 | Android debug certificate, v2/v3 검증 통과 |
| 설치 검증 | Android 15 emulator 설치·cold start 통과, `zipalign -c -v 4` 통과 |

이 APK는 **QA 설치용**이다. Android debug certificate로 서명했으므로 운영 배포·사용자 업데이트용으로 배포해서는 안 된다.

[R8 축소 release APK API 35 기동 화면](screenshots/android15-release-qa-launch.png)

[노트 archive·녹음 상태·디자인 보강 후 최신 QA APK API 35 라이트 화면](screenshots/android15-release-qa-r11-light-recording.png)

## Release 보안 확인

- release APK: `debuggable=false`, `usesCleartextTraffic=false`
- `MANAGE_EXTERNAL_STORAGE`, `USE_EXACT_ALARM` 미포함
- 앱 자체 microphone BootReceiver 미포함
- `TYPE_REMOTE_SUBMIX` API 31 guard와 adaptive monochrome icon 경고를 정리했으며, lint의 잔여 경고는 의존성 최신 버전 안내뿐이다.
- `RECEIVE_BOOT_COMPLETED`와 `RescheduleReceiver`는 WorkManager가 업로드 작업 재등록을 위해 추가한 것이며 마이크 서비스를 시작하지 않는다.
- AGP를 **8.9.1**, Gradle을 **8.12**로 올리고 R8 축소·resource shrinking을 다시 활성화했다. `minifyReleaseWithR8`을 포함한 release build 및 API 35 설치·기동을 통과했다.

## 미완료 운영 게이트

- 운영 서명키/인증서 rotation 정책 확정 및 서명 APK 생성
- Android 14 및 15 실제 기기에서 잠금·홈 이동·권한 취소·Bluetooth/USB 마이크·1시간 이상 녹음 검증
- 5/20/120분 청크 PTS gap/overlap 및 8~24시간 soak 검증
- 실제 GCP HTTPS staging에서 인증서·proxy body limit·영속 storage smoke 검증. 현재 file/SQLite receiver의 Cloud Run 직접 운영 배포는 금지하며 [전환 준비도](../gcp-cloud-run-readiness-2026-07-23.md)를 먼저 충족해야 함
- PD20의 외부 앱 전면 점유를 해소한 뒤 Compose 자동 UI test 재실행
