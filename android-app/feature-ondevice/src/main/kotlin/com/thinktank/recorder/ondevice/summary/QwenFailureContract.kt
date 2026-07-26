package com.thinktank.recorder.ondevice.summary

internal const val QWEN_QUALITY_REJECTED = "QWEN_QUALITY_REJECTED"
internal const val QWEN_RUNTIME_FAILED = "QWEN_RUNTIME_FAILED"

internal fun classifyQwenFailure(error: Throwable): String {
    val message = error.message.orEmpty()
    return if (
        error is SummaryQualityException ||
        message.contains(QWEN_QUALITY_REJECTED) ||
        message.contains("요약 품질 검사 실패")
    ) {
        QWEN_QUALITY_REJECTED
    } else {
        QWEN_RUNTIME_FAILED
    }
}
