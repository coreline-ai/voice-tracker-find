package com.thinktank.recorder.ondevice.modelpack

import java.io.File

internal class ArtifactValidationException(message: String) : IllegalStateException(message)

/**
 * A full artifact can be installed locally after an app update interrupted the extraction phase.
 * Size alone is deliberately not treated as "installed"; the worker still verifies SHA-256 before
 * activating it. It is only a safe, cheap gate for scheduling that local recovery work.
 */
internal fun hasCompleteArtifactFile(artifact: File, expectedBytes: Long): Boolean =
    artifact.isFile && artifact.length() == expectedBytes

internal class ExactArtifactSizeGuard(
    private val expectedBytes: Long,
    initialBytes: Long,
) {
    var copiedBytes: Long = initialBytes
        private set

    init {
        if (initialBytes !in 0..expectedBytes) {
            throw ArtifactValidationException("이어받기 파일 크기가 고정 크기를 초과했습니다")
        }
    }

    fun accept(count: Int): Long {
        require(count >= 0)
        if (copiedBytes + count > expectedBytes) {
            throw ArtifactValidationException("모델 파일이 고정 크기를 초과했습니다")
        }
        copiedBytes += count
        return copiedBytes
    }

    fun verifyEof() {
        if (copiedBytes != expectedBytes) {
            throw ArtifactValidationException(
                "모델 파일 크기가 올바르지 않습니다: $copiedBytes/$expectedBytes",
            )
        }
    }
}

internal enum class Range416Decision {
    ACCEPT_COMPLETE,
    RETRY_FULL,
    FAIL,
}

internal fun range416Decision(
    partialBytes: Long,
    exactBytes: Long,
    shaMatches: Boolean,
    fullRetryUsed: Boolean,
): Range416Decision = when {
    partialBytes == exactBytes && shaMatches -> Range416Decision.ACCEPT_COMPLETE
    fullRetryUsed -> Range416Decision.FAIL
    else -> Range416Decision.RETRY_FULL
}
