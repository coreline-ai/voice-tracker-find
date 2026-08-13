# 과거 QA APK 보관 안내

이 디렉터리의 APK는 `AI R Voice` 리브랜딩 이전 검증 증적이다. 현재 설치 파일이 아니며
파일명과 package에 과거 제품 식별자가 의도적으로 남아 있다.

보관 파일: `thinktank-recorder-next-1.0.0-qa-debug-signed-r11.apk`

- 과거 package: `com.thinktank.recorder.next`
- version: `1.0.0 (1)`
- SHA-256: `2fc058b127e68c00a8d1801c1a1db9d7b4c35679d602f4c749cb95d08bd5e38f`
- 서명: Android debug certificate
- 용도: 2026-07 리브랜딩 이전 QA 결과 재현

현재 AI R Voice QA 설치 파일은 저장소 루트의 `ai-r-voice.apk`이며 package는
`com.coreline.ai.voice.qa`다. 운영 배포 전에는 별도 보관된 release signing key와
`AIRVOICE_RELEASE_*` 환경변수로 release artifact를 다시 생성해야 한다.
