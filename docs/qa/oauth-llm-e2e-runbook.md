# OAuth LLM real-account E2E runbook

## Status

- `executed=false`
- `status=DEFERRED_BY_OWNER`
- `resume_condition=public client registrations + QA accounts + signed QA build`
- Real Anthropic/Codex/xAI credentials were not supplied during implementation.

## Safety rules

- Use a QA account and a build configured with public client IDs only.
- Never capture access/refresh tokens, authorization codes, PKCE values, cookies, raw HTTP bodies,
  or the full private transcript in screenshots, logs, bug reports, or this report.
- Use a short synthetic Korean transcript containing no personal or confidential data.
- Record only Provider name, model, redacted request ID prefix, latency, normalized usage and result.

## Provider matrix

Run the following separately for Anthropic, Codex and xAI. A failure in one row must not cause the
app to select another Provider.

1. Install the signed QA APK without clearing the persistent QA package unless migration testing is intended.
2. Open Settings → Cloud summary account and connect the target Provider.
3. Confirm the profile becomes active; restart the app and confirm selection restoration.
4. Create a synthetic recording, finish STT and request a summary.
5. Verify the result card reports the same Provider/model and remote transcript data policy.
6. Expire or revoke the account, then verify auth-required produces eligible local fallback if Gemma is installed.
7. Force offline, timeout, 429 and 5xx fixtures separately; verify one remote attempt and one Gemma fallback.
8. Cancel the request and exercise invalid input; verify Gemma is not automatically executed.
9. Disconnect the profile, restart, and verify SDK credentials are no longer usable.
10. Complete the report template below without adding secret or raw-body evidence.

## Report template

| Field | Value |
|---|---|
| executed | false |
| status | deferred by owner |
| date/device/build | not run |
| Provider | not run |
| login | not run |
| generate | not run |
| refresh/reauth | not run |
| logout | not run |
| offline fallback | not run |
| 429 fallback | not run |
| 5xx fallback | not run |
| timeout fallback | not run |
| cancel no-fallback | not run |
| invalid request no-fallback | not run |
| DB/UI provenance match | not run |
| sanitized evidence path | none |

Change `executed` to `true` only after every required row for that Provider has real sanitized
evidence. Unit tests and MockWebServer/fixture results are not real-account E2E evidence.
