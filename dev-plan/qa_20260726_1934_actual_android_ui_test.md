# Android 실제 앱 UI 테스트 결과 — 2026-07-26

## 대상

- 기기: Samsung `SM-S931N` (`R3CY40PXCAP`)
- 앱: `com.thinktank.recorder.next.qa` (Device QA 빌드)
- 범위: 4번째 탭 **로컬 AI**의 완료 녹음 파일 분석 흐름
- 테스트 입력: 기기 내 오프라인 한국어 TTS로 생성한 3초 통제 fixture 1건
  - 실제 사용자 음성·전사 원문은 PC로 수집하거나 이 문서에 기록하지 않았다.

## 사전 상태

- 실제 앱 GUI에서 Wi-Fi 설치를 완료한 모델
  - SenseVoice 한국어 파일 STT: 사용 준비 완료
  - Qwen 로컬 AI 요약: 사용 준비 완료
- 1번 탭 완료 녹음 아카이브 계약을 통해 생성된 fixture가 선택 목록에 노출됨을 확인했다.

## 실제 UI 수행 결과

| 단계 | 실제 앱 UI 동작 | 결과 |
|---|---|---|
| 1 | 4번째 탭에서 `Qwen 로컬 AI · 실험적` 선택 | PASS — 선택 표시 확인 |
| 2 | `1번 탭 녹음 선택`에서 완료된 WAV 선택 | PASS — 3초 WAV가 선택됨으로 표시 |
| 3 | `PCM 변환 후 텍스트 추출` 실행 | PASS — 처리 상태 및 취소 UI 표시 |
| 4 | 로컬 파일 전사 완료 | PASS — 저장 기록에 `전사 방식 · SenseVoice 로컬 파일 STT` 표기 |
| 5 | Qwen 요약 완료 | PASS — UI에 `Qwen 로컬 AI 요약을 완료했습니다.` 표시 |
| 6 | 로컬 기록 보존 확인 | PASS — `원본 · 1번 탭 녹음 · 3초`, `처리 방식 · Qwen 로컬 AI` 표기 |

## 증적

- 성공 완료 화면: `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app/build/device-qa/20260726_actual_ui_R3CY40PXCAP/ui-qwen-complete.png`
- 저장 결과 화면: `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app/build/device-qa/20260726_actual_ui_R3CY40PXCAP/ui-local-record.png`
- UI 구조 증적(XML, 전사 원문은 보고서에 미기록):
  - `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app/build/device-qa/20260726_actual_ui_R3CY40PXCAP/ui-qwen-complete.xml`
  - `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app/build/device-qa/20260726_actual_ui_R3CY40PXCAP/ui-local-record.xml`
- 재현용 fixture instrumentation test:
  - `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app/app/src/androidTest/kotlin/com/thinktank/recorder/next/ondevice/AppRecordedSampleSeedDeviceTest.kt`

## 판정 및 범위

**PASS.** 실제 삼성 기기와 실제 앱 UI에서 `1번 탭 완료 녹음 선택 → PCM 변환 → SenseVoice 로컬 파일 STT → Qwen 로컬 요약 → 로컬 기록 저장` 전체 흐름을 완료했다.

- 이 실행에서는 이미 설치된 로컬 모델을 사용했으며, 처리 중 모델 다운로드는 발생하지 않았다.
- 이 결과는 통제 fixture 기준이다. 실제 사용자 음성의 인식 품질, 장시간 녹음, 기기 오프라인(비행기 모드) 네트워크 차단은 별도 수동 테스트 항목으로 유지한다.
