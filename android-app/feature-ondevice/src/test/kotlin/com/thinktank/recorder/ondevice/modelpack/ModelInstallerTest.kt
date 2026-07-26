package com.thinktank.recorder.ondevice.modelpack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelInstallerTest {
    private lateinit var context: Context
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ModelStore(context)
        store.delete(ModelId.QWEN_SUMMARY_KO)
        store.delete(ModelId.SENSEVOICE_STT_KO)
    }

    @After
    fun tearDown() {
        store.delete(ModelId.QWEN_SUMMARY_KO)
        store.delete(ModelId.SENSEVOICE_STT_KO)
    }

    @Test
    fun qwenArtifactInstallsAsSingleVerifiedGguf() {
        val descriptor = testQwenDescriptor()
        val artifact = File(context.cacheDir, "qwen-test.gguf").apply { writeText("qwen-model") }

        ModelInstaller(store) { 123L }.install(descriptor, artifact)

        val installed = store.snapshot(descriptor)
        assertTrue(installed.ready)
        assertEquals("qwen-model", File(store.installDir(descriptor.id), "model.gguf").readText())
        assertFalse(artifact.exists())
    }

    @Test
    fun installedFileTamperingIsRejectedBeforeInference() {
        val descriptor = testQwenDescriptor()
        val artifact = File(context.cacheDir, "qwen-integrity.gguf").apply { writeText("qwen-model") }
        ModelInstaller(store) { 456L }.install(descriptor, artifact)
        File(store.installDir(descriptor.id), "model.gguf").appendText("tampered")

        assertFalse(store.snapshot(descriptor).ready)
        assertThrows(IllegalStateException::class.java) {
            ModelIntegrityVerifier(store).requireValid(descriptor)
        }
    }

    @Test
    fun interruptedActivationRestoresQwenBackup() {
        val descriptor = testQwenDescriptor()
        val artifact = File(context.cacheDir, "qwen-recovery.gguf").apply { writeText("qwen-model") }
        ModelInstaller(store).install(descriptor, artifact)

        val target = store.installDir(descriptor.id)
        val backup = store.backupDir(descriptor.id)
        assertTrue(target.renameTo(backup))
        assertFalse(target.exists())

        store.recoverInterruptedInstall(descriptor.id)

        assertTrue(target.isDirectory)
        assertFalse(backup.exists())
        assertEquals("qwen-model", File(target, "model.gguf").readText())
        assertTrue(store.snapshot(descriptor).ready)
    }

    @Test
    fun senseVoiceArchiveInstallsOnlyRequiredFiles() {
        val descriptor = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO).copy(
            version = "test",
            expectedSha256 = "b".repeat(64),
            approximateInstallBytes = 1024,
        )
        val artifact = File(context.cacheDir, "sensevoice-test.tar.bz2")
        writeTarBz2(
            artifact,
            mapOf(
                "${descriptor.archiveRoot}/model.int8.onnx" to "onnx".toByteArray(),
                "${descriptor.archiveRoot}/tokens.txt" to "tokens".toByteArray(),
                "${descriptor.archiveRoot}/README.md" to "ignored".toByteArray(),
            ),
        )

        ModelInstaller(store).install(descriptor, artifact)

        val directory = store.installDir(descriptor.id)
        assertTrue(store.snapshot(descriptor).ready)
        assertEquals("onnx", File(directory, "model.int8.onnx").readText())
        assertEquals("tokens", File(directory, "tokens.txt").readText())
        assertFalse(File(directory, "README.md").exists())
        assertFalse(artifact.exists())
    }

    @Test
    fun senseVoiceArchiveRejectsTraversalAndMissingRequiredFiles() {
        val descriptor = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO).copy(
            version = "test",
            expectedSha256 = "c".repeat(64),
            approximateInstallBytes = 1024,
        )
        val traversal = File(context.cacheDir, "sensevoice-traversal.tar.bz2")
        writeTarBz2(
            traversal,
            mapOf(
                "${descriptor.archiveRoot}/../outside.txt" to "no".toByteArray(),
                "${descriptor.archiveRoot}/model.int8.onnx" to "onnx".toByteArray(),
                "${descriptor.archiveRoot}/tokens.txt" to "tokens".toByteArray(),
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            ModelInstaller(store).install(descriptor, traversal)
        }
        assertFalse(store.installDir(descriptor.id).exists())

        val missing = File(context.cacheDir, "sensevoice-missing.tar.bz2")
        writeTarBz2(
            missing,
            mapOf("${descriptor.archiveRoot}/model.int8.onnx" to "onnx".toByteArray()),
        )
        assertThrows(IllegalStateException::class.java) {
            ModelInstaller(store).install(descriptor, missing)
        }
        assertFalse(store.installDir(descriptor.id).exists())
    }

    @Test
    fun cancelledSenseVoiceInstallLeavesNoStagingDirectory() {
        val descriptor = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO).copy(
            version = "test",
            expectedSha256 = "d".repeat(64),
            approximateInstallBytes = 1024,
        )
        val artifact = File(context.cacheDir, "sensevoice-cancel.tar.bz2")
        writeTarBz2(
            artifact,
            mapOf(
                "${descriptor.archiveRoot}/model.int8.onnx" to "onnx".toByteArray(),
                "${descriptor.archiveRoot}/tokens.txt" to "tokens".toByteArray(),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            ModelInstaller(store).install(descriptor, artifact) { error("cancelled") }
        }
        assertFalse(store.stagingDir(descriptor.id).exists())
        assertFalse(store.installDir(descriptor.id).exists())
    }

    private fun writeTarBz2(target: File, entries: Map<String, ByteArray>) {
        FileOutputStream(target).use { file ->
            BZip2CompressorOutputStream(file).use { bzip2 ->
                TarArchiveOutputStream(bzip2).use { tar ->
                    entries.forEach { (name, bytes) ->
                        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
    }

    private fun testQwenDescriptor(): ModelDescriptor =
        ModelCatalog.get(ModelId.QWEN_SUMMARY_KO).copy(
            version = "test",
            expectedSha256 = "a".repeat(64),
        )
}
