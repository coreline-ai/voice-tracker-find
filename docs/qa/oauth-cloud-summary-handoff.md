# OAuth cloud summary handoff

## 현재 상태

| 항목 | 상태 |
|---|---|
| 앱 구현 | `READY` |
| 자동 test/lint/build | `PASS` |
| Room 9→10 physical-device migration | `PASS` (2 devices) |
| SDK artifact 기준선 | `ai.coreline.oauthllm:oauth-llm-android:0.1.0` |
| public registration | source-controlled compatibility defaults; production replacement required |
| 실계정 E2E | `DEFERRED_BY_OWNER` / `executed=false` |
| release 배포 | unsigned, signing 전 |

실계정 검증 전까지 현재 구현과 검증 결과를 기준선으로 유지한다. 실계정 로그인, refresh,
generate, logout을 실행하지 않았으므로 해당 항목은 완료로 간주하지 않는다.

## 보존할 기준선

- SDK는 `android-app/local-maven`의 Maven artifact만 소비한다.
- 클라우드 요약은 immediate/manual summary에 적용된다.
- 장시간 background 계층형 요약은 기존 Gemma checkpoint 경로를 유지한다.
- 전송 대상은 제한된 전사 텍스트뿐이며 오디오와 OAuth 비밀 값은 소비 앱에 노출하지 않는다.
- Provider 실패 시 다른 Provider로 자동 전환하지 않는다.
- `UserCancelled`와 `InvalidRequest`는 자동 Gemma 폴백 대상이 아니다.

## 실계정 단계 재개 체크리스트

1. Provider별 QA 계정을 준비하고 source-controlled compatibility registration이 허용되는지
   확인하거나 Provider-approved ThinkTank public registration으로 교체한다.
2. 다른 registration/model이 필요하면 `android-app/oauth-llm.properties.example`을 참고해
   ignored `android-app/local.properties`에 override한다. client secret은 추가하지 않는다.
3. 배포용이 아닌 서명된 QA APK를 만들고 앱 ID/callback registration을 대조한다.
4. `docs/qa/oauth-llm-e2e-runbook.md`를 Provider별로 독립 실행한다.
5. token, code, PKCE, cookie, raw body, 실제 사적 전사를 제외한 sanitized evidence만 남긴다.
6. 모든 필수 행의 증적이 있을 때만 해당 Provider의 `executed=true`를 기록한다.
7. E2E에서 결함이 발견된 경우에만 구현 기준선을 수정하고 전체 자동 검증을 다시 실행한다.

## 현재 산출물

- debug APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`
- unsigned release APK: `android-app/app/build/outputs/apk/release/app-release-unsigned.apk`
- 자동 검증 보고서: `docs/qa/oauth-cloud-summary-implementation-20260802.md`
- 실계정 런북: `docs/qa/oauth-llm-e2e-runbook.md`
- 구현 계획: `dev-plan/implement_20260802_204022.md`

빌드 산출물은 재현 가능한 결과물이며 Git 추적 대상이 아니다. release 서명/배포는 signing
configuration을 별도로 준비한 뒤 진행한다.
