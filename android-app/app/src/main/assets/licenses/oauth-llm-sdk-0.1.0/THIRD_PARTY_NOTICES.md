# Third-party notices

The SDK source is proprietary. The build and runtime dependencies below remain
under their own licenses. This inventory is a release aid, not a replacement
for the license text shipped by each dependency.

| Component | Use | License |
|---|---|---|
| Gradle and Gradle Wrapper | Build tooling | Apache License 2.0 |
| Android Gradle Plugin | Build tooling | Apache License 2.0 |
| Kotlin Gradle plugins and standard library | Build/runtime | Apache License 2.0 |
| Kotlin binary compatibility validator | API verification | Apache License 2.0 |
| kotlinx.coroutines | Public API/runtime/tests | Apache License 2.0 |
| AndroidX Core, Activity, AppCompat, Lifecycle and Test | Android runtime/sample/tests | Apache License 2.0 |
| AppAuth for Android | Standard OAuth/OIDC authorization | Apache License 2.0 |
| OkHttp and MockWebServer | HTTPS runtime/tests | Apache License 2.0 |
| Turbine | Flow tests | Apache License 2.0 |
| Robolectric | Android local tests | MIT License |
| JUnit 4 | Tests | Eclipse Public License 1.0 |
| JSON-java (`org.json`) | JVM tests only | JSON License |

Transitive dependencies must be captured from the resolved release graph before
each distribution. A release must not be approved solely from this hand-written
inventory.

Provider APIs and trademarks are not bundled third-party software. Use of a
provider service remains governed by that provider's applicable terms.
