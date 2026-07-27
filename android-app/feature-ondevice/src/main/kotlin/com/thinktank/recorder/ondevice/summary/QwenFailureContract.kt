package com.thinktank.recorder.ondevice.summary

internal const val QWEN_QUALITY_REJECTED = "QWEN_QUALITY_REJECTED"
internal const val QWEN_RUNTIME_FAILED = "QWEN_RUNTIME_FAILED"
internal const val LOCAL_LLM_RUNTIME_FAILED = "LOCAL_LLM_RUNTIME_FAILED"

internal fun classifyQwenFailure(error: Throwable): String {
    val message = error.message.orEmpty()
    return if (
        error is SummaryQualityException ||
        message.contains(QWEN_QUALITY_REJECTED) ||
        message.contains("요약 품질 검사 실패")
    ) {
        message.substringAfterLast("IllegalStateException: ", message)
            .takeIf { it.contains(QWEN_QUALITY_REJECTED) }
            ?: "$QWEN_QUALITY_REJECTED:${(error as? SummaryQualityException)?.validation?.correctionHint().orEmpty()}"
    } else {
        if (message.contains(LOCAL_LLM_RUNTIME_FAILED)) LOCAL_LLM_RUNTIME_FAILED
        else QWEN_RUNTIME_FAILED
    }
}
