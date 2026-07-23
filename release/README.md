# QA APK 안내

파일: `thinktank-recorder-next-1.0.0-qa-debug-signed-r11.apk`

- package: `com.thinktank.recorder.next`
- version: `1.0.0 (1)`
- SHA-256: `2fc058b127e68c00a8d1801c1a1db9d7b4c35679d602f4c749cb95d08bd5e38f`
- 서명: Android debug certificate (v2/v3 검증 통과)

이 파일은 **QA 설치 및 기능 검증 전용**입니다. Android debug certificate로 서명되어 있으므로 운영 배포, 기존 운영 앱 업데이트, Play 배포에 사용하지 마십시오.

운영 배포 전에는 별도 보관된 release signing key로 `THINKTANK_RELEASE_STORE_FILE`, `THINKTANK_RELEASE_STORE_PASSWORD`, `THINKTANK_RELEASE_KEY_ALIAS`, `THINKTANK_RELEASE_KEY_PASSWORD`를 제공하여 release artifact를 다시 생성해야 합니다.
