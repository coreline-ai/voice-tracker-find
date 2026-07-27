package com.thinktank.recorder.ondevice.modelpack

enum class ModelId {
    QWEN_SUMMARY_KO,
    EXAONE_SUMMARY_KO,
    GEMMA_SUMMARY_KO,
    SENSEVOICE_STT_KO,
}

enum class ModelRuntimeType {
    LLAMA_CPP,
    LITERT_LM,
    SHERPA_ONNX,
}

enum class ModelArtifactFormat {
    SINGLE_FILE,
    TAR_BZ2,
}

data class ModelDescriptor(
    val id: ModelId,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val remoteDownloadEnabled: Boolean = true,
    val expectedSha256: String,
    val exactArtifactBytes: Long,
    val approximateDownloadBytes: Long,
    val approximateInstallBytes: Long,
    val requiredFiles: Set<String>,
    val runtimeType: ModelRuntimeType,
    val artifactFormat: ModelArtifactFormat = ModelArtifactFormat.SINGLE_FILE,
    val archiveRoot: String = "",
)

object ModelCatalog {
    val models: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = ModelId.QWEN_SUMMARY_KO,
            displayName = "Qwen 로컬 AI 요약",
            version = "Q4_0",
            description = "전사문에서 제목, 핵심 요약, 할 일을 생성합니다.",
            downloadUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/" +
                "resolve/main/Qwen3.5-0.8B-Q4_0.gguf?download=true",
            expectedSha256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
            exactArtifactBytes = 563_036_064L,
            approximateDownloadBytes = 563_036_064L,
            approximateInstallBytes = 563_000_000L,
            requiredFiles = setOf("model.gguf"),
            runtimeType = ModelRuntimeType.LLAMA_CPP,
        ),
        ModelDescriptor(
            id = ModelId.EXAONE_SUMMARY_KO,
            displayName = "EXAONE 한국어 AI 요약",
            version = "4.0-1.2B-Q4_K_M",
            description = "한국어에 최적화된 로컬 AI로 핵심 구간을 선택하고 요약합니다.",
            downloadUrl = "https://huggingface.co/LGAI-EXAONE/" +
                "EXAONE-4.0-1.2B-GGUF/resolve/main/" +
                "EXAONE-4.0-1.2B-Q4_K_M.gguf?download=true",
            expectedSha256 = "7b5e753540183ae4d56e6febd9b48cdd944de53386e6faa8f51c8f98cb2b47df",
            exactArtifactBytes = 812_437_792L,
            approximateDownloadBytes = 812_437_792L,
            approximateInstallBytes = 813_000_000L,
            requiredFiles = setOf("model.gguf"),
            runtimeType = ModelRuntimeType.LLAMA_CPP,
        ),
        ModelDescriptor(
            id = ModelId.GEMMA_SUMMARY_KO,
            displayName = "Gemma 3 경량 AI 요약",
            version = "3-1B-IT-int4-litertlm",
            description = "LiteRT-LM CPU로 실행하는 경량 로컬 AI입니다. 공식 모델 파일을 가져오세요.",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
                "resolve/main/gemma3-1b-it-int4.litertlm?download=true",
            // The official repository is gated. Do not add an access token or silently use the
            // network; the user imports the downloaded official artifact with the existing picker.
            remoteDownloadEnabled = false,
            expectedSha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
            exactArtifactBytes = 584_417_280L,
            approximateDownloadBytes = 584_417_280L,
            approximateInstallBytes = 585_000_000L,
            requiredFiles = setOf("model.litertlm"),
            runtimeType = ModelRuntimeType.LITERT_LM,
        ),
        ModelDescriptor(
            id = ModelId.SENSEVOICE_STT_KO,
            displayName = "SenseVoice 한국어 파일 STT",
            version = "int8-2024-07-17",
            description = "완료 녹음 파일을 기기 안에서 전사하는 한국어 STT 모델입니다.",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            expectedSha256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
            exactArtifactBytes = 163_002_883L,
            approximateDownloadBytes = 163_002_883L,
            approximateInstallBytes = 230_000_000L,
            requiredFiles = setOf("model.int8.onnx", "tokens.txt"),
            runtimeType = ModelRuntimeType.SHERPA_ONNX,
            artifactFormat = ModelArtifactFormat.TAR_BZ2,
            archiveRoot = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        ),
    )

    /** Every optional model is explicitly installed and managed from the Local AI tab. */
    val userManagedModels: List<ModelDescriptor> = models

    fun get(id: ModelId): ModelDescriptor =
        requireNotNull(models.firstOrNull { it.id == id }) { "알 수 없는 모델: $id" }
}
