# Compose Screen Specifications

## Recording

- 상단 40%: `hero_recording_chamber`, full-bleed crop, 하단 scrim
- hero 위: 상태 한 줄, 48–56sp 경과 시간
- 본문: 실제 amplitude 기반 `SoundThread`
- 176–200dp primary record control
- 저장 위치·현재 청크·업로드 큐·마지막 오류는 접을 수 있는 진단 영역

## Notes

- 상단 수동 동기화 action
- 폴더별 editorial label과 hairline divider
- row: 제목, 2줄 preview, 수정 시각, sync 상태
- 임의 thumbnail 금지
- 빈 목록에서만 `empty_notes_desk`
- 상세: paper surface, serif heading, sans body, WikiLink 강조

## Settings

- hero 없음
- 녹음·시간 창·동기화·서버·권한·앱 정보 section
- 모든 항목을 카드로 감싸지 않음
- token은 password field, 로그·screenshot에 노출 금지

## Layout Matrix

- 360×800
- 412×915
- font scale 100%, 130%, 200%
- portrait 기본, landscape에서 scroll과 system inset 보장
- light/dark

