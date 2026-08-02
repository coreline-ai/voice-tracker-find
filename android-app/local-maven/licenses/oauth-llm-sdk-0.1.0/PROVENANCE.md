# Provenance and clean-room policy

## Ownership baseline

New source authored in this repository is intended to be owned by the user and
licensed under the proprietary `LICENSE`. The legal name in `LICENSE` and
`NOTICE` is deliberately unresolved and is a release blocker, not an ownership
transfer to a tool vendor, reference-project author, or cloud provider.

The repository is a standalone Gradle build. It has no source-project or build
dependency on either read-only reference repository. The Gradle Wrapper files
are upstream Gradle distribution files and are covered by the Gradle license.

## Classification meanings

| Classification | Meaning |
|---|---|
| `rewrite` | Requirements and externally observable protocol behavior may be studied, but implementation, naming, tests, and documentation are authored independently in the `ai.coreline.oauthllm` namespace. |
| `reference-only` | Consulted to establish compatibility or constraints; no source or user-facing material is carried into this repository. |
| `discard` | Out of scope and intentionally not used in the SDK or sample. |

Generated build output below either reference directory is excluded from review
and must never be copied into this repository.

## SDK reference classification

Read-only root: `/Volumes/Eprojects/project_202607/alpine-llm-gateway/android`

| Reference file | Classification | Independent-repository treatment |
|---|---|---|
| `README.md`, `build.gradle.kts`, `consumer-rules.pro` | `reference-only` | Compatibility and dependency observations only. |
| `src/main/AndroidManifest.xml` | `rewrite` | Declare only activities and metadata required by this SDK. |
| `AnthropicOAuthContract.kt`, `CodexOAuthContract.kt`, `XaiOAuthContract.kt` | `rewrite` | Re-express approved provider registrations as validated, host-configured contracts; do not copy registration identifiers. |
| `OAuthPkce.kt`, `OAuthProviderConfig.kt` | `rewrite` | Independently implement public-client configuration, PKCE, state, and nonce policy. |
| `OAuthCallbackRegistry.kt`, `OAuthCallbackServer.kt`, `OAuthRedirectActivity.kt` | `rewrite` | Independently implement strict callback validation and isolated loopback handling. |
| `OAuthManager.kt`, `OAuthRefreshCoordinator.kt`, `OAuthTokenStore.kt` | `rewrite` | Independently implement encrypted persistence, refresh coordination, and rotation. |
| `OAuthTokenRequestAdapter.kt`, `OAuthTokenResponseAdapter.kt`, `JwtClaimMetadataTokenResponseAdapter.kt` | `rewrite` | Independently implement structured token exchange and safe metadata normalization. |
| `OAuthDiscovery.kt`, `ProviderProtocolAdapters.kt`, `CodexResponsesOAuthAdapter.kt` | `rewrite` | Independently implement allowlisted discovery and provider wire adapters with golden tests. |
| `OAuthFailure.kt`, `OAuthHttpLlmBridge.kt` | `rewrite` | Replace with the SDK's Android-independent typed failure and session contracts. |
| `GatewayOperations.kt`, `HostLlmBridge.kt` | `discard` | Host-bridge and process-operation abstractions are not part of this SDK. |
| `GeminiOAuthContract.kt` | `discard` | Provider is outside the three-provider scope. |
| `src/test/**`, `src/androidTest/**` | `reference-only` | Observe risk cases only; author new fixtures, assertions, and golden data. |

## Demo reference classification

Read-only root: `/Volumes/Eprojects/project_202607/alpine-llm-gateway/demo-chatbot`

| Reference file or group | Classification | Independent-repository treatment |
|---|---|---|
| `README.md`, `DESIGN.md`, `build.gradle.kts`, `src/main/AndroidManifest.xml` | `reference-only` | Used only to understand an end-to-end validation flow. |
| `src/main/java/**/DemoDependencies.kt`, `MainActivity.kt`, `ProviderEditActivity.kt`, `ProviderProfilesActivity.kt` | `discard` | The sample uses a new minimal, non-Compose connect/generate/disconnect interface. |
| `src/main/java/**/assistant/**`, `data/**`, `llm/**`, `model/**`, `ui/**` | `discard` | Assistant features, persistence, models, state, themes, and UI are not reused. |
| `src/main/res/**` | `discard` | No reference visual, text, icon, theme, or resource is reused. |
| `src/test/**`, `src/androidTest/**` | `discard` | Demo tests and support fixtures are not reused. |

## Planning and compatibility references

| Reference | Classification | Use |
|---|---|---|
| `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/dev-plan/implement_20260802_190531.md` | `reference-only` | Scope, security, release, and consumer-boundary requirements. |
| `/Volumes/Eprojects/project_202607/thinktank-release-v0.1.22/android-app/gradle/libs.versions.toml` | `reference-only` | Kotlin/AGP/SDK/JVM/coroutines/OkHttp compatibility baseline. |

## Required provenance audit

Before release, verify that source packages use only this repository's namespace,
that no reference binary or generated output is present, and that all resolved
third-party dependencies appear in the generated dependency/license inventory.
