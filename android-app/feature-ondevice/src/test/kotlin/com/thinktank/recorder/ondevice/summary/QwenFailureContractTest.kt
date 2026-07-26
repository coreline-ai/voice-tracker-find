package com.thinktank.recorder.ondevice.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class QwenFailureContractTest {
    @Test
    fun remoteOutputRejectionStaysQualityFailureAcrossBinder() {
        assertEquals(
            QWEN_QUALITY_REJECTED,
            classifyQwenFailure(
                IllegalStateException("$QWEN_QUALITY_REJECTED:INVALID_JSON_OR_SCHEMA"),
            ),
        )
    }

    @Test
    fun workerAndResourceErrorsStayRuntimeFailures() {
        assertEquals(
            QWEN_RUNTIME_FAILED,
            classifyQwenFailure(IllegalStateException("Qwen 서비스 Binder가 없습니다")),
        )
    }
}
