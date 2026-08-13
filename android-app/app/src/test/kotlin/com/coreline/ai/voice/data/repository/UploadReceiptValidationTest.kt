package com.coreline.ai.voice.data.repository

import com.coreline.ai.voice.data.local.ChunkEntity
import com.coreline.ai.voice.data.local.ChunkState
import com.coreline.ai.voice.data.remote.UploadReceipt
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadReceiptValidationTest {
    @Test
    fun `accepts only a receipt that identifies the uploaded local file`() {
        val file = File.createTempFile("receipt-validation", ".m4a").apply {
            writeBytes("audio".toByteArray())
            deleteOnExit()
        }
        val chunk = ChunkEntity(
            id = "22222222-2222-4222-8222-222222222222",
            sessionId = "11111111-1111-4111-8111-111111111111",
            uploadId = "33333333-3333-4333-8333-333333333333",
            path = file.absolutePath,
            state = ChunkState.UPLOADING,
            createdAt = 1,
            sha256 = "a".repeat(64),
        )
        val receipt = UploadReceipt(
            uploadId = chunk.uploadId,
            recordingId = chunk.sessionId,
            chunkId = chunk.id,
            filename = file.name,
            size = file.length(),
            sha256 = requireNotNull(chunk.sha256),
            status = "created",
            requestId = "request-1",
        )

        assertTrue(receipt.matches(chunk, file))
        assertFalse(receipt.copy(uploadId = "another-upload").matches(chunk, file))
        assertFalse(receipt.copy(filename = "other.m4a").matches(chunk, file))
        assertFalse(receipt.copy(size = file.length() + 1).matches(chunk, file))
        assertFalse(receipt.copy(status = "stored").matches(chunk, file))
    }
}
