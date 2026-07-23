# Quiet Archive Design System

## Color

| Token | Dark | Light | Role |
|---|---|---|---|
| ArchiveInk | `#151614` | `#24241F` | 배경·본문 |
| ArchivePaper | `#EEE8DC` | `#F7F3EA` | 노트 표면 |
| ArchiveCopper | `#C07148` | `#A95632` | 녹음·주요 동작 |
| ArchiveMoss | `#7E8C78` | `#5F6D58` | 완료·안정 |
| ArchiveFog | `#A9AAA3` | `#6E706A` | 보조 정보 |
| ArchiveError | `#D06A60` | `#A73F39` | 오류 |
| ArchiveHairline | `#343530` | `#DDD6CA` | divider |

상태는 색만으로 구분하지 않고 라벨·아이콘·진행 정보와 함께 표시한다.

라이트 배경에서 상태/링크/버튼 텍스트는 AA 대비를 유지한다. 따라서 라이트 Moss는
`#5F6D58`을 사용하고, 항상 밝은 Markdown paper 위에 표시되는 편집 링크·인용선에는
별도 `ArchiveNoteCopper #8C452B`를 사용한다. 화면 공통 action은 `ColorScheme.primary`
또는 `secondary`를 통해 테마별 대비 토큰을 사용하며 dark copper/moss 상수를 라이트
배경의 본문·상태 텍스트에 직접 쓰지 않는다.

## Type

- UI, 숫자, 상태: Pretendard
- 짧은 editorial heading: MaruBuri
- body: 16sp / 24sp
- secondary: 13–14sp
- screen title: 28–32sp
- recording timer: 48–56sp tabular number

폰트 파일을 번들하지 못하는 빌드는 Android sans-serif/serif fallback을 사용하며 기능과
레이아웃은 유지한다.

## Space and Shape

- spacing: 4, 8, 12, 16, 24, 32, 48dp
- corner: control 10dp, sheet 18dp, primary record control 28dp
- stroke: 1dp hairline, focus 2dp
- elevation: 기본 0, floating control 4dp, modal 8dp 이하
- touch target: 최소 48×48dp

## Motion

- 화면 전환: 180–240ms
- 녹음 상태: 220–320ms
- 지속 glow/pulse, parallax, 장식 Lottie 금지
- reduced motion에서는 파형을 정적 level과 텍스트 상태로 대체
