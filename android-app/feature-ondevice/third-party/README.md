# On-device native runtime provenance

The model files are **not** bundled in the APK. They are installed only after an
explicit user action and are verified against the hashes in `ModelCatalog.kt`.

## sherpa-onnx

- Upstream: <https://github.com/k2-fsa/sherpa-onnx>
- Version: `v1.13.4`
- Release asset:
  `sherpa-onnx-v1.13.4-android.tar.bz2`
- Packaged ABI: `arm64-v8a`
- Packaged files:
  - `libonnxruntime.so`
    `994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533`
  - `libsherpa-onnx-jni.so`
    `a79ff75fbe1c3813cc239037b458a7828298a90a5b77f5314056508eefdf72bc`
- Kotlin JNI API source:
  `sherpa-onnx/kotlin-api` at tag `v1.13.4`
- License: Apache-2.0; packaged as
  `src/main/assets/licenses/SHERPA-ONNX-APACHE-2.0.txt`

Only the two libraries required by the upstream Android JNI instructions are
packaged. C/C++ API libraries and non-arm64 ABIs are intentionally excluded.

## llama.cpp Android binding

- Upstream: <https://github.com/ggml-org/llama.cpp>
- Version: `b10107`
- Source: `examples/llama.android/lib`
- Output: `libs/llama-android-b10107-arm64.aar`
- SHA-256:
  `96e22269f12a56d04be5577065d729677b0a61d606d38a8963d211a6cca4937c`
- License: MIT; packaged as
  `src/main/assets/licenses/LLAMA-CPP-MIT.txt`

The AAR was built from the tagged source with Kotlin `1.9.24` (matching this
app), NDK `29.0.14206865`, CMake `3.22.1`, `arm64-v8a` only, and `minSdk 26`.
The app pins kotlinx-coroutines `1.9.0`, which is required by the Android
binding's single-thread inference dispatcher.
The upstream Android binding uses
an API-30 loggability helper; for the minSdk-26 build its log predicate was
changed to a local minimum-level comparison. Inference and model code were not
modified except for mobile resource limits: context was reduced from 8192 to
4096 tokens and the prompt batch from 512 to 256. The upstream information-level
message that included formatted prompt content was also reduced to role-only
metadata so transcripts and summaries cannot be written to logcat.

The AAR uses `BUILD_SHARED_LIBS=OFF`, `GGML_BACKEND_DL=OFF`,
`GGML_CPU_ALL_VARIANTS=OFF`, and `GGML_NATIVE=OFF`. This links the generic
Armv8 CPU backend into `libai-chat.so` instead of scanning and loading
architecture-specific backend DSOs at runtime. The change avoids Android linker
namespace failures observed on older Cortex-A53/A73 devices and prevents an
unsupported instruction variant from being selected.

The app-facing JNI surface used here only loads a local model path and runs
local prompts. The statically linked upstream common code may still contain
unused command-line download helpers, so the release privacy claim relies on
the restricted JNI call path plus the module's static network-boundary check
rather than claiming that every native object file is network-free.

## Model licenses

- `Qwen3.5-0.8B-Q4_0.gguf`: Apache-2.0 according to the upstream model card.
- `Moonshine Tiny Korean`: Moonshine Community License. The downloaded model
  archive includes the complete license and the installer retains it inside
  the private model directory. A pre-install copy is packaged as
  `MOONSHINE-KOREAN-MODEL-LICENSE.txt` with SHA-256
  `6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd`.
  The Korean model has commercial registration and annual-revenue conditions;
  release approval must verify those conditions.
