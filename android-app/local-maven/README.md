# Local OAuth LLM Maven repository

This directory contains the private, proprietary `0.1.0` publication consumed by ThinkTank:

- `ai.coreline.oauthllm:oauth-llm-api:0.1.0`
- `ai.coreline.oauthllm:oauth-llm-android:0.1.0`

The artifacts were copied without modification from the independent SDK repository's successful
`publishAllPublicationsToLocalFileRepository` output on 2026-08-02 KST.
The adjacent `.sha256`/`.sha512` files are the publication checksums. ThinkTank never uses a Gradle
source-project dependency on that repository.

To update this directory, publish a reviewed SemVer release from the independent repository, verify its
API dump, tests, license, POM and checksums, then replace the complete version directories and update the
version catalog in one change. Do not hand-edit an AAR, JAR, POM, module metadata or checksum here.

These proprietary SDK files are not third-party open-source artifacts. Their use and redistribution remain
subject to the SDK repository's `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, and `PROVENANCE.md`.
