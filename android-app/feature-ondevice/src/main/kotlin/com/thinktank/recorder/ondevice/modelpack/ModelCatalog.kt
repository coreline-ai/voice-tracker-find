package com.thinktank.recorder.ondevice.modelpack

enum class ModelId {
    GEMMA_SUMMARY_KO,
    SENSEVOICE_STT_KO,
}

enum class ModelRuntimeType {
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
            id = ModelId.GEMMA_SUMMARY_KO,
            displayName = "Gemma 3 1B 기본 요약",
            version = "3-1B-IT-int4-litertlm",
            description = "전사 원문을 기기 안에서 자동 요약하는 기본 로컬 AI입니다.",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/" +
                "resolve/main/gemma3-1b-it-int4.litertlm?download=true",
            // The official repository is gated. The user imports the accepted official artifact.
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

    /** Gemma 3 1B is the fixed summary model; SenseVoice remains the file STT model. */
    val userManagedModels: List<ModelDescriptor> = models

    fun get(id: ModelId): ModelDescriptor =
        requireNotNull(models.firstOrNull { it.id == id }) { "알 수 없는 모델: $id" }
}
