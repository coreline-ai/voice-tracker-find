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
    fun pinnedArtifactsMatchApprovedModels() {
        val moonshine = ModelCatalog.get(ModelId.MOONSHINE_KO)
        val qwen = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)

        assertEquals(ModelPackaging.TAR_BZ2, moonshine.packaging)
        assertEquals(49_153_415L, moonshine.exactArtifactBytes)
        assertEquals(
            "d3b6c5390a7859c9ef20ff4f20b0766fcbad1dc06c0f509fe4840a3a302112dc",
            moonshine.expectedSha256,
        )
        assertEquals(ModelPackaging.SINGLE_GGUF, qwen.packaging)
        assertEquals(563_036_064L, qwen.exactArtifactBytes)
        assertEquals(
            "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
            qwen.expectedSha256,
        )
    }

    @Test
    fun officialRedirectHostsAreAllowedButArbitraryHostsAreRejected() {
        assertTrue(ModelDownloadWorker.isAllowedModelHost("release-assets.githubusercontent.com"))
        assertTrue(ModelDownloadWorker.isAllowedModelHost("us.aws.cdn.hf.co"))
        assertFalse(ModelDownloadWorker.isAllowedModelHost("example.com"))
        assertFalse(ModelDownloadWorker.isAllowedModelHost("cdn.hf.co.attacker.example"))
    }
}
