package com.thinktank.recorder.ondevice.modelpack

enum class ModelId {
    MOONSHINE_KO,
    QWEN_SUMMARY_KO,
}

enum class ModelPackaging {
    TAR_BZ2,
    SINGLE_GGUF,
}

data class ModelDescriptor(
    val id: ModelId,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val exactArtifactBytes: Long,
    val approximateDownloadBytes: Long,
    val approximateInstallBytes: Long,
    val packaging: ModelPackaging,
    val requiredFiles: Set<String>,
)

object ModelCatalog {
    val models: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = ModelId.MOONSHINE_KO,
            displayName = "Moonshine 한국어 STT",
            version = "2026.02.27",
            description = "한국어 음성을 기기에서 텍스트로 변환합니다.",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "asr-models/sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27.tar.bz2",
            expectedSha256 = "d3b6c5390a7859c9ef20ff4f20b0766fcbad1dc06c0f509fe4840a3a302112dc",
            exactArtifactBytes = 49_153_415L,
            approximateDownloadBytes = 49_153_415L,
            approximateInstallBytes = 70_000_000L,
            packaging = ModelPackaging.TAR_BZ2,
            requiredFiles = setOf(
                "encoder_model.ort",
                "decoder_model_merged.ort",
                "tokens.txt",
                "LICENSE",
            ),
        ),
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
            packaging = ModelPackaging.SINGLE_GGUF,
            requiredFiles = setOf("model.gguf"),
        ),
    )

    fun get(id: ModelId): ModelDescriptor =
        requireNotNull(models.firstOrNull { it.id == id }) { "알 수 없는 모델: $id" }
}
