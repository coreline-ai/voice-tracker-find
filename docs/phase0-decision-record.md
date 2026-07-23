# Android V1 제품 결정 기록

- 결정 일시: 2026-07-23 KST
- 대상: ThinkTank Recorder Kotlin + Jetpack Compose 재구현
- 상태: 구현 기준선 확정

## 제품 결정

| 항목 | V1 결정 |
|---|---|
| applicationId | `com.thinktank.recorder.next` |
| 설치 | 기존 `com.thinktank.recorder`와 병행 설치 |
| 배포 | 내부 서명 APK |
| 화면 | 녹음·노트·설정 3탭 |
| 녹음 시작 | 보이는 Activity의 명시적 사용자 조작만 허용 |
| 녹음 지속 | 사용자 시작 foreground service가 잠금·백그라운드에서 지속 |
| 시간 창 | 실행 중인 service 내부에서만 적용 |
| 업로드 | Wi-Fi 우선 자동 동기화와 사용자 수동 동기화 |
| 서버 | legacy 유지 + `/api/v1` additive 계약 |
| 노트 | 조회·편집·보관과 revision 충돌 처리 포함 |
| 새 노트 | `/api/v1` POST 계약을 구현해 포함 |
| 앱 업데이트 | 버전 확인만 포함, APK 자동 다운로드·설치는 V1 제외 |
| 부팅/watchdog | 무인 마이크 시작·부활 제외 |
| 통화 폴더/all-files | V1 제외 |
| 데이터 전환 | 서버 설정 재입력, 기존 private data 자동 이전 없음 |
| 디자인 | `Quiet Archive`, 고정 palette, dynamic color 기본 off |
| 원격 서버 | LAN/VPN/GCP public-CA HTTPS base URL 지원 |

## 서명·병행 설치 근거

기존 APK의 서명 개인키는 저장소에서 확인되지 않았다. 따라서 같은 package를 사용하는
업데이트 APK가 아니라 별도 package로 병행 설치한다.

```text
APK SHA-256:
d3067f3210aff0c51779adfa6b66bd9621f09ac3119a5e300a577995cd8f54fb

package: com.thinktank.recorder
versionCode: 22
versionName: 0.1.22+5e551a1
signer SHA-256:
f771c946ca8823571511bdb31e53ec9ba5777b995604165b55d6f2b011d4ccc7
signature scheme: APK Signature Scheme v2
```

## 기존 데이터 전환

| 데이터 | 신규 package 접근 | V1 처리 |
|---|---|---|
| 기존 SharedPreferences/token | 불가 | 사용자가 다시 입력 |
| 기존 앱 private 녹음 | 불가 | 기존 앱에서 업로드 완료 후 전환 |
| 공용 파일 | 사용자 승인 시 가능 | 후속 SAF import 후보 |
| 서버 노트 | 가능 | 동일 서버 계정으로 재동기화 |
| 기존 업로드 완료 Set | 불가 | 신규 v1 receipt/Room ledger 사용 |

## 기능 분류 승인

- A/B 기능은 V1 구현 대상으로 승인한다.
- E 기능은 `/api/v1` 서버 계약이 테스트로 통과한 뒤 구현한다.
- C 기능은 별도 internal flavor 승인 전 구현하지 않는다.
- D 기능은 코드와 Manifest에 포함하지 않는다.
- 앱 내부 APK 다운로드·설치는 버전 확인과 분리해 후속 범위로 둔다.

## 디자인 승인 기준

- 녹음·노트·설정은 이미지 없이도 기능과 상태가 이해되어야 한다.
- 이미지와 글꼴은 출처·권리·checksum이 기록된 파일만 사용한다.
- neon gradient, glass card soup, sparkle/robot, 의미 없는 3D 장식을 금지한다.
- bundled raster는 1.5MB를 목표로 하고 2.5MB를 초과하면 빌드를 차단한다.
- 모든 조작은 48dp 이상 touch target과 색 이외의 상태 표현을 제공한다.

