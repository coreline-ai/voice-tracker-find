package com.thinktank.recorder.next.recording

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class RecordingFileManagerTest {
    private lateinit var manager: RecordingFileManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = RecordingFileManager(context)
        manager.directory.deleteRecursively()
        manager.directory.mkdirs()
    }

    @After
    fun tearDown() {
        manager.directory.deleteRecursively()
    }

    @Test
    fun retryVerificationRejectsMissingPartAndTamperedFiles() = runBlocking {
        val finalized = File(manager.directory, "verified.m4a").apply { writeText("audio") }
        val part = File(manager.directory, "unfinished.m4a.part").apply { writeText("audio") }
        val digest = digest(finalized)

        assertTrue(manager.isVerifiedFinalizedFile(finalized.path, digest))
        assertFalse(manager.isVerifiedFinalizedFile("${manager.directory}/missing.m4a", digest))
        assertFalse(manager.isVerifiedFinalizedFile(finalized.path, null))
        assertFalse(manager.isVerifiedFinalizedFile(part.path, digest))

        finalized.writeText("tampered")
        assertFalse(manager.isVerifiedFinalizedFile(finalized.path, digest))
    }

    @Test
    fun deletionOnlyAcceptsAppManagedFiles() = runBlocking {
        val managed = File(manager.directory, "completed.m4a").apply { writeText("audio") }
        val outside = File(manager.directory.parentFile, "outside-recording-test.m4a")
            .apply { writeText("audio") }
        try {
            assertTrue(manager.deleteManagedFinalizedFile(managed.path))
            assertFalse(managed.exists())
            assertFalse(manager.deleteManagedFinalizedFile(outside.path))
            assertTrue(outside.exists())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun verifiedCopyKeepsSourceAndRejectsTamperedOriginal() = runBlocking {
        val source = File(manager.directory, "source.m4a").apply { writeText("original audio") }
        val destination = File(manager.directory.parentFile, "ondevice-copy-test/source.snapshot")
        destination.parentFile?.deleteRecursively()
        try {
            val receipt = digest(source)
            assertTrue(manager.copyVerifiedFinalizedFile(source.path, receipt, destination))
            assertTrue(source.exists())
            assertTrue(destination.readText() == "original audio")

            source.writeText("changed")
            assertFalse(manager.copyVerifiedFinalizedFile(source.path, receipt, destination))
        } finally {
            destination.parentFile?.deleteRecursively()
        }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
