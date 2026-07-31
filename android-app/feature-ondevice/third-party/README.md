# On-device model runtime provenance

The SenseVoice model is not bundled in the APK. It is installed only after an
explicit user action and is verified against the pinned size and SHA-256 in
`ModelCatalog.kt`.

## sherpa-onnx

- Purpose: completed-recording STT
- Runtime: arm64 JNI/ONNX
- Model: `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`
- Distribution: official `k2-fsa/sherpa-onnx` GitHub release
- Installed files: `model.int8.onnx`, `tokens.txt`

Inference reads only the normalized private PCM snapshot and the installed
model directory. The app does not upload the recording or transcript.

## Gemma 3 1B

- Purpose: default local summary after STT
- Runtime: LiteRT-LM 0.14.0, CPU backend, isolated app process
- Model: `Gemma3-1B-IT` int4 `.litertlm`
- Distribution: official `litert-community/Gemma3-1B-IT` repository
- Expected SHA-256: pinned in `ModelCatalog.kt`

The gated official model is imported only after the user accepts its license.
Inference receives the locally stored transcript and does not use a network
client. Qwen, EXAONE, and llama.cpp are not part of the active runtime.
