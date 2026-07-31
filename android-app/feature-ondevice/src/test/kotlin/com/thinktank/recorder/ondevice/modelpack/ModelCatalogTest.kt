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
    fun onlyGemmaDefaultSummaryAndSenseVoiceSttAreAvailable() {
        val gemma = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
        val senseVoice = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)

        assertEquals(
            listOf(ModelId.GEMMA_SUMMARY_KO, ModelId.SENSEVOICE_STT_KO),
            ModelCatalog.models.map { it.id },
        )
        assertEquals(ModelRuntimeType.LITERT_LM, gemma.runtimeType)
        assertFalse(gemma.remoteDownloadEnabled)
        assertEquals(setOf("model.litertlm"), gemma.requiredFiles)
        assertEquals(ModelRuntimeType.SHERPA_ONNX, senseVoice.runtimeType)
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
