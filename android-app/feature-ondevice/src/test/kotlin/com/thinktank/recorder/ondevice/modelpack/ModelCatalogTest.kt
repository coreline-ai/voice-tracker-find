package com.thinktank.recorder.ondevice.modelpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun everyDownloadIsHttpsAndHashPinned() {
        ModelCatalog.models.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://"))
            assertTrue(model.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.approximateDownloadBytes > 0)
            assertEquals(model.exactArtifactBytes, model.approximateDownloadBytes)
            assertTrue(model.requiredFiles.isNotEmpty())
        }
    }

    @Test
    fun pinnedQwenAndSenseVoiceArtifactsAreAvailable() {
        val qwen = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
        val senseVoice = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)

        assertEquals(
            listOf(ModelId.QWEN_SUMMARY_KO, ModelId.SENSEVOICE_STT_KO),
            ModelCatalog.models.map { it.id },
        )
        assertEquals(563_036_064L, qwen.exactArtifactBytes)
        assertEquals(
            "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
            qwen.expectedSha256,
        )
        assertEquals(ModelArtifactFormat.TAR_BZ2, senseVoice.artifactFormat)
        assertEquals(163_002_883L, senseVoice.exactArtifactBytes)
        assertEquals(
            setOf("model.int8.onnx", "tokens.txt"),
            senseVoice.requiredFiles,
        )
        assertEquals(ModelCatalog.models, ModelCatalog.userManagedModels)
    }

    @Test
    fun officialRedirectHostsAreAllowedButArbitraryHostsAreRejected() {
        assertTrue(ModelDownloadWorker.isAllowedModelHost("release-assets.githubusercontent.com"))
        assertTrue(ModelDownloadWorker.isAllowedModelHost("us.aws.cdn.hf.co"))
        assertFalse(ModelDownloadWorker.isAllowedModelHost("example.com"))
        assertFalse(ModelDownloadWorker.isAllowedModelHost("cdn.hf.co.attacker.example"))
    }
}
