# AI R Voice Console 웹 대시보드 디자인 결정

## 목적

맥미니에서 실행 중인 기존 `airvoice.receiver`의 상태를 같은 LAN에서 확인한다. 이 문서와 구현은 별도 클라우드, 별도 웹 서버, 외부 모니터링 SaaS를 추가하지 않는 것을 전제로 한다.

## 유사 서비스 검토

| 참고 서비스 | 확인한 패턴 | 이 프로젝트에 적용한 판단 |
| --- | --- | --- |
| [Grafana Dashboards](https://grafana.com/docs/grafana/latest/visualizations/dashboards/) | 관련 데이터를 패널로 묶어 한눈에 보고, 패널을 행/탭으로 그룹화한다. | 화면을 `상태 → 대기열 → 노트 → 런타임/저장소` 패널로 나눈다. 복잡한 시계열 차트는 현재 데이터가 부족하므로 넣지 않는다. |
| [Uptime Kuma](https://github.com/louislam/uptime-kuma) / [Status Page 문서](https://github.com/louislam/uptime-kuma/wiki/Status-Page) | self-hosted 운영, 명확한 Up/Down 상태, 주기 갱신, 운영자용 대시보드와 공개 상태 페이지의 분리. | 대시보드는 토큰 인증으로 보호하고, 서버의 `operational / 인증 실패 / 확인 실패`를 상단 배지로 즉시 표시한다. |
| [GitHub Actions workflow monitoring](https://docs.github.com/en/actions/how-tos/monitor-workflows?tool=webui) | 현재 상태만이 아니라 실행 이력·단계·로그를 함께 보여주어 다음 조치를 판단한다. | 현재는 처리 이력 DB가 별도 계약으로 정의되지 않았으므로, 실제 운영 판단에 필요한 `최근 수집 파일`과 `최근 노트 변경`을 이력의 최소 단위로 사용한다. |
| [Netdata Dashboards and Charts](https://learn.netdata.cloud/docs/dashboards-and-charts/) / [Charts](https://learn.netdata.cloud/docs/dashboards-and-charts/charts) | 로컬 Agent 접근, 실시간 상태, overview와 상세 차트의 계층, 데이터가 있을 때만 깊이를 더하는 구조. | 현재 서버에는 초당 메트릭 수집기가 없으므로 실시간 차트를 흉내 내지 않고 10초 상태 스냅샷과 실제 파일 시각을 표시한다. |

## 확정 디자인

표시 이름은 **AI R Voice Console**, 브라우저 제목은 **AI R Voice · LAN Console**로 정했다. 기존 앱의 archive/ink 계열 시각 언어를 이어받아 따뜻한 종이색 배경, 짙은 잉크색 본문, copper 강조색, sage 정상 상태색을 사용한다.

정보 우선순위는 아래와 같다.

1. **Receiver status** — 서버가 인증된 요청에 응답하는지, 포트·시작 시각·자동 처리·TLS 여부
2. **Inbox files** — 보존 기간 안의 수신 오디오 파일 개수·총 용량·최근 도착 시각·M4A 재생
3. **Exposed notes** — Android 앱에 공개되는 최신 노트와 폴더
4. **Disk available** — 수집 폴더가 위치한 볼륨의 사용량·여유 공간
5. **Next** — 자동 처리 설정과 최근 노트 기준의 다음 조치

이 구조는 운영자가 화면을 열자마자 “서버가 살아 있는가 → 녹음이 도착했는가 → 저장할 공간이 있는가”를 읽을 수 있도록 설계했다. 수신 파일 수는 처리 대기 수가 아니라 retention 기간 동안 남는 원본 수이므로, 처리 완료 여부는 최근 노트 생성으로 판단한다. 작은 화면에서는 이 순서를 유지한 채 단일 컬럼으로 접힌다.

## 구현 경계

- UI는 `web/dashboard/`의 HTML/CSS/JavaScript이며 외부 프레임워크와 CDN을 사용하지 않는다.
- 기존 Python receiver가 `/dashboard` 정적 파일과 `/api/v1/dashboard/summary` 읽기 전용 API를 함께 제공한다.
- 대시보드 API는 기존 `Authorization: Bearer <RECEIVER_TOKEN>` 인증을 재사용한다.
- 토큰은 URL, HTML, API 응답에 포함하지 않는다. 브라우저 `sessionStorage`에만 저장하고 “이 세션에서 토큰 지우기”를 제공한다.
- 응답에는 절대 경로·다른 사용자의 정보·토큰을 넣지 않는다.
- 10초 폴링은 별도 WebSocket/메시지 브로커 없이 현재 receiver의 LAN 운영 목적에 충분한 주기다.

### 콘텐츠 확인 확장

- 최근 노트의 `보기`는 `/api/v1/dashboard/notes/{folder}/{name}`에서 선택한 사용자 범위의 Markdown만 읽는다. 원문은 HTML로 렌더링하지 않고 읽기 전용 텍스트로 표시한다.
- 수신 파일의 `듣기`는 `/api/v1/dashboard/audio/{filename}`에서 M4A만 bearer header로 받아 브라우저 메모리의 Blob URL로 재생한다. `RECEIVER_TOKEN`이나 재생용 토큰은 URL에 넣지 않는다.
- 서버는 공개 노트 폴더·안전한 leaf filename·파일 존재를 모두 확인하며, 경로 탐색·다른 사용자 파일·M4A 이외 오디오는 거절한다.
- M4A 원본의 보존 기간과 수집 폴더 정책은 변경하지 않는다. 파일이 cleanup된 뒤에는 재생 대신 파일 없음 오류를 표시한다.

## 현재 접속 방법

receiver가 실행 중인 맥미니에서 `http://<맥미니-LAN-IP>:8765/dashboard`를 연다. 예를 들어 맥미니의 LAN 주소가 `192.168.0.71`이면 `http://192.168.0.71:8765/dashboard`이다. 페이지가 열린 뒤 앱과 동일한 `RECEIVER_TOKEN`을 입력한다.

삼성폰과 맥미니가 서로 다른 Wi-Fi 대역에 있으면 폰에서 이 주소에 접근할 수 없다. 이는 대시보드 코드 문제가 아니라 네트워크 경로 문제이므로, 실기기에서 확인할 때는 두 기기를 같은 LAN/VLAN에 연결해야 한다.

## 다음 확장 후보

- 처리 이력 테이블이 필요해지면 receiver SQLite ledger에 집계용 읽기 API를 추가한다.
- 서비스가 여러 개로 늘어날 때만 Grafana/Uptime Kuma를 별도 도입한다. 현재는 기존 서버 단일 프로세스에 유지하는 것이 운영 복잡도와 배포 위험이 가장 낮다.
