# Samsung 실기기 화면 QA — 2026-07-24

## 범위와 방법

- 대상: `com.thinktank.recorder.next.debug` `1.0.0-debug (1)`
- 기기: Samsung `SM-S931N`, Android 16, 1080 × 2340
- 범위: 녹음, 노트 목록, 노트 상세, 설정 상단 및 서버 설정 영역
- 방법: 실기기 화면 캡처, UI hierarchy의 실제 bounds 측정, 독립 QA 에이전트의 소스 교차 검토
- 최초 QA는 코드 변경 없이 수행했고, 아래의 `수정 후 실기기 검증`에서 P1·P2 보정 결과를 기록한다.

## 캡처

| 화면 | 증빙 |
| --- | --- |
| 녹음 시작 | [recording.png](screenshots/2026-07-24-layout/recording.png) |
| 녹음 상태 | [recording_status.png](screenshots/2026-07-24-layout/recording_status.png) |
| 노트 목록 | [notes.png](screenshots/2026-07-24-layout/notes.png) |
| 노트 상세 | [note_detail_actual.png](screenshots/2026-07-24-layout/note_detail_actual.png) |
| 설정 상단 | [settings.png](screenshots/2026-07-24-layout/settings.png) |
| 설정 서버 영역 | [settings_connection.png](screenshots/2026-07-24-layout/settings_connection.png) |

## 결론

사용자 제보는 재현됐다. 노트의 `아카이브`와 설정의 `설정` 제목은 바로 아래 보조 문구와 실제 0px 간격으로 이어진다. 제목과 보조 문구를 별도 정보 계층으로 읽기 어렵게 만드는 P1 레이아웃 문제다.

| ID | 우선순위 | 문제 | 증빙 및 원인 | 권장 수정 |
| --- | --- | --- | --- | --- |
| QA-01 | P1 | 노트 제목과 보조 문구가 붙음 | 실기기 bounds: `아카이브 [56,143–224]`, 보조 문구 시작 `y=224`. [NotesScreen.kt:87](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/notes/NotesScreen.kt#L87)와 88행이 연속 `Text`다. | 제목 `Text`에 `padding(bottom = 8.dp)`를 주거나 두 텍스트 사이에 `Spacer(8.dp)`를 둔다. |
| QA-02 | P1 | 설정 제목과 보조 문구가 붙음 | 실기기 bounds: `설정 [56,159–240]`, 보조 문구 시작 `y=240`. [SettingsScreen.kt:79](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/settings/SettingsScreen.kt#L79)와 80행이 연속 `Text`다. | QA-01과 동일한 `8.dp` 규칙을 적용한다. 설정의 보조 문구 아래 `32.dp` 여백은 유지한다. |
| QA-03 | P1 | 노트 목록과 상세에 YAML frontmatter가 사용자에게 노출됨 | 목록 미리보기에 `--- type: daily_section`, 상세에 `type: daily_section date: ...`가 보인다. [NotesScreen.kt:175](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/notes/NotesScreen.kt#L175) 미리보기와 [NotesScreen.kt:501](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/notes/NotesScreen.kt#L501) 렌더러가 frontmatter를 분리하지 않는다. | `---`로 감싼 선행 메타데이터를 한 곳에서 제거/파싱한 뒤 목록 미리보기와 `MarkdownDocument` 모두에 전달한다. |
| QA-04 | P2 | 노트 상세 상단 제목이 파일명 그대로 표시됨 | `2026-07-24_중요`처럼 밑줄이 사용자용 제목으로 노출된다. [NotesScreen.kt:285](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/notes/NotesScreen.kt#L285) | H1을 우선 표시하고, 없으면 `.md` 제거 후 `_`를 공백으로 바꾼 표시용 제목을 사용한다. |
| QA-05 | P2 | 상세 화면 뒤로가기 터치 영역이 약 40dp | 실기기 bounds가 113 × 113px이며 해당 기기의 450dpi 기준 약 40dp다. [NotesScreen.kt:291](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/notes/NotesScreen.kt#L291) | `IconButton`에 `sizeIn(minWidth = 48.dp, minHeight = 48.dp)`를 지정해 최소 터치 타깃을 보장한다. |
| QA-06 | P2 | 디버그 환경에서 `HTTPS 서버 주소`와 HTTP 값이 동시에 표시됨 | 설정 화면에는 `HTTPS 서버 주소`라고 쓰였으나 실기기 설정값은 `http://192.168.0.71:8765`다. [SettingsScreen.kt:186](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/settings/SettingsScreen.kt#L186)와 [AppPreferences.kt:111](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/data/settings/AppPreferences.kt#L111)의 debug 예외가 불일치 원인이다. | debug에서는 라벨을 `서버 주소`로 바꾸거나, HTTP를 넣었을 때 `개발용 HTTP` 안내를 명시한다. release의 HTTPS 강제 정책은 유지한다. |

## 정상 확인 항목

- 공통 `Scaffold`가 하단 탭 바와 시스템 내비게이션 인셋을 처리한다. [ThinkTankApp.kt:93](../../android-app/app/src/main/kotlin/com/thinktank/recorder/next/ui/ThinkTankApp.kt#L93)
- 녹음 제어는 충분히 큰 원형 터치 타깃이며, 녹음 상태 카드와 하단 탭 바가 겹치지 않는다.
- 노트 목록의 동기화/추가 버튼과 설정 주요 제어는 48dp 터치 영역이다.
- 설정의 하단 서버/앱 정보는 스크롤 끝까지 접근 가능하다. 중간 스크롤 캡처에서 하단에 보이는 버튼 일부는 고정 탭 바의 가림이 아니라 더 아래 내용이 남아 있는 상태다.

## 권장 적용 순서 및 완료 기준

1. QA-01, QA-02: 두 헤더에 `8.dp` 제목 하단 여백을 같은 방식으로 적용한다.
2. QA-03: frontmatter 제거 함수를 만들고 목록/상세 공통으로 사용한다.
3. QA-04, QA-05, QA-06: 상세 앱바의 표시/접근성과 개발용 서버 라벨을 보정한다.
4. Samsung에서 동일 6개 화면을 다시 캡처한다. 제목 글리프 하단과 보조 문구 상단 사이에 최소 `8.dp`의 빈 공간이 있어야 하며, 목록/상세 어디에도 `type:` 메타데이터가 보이면 안 된다.

## 수정 후 실기기 검증

- 적용 APK: `com.thinktank.recorder.next.debug` `1.0.0-debug (1)`을 Samsung `SM-S931N`에 재설치했다.
- QA-01: `아카이브`의 글리프 하단 `y=218`과 보조 문구 시작 `y=241` 사이에 약 8dp(23px) 간격이 생겼다. [수정 후 노트](screenshots/2026-07-24-layout/fixed_notes.png)
- QA-02: `설정`의 글리프 하단 `y=240`과 보조 문구 시작 `y=263` 사이에 약 8dp(23px) 간격이 생겼다. [수정 후 설정](screenshots/2026-07-24-layout/fixed_settings.png)
- QA-03: 노트 목록/상세에 `type:` 또는 `---` frontmatter가 노출되지 않는다. 편집 저장 때 원래 frontmatter는 다시 결합해 보존한다. [수정 후 노트 상세](screenshots/2026-07-24-layout/fixed_note_detail.png)
- QA-04/05: 상세 앱바 제목은 `2026-07-24 중요`으로 표시되며, 뒤로가기의 클릭 가능한 부모 bounds는 135 × 135px(48dp)다.
- QA-06: debug 환경의 HTTP 설정은 `서버 주소` 라벨로 표시된다. [수정 후 서버 설정](screenshots/2026-07-24-layout/fixed_settings_server.png)
- 자동 검증: 대상 `MarkdownParserTest`, 전체 `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`가 모두 성공.
