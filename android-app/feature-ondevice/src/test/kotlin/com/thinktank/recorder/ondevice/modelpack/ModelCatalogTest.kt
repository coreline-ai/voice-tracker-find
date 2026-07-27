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
    fun pinnedSummaryAndSenseVoiceArtifactsAreAvailable() {
        val qwen = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
        val exaone = ModelCatalog.get(ModelId.EXAONE_SUMMARY_KO)
        val gemma = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
        val senseVoice = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)

        assertEquals(
            listOf(
                ModelId.QWEN_SUMMARY_KO,
                ModelId.EXAONE_SUMMARY_KO,
                ModelId.GEMMA_SUMMARY_KO,
                ModelId.SENSEVOICE_STT_KO,
            ),
            ModelCatalog.models.map { it.id },
        )
        assertEquals(563_036_064L, qwen.exactArtifactBytes)
        assertEquals(
            "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
            qwen.expectedSha256,
        )
        assertEquals(ModelRuntimeType.LLAMA_CPP, exaone.runtimeType)
        assertEquals(812_437_792L, exaone.exactArtifactBytes)
        assertEquals(
            "7b5e753540183ae4d56e6febd9b48cdd944de53386e6faa8f51c8f98cb2b47df",
            exaone.expectedSha256,
        )
        assertEquals(ModelRuntimeType.LITERT_LM, gemma.runtimeType)
        assertEquals(584_417_280L, gemma.exactArtifactBytes)
        assertFalse(gemma.remoteDownloadEnabled)
        assertEquals(setOf("model.litertlm"), gemma.requiredFiles)
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
