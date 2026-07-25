# 로컬 AI 4번째 탭 개발·QA 실행 결과

작성 일시: `2026-07-25 KST`

대상 계획: [implement_20260725_085019.md](implement_20260725_085019.md)

최신 종합 리뷰:
[review_20260725_full_qa_agents.md](review_20260725_full_qa_agents.md)

## 현재 판정

| 구분 | 판정 | 근거 |
|---|---|---|
| 4번째 탭·엔진 선택·로컬 저장 | 기능 통과 | Compose 화면, 별도 `ondevice.db`, 앱 전용 파일 경로 구현. 복구·취소·삭제 P1은 미해결 |
| 실제 로컬 녹음 | 조건부 통과 | `AudioRecord` 16kHz mono PCM WAV 1초 smoke 통과. 시작 예외·lifecycle race 수정 필요 |
| Moonshine 한국어 STT | 조건부 통과 | 공식 fixture 성공. 재시도 UI, 즉시 취소, 목표 단말, 라이선스 증빙 미완료 |
| Kotlin 추출형 요약 | 통과 | 결정성·원문 보존·경계 입력 unit test 통과 |
| Qwen3.5 0.8B Q4 요약 | 실험적 NO-GO | 실제 생성은 성공했지만 약 `20.417~52.946초`, cleanup·반복 PSS 검증 미완료 |
| 모델 다운로드·이어받기·검증 | 조건부 통과 | 기본 GUI·SHA-256·원자적 설치 구현. `416`, hard limit, 삭제 race 보강 필요 |
| 음성·전사·요약 네트워크 차단 | 정적 코드 경계 통과 | 직접 업로드 path는 없음. 실제 sync/Receiver 요청 0건 test와 packet capture 미완료 |
| production release | **NO-GO** | P0 라이선스·P1 상태/동시성·ABI와 목표 단말·실동기화 release gate 미완료 |

현재 결과는 **기능 prototype을 내부에서 시험할 수 있는 조건부 beta 후보**다.
Qwen은 실기기 계측 편차가 크고 목표 15초를 넘었으므로 `실험적`으로 유지한다.
목표 기기 검증과 Moonshine 배포 증빙뿐 아니라 종합 리뷰의 P0/P1을 모두 닫기
전에는 production 완료로 판정하지 않는다.

## 구현 결과

- `:feature-ondevice` 모듈과 `로컬 AI` 4번째 탭을 추가했다.
- Android `createOnDeviceSpeechRecognizer()` 전용 경로와 온라인 폴백 금지를 구현했다.
- Moonshine + sherpa-onnx 파일 전사와 실제 16kHz mono WAV 녹음을 구현했다.
- Kotlin 추출형 요약과 Qwen3.5 0.8B Q4 + llama.cpp 생성형 요약을 구현했다.
- 모델 관리 GUI에 Wi-Fi 전용, 다운로드, 속도·예상 남은 시간, 일시정지,
  Range/ETag 이어받기, 재시도, SAF 파일 가져오기, 삭제를 구현했다.
- 모델은 고정 URL·허용 호스트·카탈로그 SHA-256을 통과한 뒤 staging/backup을
  사용해 원자적으로 설치한다.
- `MicrophoneArbiter`를 추가했지만 취소 완료 전 조기 양도와 기존 녹음 시작 실패
  전달 race가 남아 있다.
- `ResourceArbiter`로 Moonshine과 Qwen의 동시 native 진입을 막았지만 Qwen의
  실제 unload 완료와 반복 PSS는 추가 검증이 필요하다.
- Qwen 실행 전 총/가용 RAM, Android low-memory, thermal severe, 저전력·저배터리
  상태를 검사한다.
- 화면 이탈 또는 앱 background 전환 시 취소 경로를 구현했지만, 작업 flag가
  설정되기 전 이탈하는 즉시 시작 race는 P1 수정 대상이다.

## 네이티브·모델 고정값

| 항목 | 버전/파일 | SHA-256 |
|---|---|---|
| sherpa-onnx | `v1.13.4` `libsherpa-onnx-jni.so` | `a79ff75fbe1c3813cc239037b458a7828298a90a5b77f5314056508eefdf72bc` |
| ONNX Runtime | sherpa-onnx 배포본 `libonnxruntime.so` | `994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533` |
| llama.cpp Android AAR | `b10107`, arm64, 정적 CPU backend | `96e22269f12a56d04be5577065d729677b0a61d606d38a8963d211a6cca4937c` |
| Moonshine Korean archive | `2026-02-27` | `d3b6c5390a7859c9ef20ff4f20b0766fcbad1dc06c0f509fe4840a3a302112dc` |
| Qwen GGUF | `Qwen3.5-0.8B-Q4_0.gguf` | `57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf` |

llama.cpp는 Android linker namespace 문제를 피하기 위해 동적 CPU variant 검색을
사용하지 않는다. generic Armv8 CPU backend를 `libai-chat.so`에 정적으로 연결했고,
모바일 메모리 제한을 위해 context `4096`, batch `256`, output `384`로 제한했다.

## 실행 기기와 성능

| 기기 | OS/ABI | 테스트 | 결과 |
|---|---|---|---|
| `PD20` / MT8788 | Android 12, API 31, arm64-v8a | Qwen 로컬 요약 | 생성 성공, 기존 `20.417초`, 독립 재실행 `52.946초`, 기존 peak TOTAL PSS `900,503KB` |
| `sdk_gphone64_arm64` | Android 15, API 35, arm64-v8a | Moonshine 공식 한국어 WAV | 통과, `2.837초`, peak TOTAL PSS `358,035KB` |
| `sdk_gphone64_arm64` | Android 15, API 35, arm64-v8a | 실제 AudioRecord WAV | 통과, 16kHz/mono, 유효 길이 1초 이상 |

Moonshine fixture 길이는 `6.917초`이고 계측 전체 시간은 `2.837초`이므로 RTF는
약 `0.41`이다. Qwen 실행에서 확인된 native buffer는 model mmap `526.5MiB`,
KV `48MiB`, recurrent `19.27MiB`, compute `245.5MiB`였다. PD20의 thermal
status는 실행 전후 모두 `0`이었다.

## 자동 검증

최종 통합 재검증은 `2026-07-25 KST` 기준 `BUILD SUCCESSFUL in 55s`
(`255 actionable tasks: 65 executed, 190 up-to-date`)로 완료했다.

QA 에이전트의 `--rerun-tasks` 독립 재검증도 `BUILD SUCCESSFUL in 1m 48s`
(`240/240 actionable tasks executed`)로 완료했다.

- `:feature-ondevice:testDebugUnitTest` — 18건 통과
- `:app:testDebugUnitTest` — 33건 통과
- `:feature-ondevice:connectedDebugAndroidTest` — 선별 Compose 2건 통과 이력 확인.
  독립 광범위 재실행은 두 emulator에서 0건으로 집계돼 다중 단말 재현성 확인 필요
- `LocalAudioRecorderSmokeTest` — 선별 1건 통과
- `MoonshineInstalledModelSmokeTest` — 통과
- `QwenInstalledModelSmokeTest` — 통과
- `lintDebug` — error 0건, feature warning 7건, app warning 74건
- `:app:assembleDebug` — 통과
- `:app:assembleRelease` + R8/lint vital — 통과
- `verifyOnDeviceNativeArtifacts` — 통과
- `verifyOnDeviceNetworkBoundary` — 통과

native smoke는 모델이나 fixture가 없을 때 skip될 수 있다. 또한 현재 coverage 보고서가
없으므로 위 통과 수치만으로 예외·경쟁 상태의 충분한 실행 범위를 증명하지는 못한다.

산출물:

| 파일 | 크기 | SHA-256 |
|---|---:|---|
| `android-app/app/build/outputs/apk/debug/app-debug.apk` | `69,740,723 bytes` | `b51e803049f71a20a49f2f6ccb7d5f8eabe1eeaea95d4ef731de2a0ad7b2c7bc` |
| `android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `993,120 bytes` | `c84acc8b8fbea034d3766a4b3e70ac2d519c08ed1d2b8a726984308887f110f1` |
| `android-app/app/build/outputs/apk/release/app-release-unsigned.apk` | `40,087,371 bytes` | `18b2e6444359a2253ed0a2c7c3da2449f9e26392551954d4f4c82d3638cf10a5` |

APK에는 모델 파일이 포함되지 않는다. arm64 native runtime만 포함되며 모델은
사용자가 GUI에서 명시적으로 설치한다.

## 다중 에이전트 리뷰에서 확인된 release blocker

- 프로세스 종료 후 `FAILED_RECOVERABLE` Moonshine 세션에 재전사 CTA가 없다.
- 수동 Kotlin/Qwen 요약의 취소와 DB 상태 복구가 일관되지 않다.
- 녹음·추론 중 active 세션을 삭제할 수 있다.
- `AudioRecord`/SpeechRecognizer 시작·terminal event 예외가 LISTENING 고착을
  만들 수 있다.
- 탭 이탈·background와 녹음 시작 사이 lifecycle race가 있다.
- 기존 `RecorderService`의 종료/reconcile/신규 시작 순서에 part 상태 race가 있다.
- 로컬 AI 마이크 취소 완료 전 arbiter를 해제할 수 있고, 기존 녹음의 arbiter 거부가
  화면에 실패로 전달되지 않는다.
- arm64 native runtime과 비-arm64 설치 가능 정책이 일치하지 않는다.
- Qwen native cleanup, 모델 설치/삭제 race, import/download byte hard limit이
  release 수준으로 검증되지 않았다.
- Moonshine 상업 등록·고지와 제3자 라이선스 notice의 최종 패키지 증빙이 부족하다.

상세 근거와 수정 순서는
[전체 QA·코드·통합 리뷰](review_20260725_full_qa_agents.md)를 따른다.

## 미완료 release gate

- 목표 `Samsung SM-S931N / Android 16` 기기가 연결되지 않아 해당 기기 QA를
  실행하지 못했다.
- Android 시스템 온디바이스 한국어 STT의 비행기 모드 실제 발화 테스트가 필요하다.
- 20분 녹음, 10회 반복, 장문 10,000자, 강제 종료, 저전력·과열 주입 테스트가 필요하다.
  기존 문서의 10,000자 경계 통과 표시는 실제 자동 테스트가 없어 철회한다.
- 네트워크 0건은 정적 소스 경계로 검증했다. 연결 상태 packet capture 기반 검증은
  아직 필요하다.
- 계획의 서명 `.ttmodel`/ECDSA 컨테이너는 구현하지 않았다. 현재 SAF 가져오기는
  카탈로그에 고정된 **원본 배포 artifact만** 받고 전체 파일 SHA-256으로 검증한다.
- `isolatedProcess` Binder/AIDL PoC는 구현하지 않았다. 현재는 앱 프로세스 내부에서
  정적 네트워크 경계, 메모리 사전 점검, 직렬 실행, 즉시 native 해제를 사용한다.
- Qwen은 PD20에서 15초 목표를 초과했으므로 목표 Samsung 성능 확인 전까지 실험적이다.
- Moonshine Community License의 상업 이용은 전면 금지가 아니지만 배포 주체의
  등록·매출 조건 확인, 요구 Notice, 제3자 고지와 법무 승인 증빙이 필요하다.
- release APK는 빌드 검증용 unsigned 산출물이다. 배포 서명은 별도 release 절차가 필요하다.

## 최종 release 재검증 명령

```bash
cd android-app
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew \
  :feature-ondevice:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  lintDebug \
  :app:assembleRelease
```
