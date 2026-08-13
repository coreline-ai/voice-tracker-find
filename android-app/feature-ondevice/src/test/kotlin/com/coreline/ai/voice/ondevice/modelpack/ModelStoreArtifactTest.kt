package com.coreline.ai.voice.ondevice.modelpack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelStoreArtifactTest {
    private lateinit var store: ModelStore
    private lateinit var descriptor: ModelDescriptor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = ModelStore(context)
        descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO).copy(
            version = "artifact-test",
            expectedSha256 = "f".repeat(64),
            exactArtifactBytes = 11,
            approximateDownloadBytes = 11,
            approximateInstallBytes = 64,
            requiredFiles = setOf("model.bin"),
            artifactFormat = ModelArtifactFormat.SINGLE_FILE,
            archiveRoot = "",
        )
        store.delete(descriptor.id)
        store.deleteLegacyStorage()
    }

    @After
    fun tearDown() {
        store.delete(descriptor.id)
        store.deleteLegacyStorage()
    }

    @Test
    fun verifiedPartialIsPromotedAndSurvivesInstalledCopyDeletion() {
        store.partialFile(descriptor.id).apply {
            parentFile?.mkdirs()
            writeText("local-model")
        }

        val artifact = store.promoteVerifiedPartial(descriptor)
        ModelInstaller(store).install(descriptor, artifact)
        store.deleteInstalled(descriptor.id)

        assertFalse(store.snapshot(descriptor).ready)
        assertTrue(artifact.isFile)
        assertEquals("local-model", artifact.readText())

        ModelInstaller(store).install(descriptor, artifact)

        assertTrue(store.snapshot(descriptor).ready)
        assertTrue(artifact.isFile)
    }

    @Test
    fun completeDeleteRemovesInstalledAndRetainedArtifact() {
        store.partialFile(descriptor.id).apply {
            parentFile?.mkdirs()
            writeText("local-model")
        }
        val artifact = store.promoteVerifiedPartial(descriptor)
        ModelInstaller(store).install(descriptor, artifact)

        store.delete(descriptor.id)

        assertFalse(store.installDir(descriptor.id).exists())
        assertFalse(artifact.exists())
    }

    @Test
    fun legacyCleanupTargetsOnlyFixedQwenAndExaonePaths() {
        val root = store.modelRoot()
        val qwenInstalled = java.io.File(root, "qwen_summary_ko/model.gguf").apply {
            parentFile?.mkdirs()
            writeText("qwen-old")
        }
        val exaoneArtifact = java.io.File(
            root,
            ".artifacts/exaone_summary_ko/exaone-old.gguf",
        ).apply {
            parentFile?.mkdirs()
            writeText("exaone-old")
        }
        val qwenPartial = java.io.File(root, ".downloads/qwen_summary_ko.part").apply {
            parentFile?.mkdirs()
            writeText("partial")
        }
        val currentGemma = java.io.File(root, "gemma_summary_ko/current.keep").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }

        val before = store.legacyStorage()
        assertEquals(3, before.entryCount)
        assertEquals(qwenInstalled.length() + exaoneArtifact.length() + qwenPartial.length(), before.bytes)

        assertEquals(before.bytes, store.deleteLegacyStorage())
        assertFalse(qwenInstalled.exists())
        assertFalse(exaoneArtifact.exists())
        assertFalse(qwenPartial.exists())
        assertTrue(currentGemma.isFile)
        assertEquals(LegacyModelStorage(), store.legacyStorage())
        assertEquals(0L, store.deleteLegacyStorage())

        currentGemma.delete()
    }
}
