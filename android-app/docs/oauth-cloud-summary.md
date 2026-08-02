# OAuth cloud summary integration

ThinkTank consumes the independent SDK only from `android-app/local-maven`:

```text
ai.coreline.oauthllm:oauth-llm-android:0.1.0
```

There is no Gradle source-project dependency on the SDK repository. `feature-cloud-summary` owns
OAuth account UI/controller integration, transcript prompt/schema parsing and SDK failure mapping.
`feature-ondevice` owns the consumer policy: active OAuth profile first, then Gemma only when
`fallbackEligible=true`. It never tries a second remote Provider.

## Build configuration

Reproducible compatibility defaults are source-controlled in
`android-app/oauth-llm.defaults.properties`. A different registration or model can override them
through Gradle properties, environment variables, or ignored `android-app/local.properties` entries:

| Name | Purpose |
|---|---|
| `THINKTANK_ANTHROPIC_CLIENT_ID` | Anthropic public OAuth client ID |
| `THINKTANK_CODEX_CLIENT_ID` | Codex public OAuth client ID |
| `THINKTANK_XAI_CLIENT_ID` | xAI public OAuth client ID |
| `THINKTANK_ANTHROPIC_MODEL` | Anthropic model used for structured summary |
| `THINKTANK_CODEX_MODEL` | Codex model used for structured summary |
| `THINKTANK_XAI_MODEL` | xAI model used for structured summary |

For an override, append the required entries from `oauth-llm.properties.example` to the ignored
`local.properties`. Override values are intentionally omitted:

```properties
THINKTANK_ANTHROPIC_CLIENT_ID=
THINKTANK_ANTHROPIC_MODEL=
THINKTANK_CODEX_CLIENT_ID=
THINKTANK_CODEX_MODEL=
THINKTANK_XAI_CLIENT_ID=
THINKTANK_XAI_MODEL=
```

Do **not** add a client secret. This Android app is a public OAuth client; confidential secrets in
an APK are not supported. `local.properties` must remain uncommitted. The checked-in identifiers are
compatibility registrations found in the approved read-only reference implementation; public does not
imply registration ownership. Replace them with Provider-approved ThinkTank registrations before a
production release.

## Runtime behavior

1. The user connects and activates exactly one profile in Settings.
2. A summary request sends only a bounded transcript to that profile's Provider.
3. A valid remote response is stored with actual Provider/model, safe request ID, latency and usage.
4. `NotConnected`, auth-required, network, 429, 5xx, timeout, invalid response and app model
   configuration failures may run Gemma locally.
5. `UserCancelled` and `InvalidRequest` never trigger automatic Gemma fallback.
6. With no active profile, the existing local-only flow remains the default.

OAuth credentials, authorization codes, PKCE verifiers, raw HTTP objects and raw Provider error
bodies are owned by the SDK and never enter ThinkTank contracts, logs, Room rows or Compose state.

## Verification

```bash
cd android-app
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-configuration-cache --no-parallel \
  :feature-cloud-summary:testDebugUnitTest \
  :feature-ondevice:testDebugUnitTest \
  :app:testDebugUnitTest \
  :feature-cloud-summary:lintDebug \
  :feature-ondevice:lintDebug \
  :app:lintDebug \
  :app:assembleDebug :app:assembleRelease
```

Real-account validation follows `docs/qa/oauth-llm-e2e-runbook.md` and must not be marked complete
without sanitized evidence for each configured Provider.
