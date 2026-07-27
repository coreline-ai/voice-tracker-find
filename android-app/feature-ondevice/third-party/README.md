# On-device native runtime provenance

The model file is **not** bundled in the APK. It is installed only after an
explicit user action and is verified against the hash in `ModelCatalog.kt`.

## llama.cpp Android binding

- Upstream: <https://github.com/ggml-org/llama.cpp>
- Version: `b10107`
- Source: `examples/llama.android/lib`
- Output: `libs/llama-android-b10107-arm64.aar`
- SHA-256:
  `ee0934ae4288108a5e6976820dd51ae5558c51891e79bdf85e8d9af6104c7268`
- License: MIT; packaged as
  `src/main/assets/licenses/LLAMA-CPP-MIT.txt`

The AAR was built from the tagged source with Kotlin `1.9.24` (matching this
app), NDK `29.0.14206865`, CMake `3.22.1`, `arm64-v8a` only, and `minSdk 26`.
The app pins kotlinx-coroutines `1.9.0`, which is required by the Android
binding's single-thread inference dispatcher.

The upstream Android binding uses an API-30 loggability helper; for the
minSdk-26 build its log predicate was changed to a local minimum-level
comparison. Inference and model code were not modified except for mobile
resource limits: context was reduced from 8192 to 4096 tokens and the prompt
batch from 512 to 256. The binding also exposes deterministic temperature,
top-k, top-p, min-p, repeat/presence penalty, seed and GBNF grammar settings;
fixes the generation-token ceiling; and closes a JSON response as soon as its
root object is complete. The upstream information-level message that included
formatted prompt content was also reduced to role-only metadata so transcripts
and summaries cannot be written to logcat.

The AAR uses `BUILD_SHARED_LIBS=OFF`, `GGML_BACKEND_DL=OFF`,
`GGML_CPU_ALL_VARIANTS=OFF`, and `GGML_NATIVE=OFF`. This links the generic
Armv8 CPU backend into `libai-chat.so` instead of scanning and loading
architecture-specific backend DSOs at runtime.

The app-facing JNI surface used here only loads a local model path and runs
local prompts. The statically linked upstream common code may still contain
unused command-line download helpers, so the release privacy claim relies on
the restricted JNI call path plus the module's static network-boundary check
rather than claiming that every native object file is network-free.

## Model license

- `Qwen3.5-0.8B-Q4_0.gguf`: Apache-2.0 according to the upstream model card.
