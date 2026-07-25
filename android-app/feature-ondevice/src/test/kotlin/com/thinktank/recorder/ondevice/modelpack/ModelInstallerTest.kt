package com.thinktank.recorder.ondevice.modelpack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelInstallerTest {
    private lateinit var context: Context
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ModelStore(context)
        store.delete(ModelId.MOONSHINE_KO)
        store.delete(ModelId.QWEN_SUMMARY_KO)
    }

    @After
    fun tearDown() {
        store.delete(ModelId.MOONSHINE_KO)
        store.delete(ModelId.QWEN_SUMMARY_KO)
    }

    @Test
    fun moonshineArchiveInstallsOnlyRequiredFiles() {
        val descriptor = testMoonshineDescriptor()
        val archive = File(context.cacheDir, "moonshine-test.tar.bz2")
        writeArchive(
            archive,
            mapOf(
                "root/encoder_model.ort" to "encoder",
                "root/decoder_model_merged.ort" to "decoder",
                "root/tokens.txt" to "tokens",
                "root/LICENSE" to "license",
                "root/test_wavs/example.wav" to "not-installed",
            ),
        )

        ModelInstaller(store) { 123L }.install(descriptor, archive)

        val installed = store.snapshot(descriptor)
        assertTrue(installed.ready)
        assertFalse(File(store.installDir(descriptor.id), "example.wav").exists())
        assertFalse(archive.exists())
    }

    @Test(expected = IllegalStateException::class)
    fun unsafeArchivePathIsRejected() {
        val descriptor = testMoonshineDescriptor()
        val archive = File(context.cacheDir, "moonshine-unsafe.tar.bz2")
        writeArchive(archive, mapOf("../encoder_model.ort" to "unsafe"))

        ModelInstaller(store).install(descriptor, archive)
    }

    @Test
    fun installedFileTamperingIsRejectedBeforeNativeLoad() {
        val descriptor = testMoonshineDescriptor()
        val archive = File(context.cacheDir, "moonshine-integrity.tar.bz2")
        writeArchive(
            archive,
            mapOf(
                "encoder_model.ort" to "encoder",
                "decoder_model_merged.ort" to "decoder",
                "tokens.txt" to "tokens",
                "LICENSE" to "license",
            ),
        )
        ModelInstaller(store) { 456L }.install(descriptor, archive)
        File(store.installDir(descriptor.id), "encoder_model.ort").appendText("tampered")

        assertThrows(IllegalStateException::class.java) {
            ModelIntegrityVerifier(store).requireValid(descriptor)
        }
    }

    private fun testMoonshineDescriptor(): ModelDescriptor =
        ModelCatalog.get(ModelId.MOONSHINE_KO).copy(
            version = "test",
            expectedSha256 = "a".repeat(64),
        )

    private fun writeArchive(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(
            BZip2CompressorOutputStream(FileOutputStream(file)),
        ).use { tar ->
            entries.forEach { (name, text) ->
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }
}
