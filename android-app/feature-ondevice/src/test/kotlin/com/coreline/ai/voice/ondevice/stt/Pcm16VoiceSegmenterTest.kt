package com.coreline.ai.voice.ondevice.stt

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Pcm16VoiceSegmenterTest {
    private lateinit var pcm: File

    @Before
    fun setUp() {
        pcm = File.createTempFile("sensevoice-vad", ".pcm")
    }

    @After
    fun tearDown() {
        pcm.delete()
    }

    @Test
    fun emitsSpeechBoundedBySilenceWithPreroll() = runBlocking {
        writeFrames(
            silenceFrames = 10,
            voicedFrames = 14,
            trailingSilenceFrames = 40,
            amplitude = 12_000,
        )
        val segments = mutableListOf<Pcm16VoiceSegmenter.AudioSegment>()

        Pcm16VoiceSegmenter().forEachSpeechSegment(pcm) { segments += it }

        assertEquals(1, segments.size)
        assertTrue("pre-roll should include the 200 ms before voiced audio", segments.single().startMs <= 200L)
        assertTrue(segments.single().endMs >= 600L)
        assertTrue(segments.single().samples.isNotEmpty())
    }

    @Test
    fun keepsVeryQuietAudioForRecognizerFallback() = runBlocking {
        writeFrames(
            silenceFrames = 0,
            voicedFrames = 12,
            trailingSilenceFrames = 0,
            amplitude = 20,
        )
        val segments = mutableListOf<Pcm16VoiceSegmenter.AudioSegment>()

        Pcm16VoiceSegmenter().forEachSpeechSegment(pcm) { segments += it }

        assertEquals(1, segments.size)
        assertEquals(240L, segments.single().endMs)
    }

    @Test
    fun forcedBoundaryKeepsContextOverlapAndAddsTrailingDecoderSilence() = runBlocking {
        writeFrames(
            silenceFrames = 0,
            voicedFrames = 80,
            trailingSilenceFrames = 0,
            amplitude = 12_000,
        )
        val segments = mutableListOf<Pcm16VoiceSegmenter.AudioSegment>()

        Pcm16VoiceSegmenter(
            maxSegmentMs = 1_000L,
            forcedOverlapMs = 200L,
            trailingPaddingMs = 100L,
        ).forEachSpeechSegment(pcm) { segments += it }

        assertEquals(2, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(1_000L, segments[0].endMs)
        assertEquals(800L, segments[1].startMs)
        assertEquals(1_600L, segments[1].endMs)
        assertEquals(200L, segments[0].endMs - segments[1].startMs)
        val paddingSamples = 16_000 / 10
        assertTrue(
            "decoder padding must be silence",
            segments.last().samples.takeLast(paddingSamples).all { it == 0f },
        )
    }

    @Test
    fun fixedRangeReadsOnlyRequestedFailureWindow() = runBlocking {
        writeFrames(
            silenceFrames = 0,
            voicedFrames = 200,
            trailingSilenceFrames = 0,
            amplitude = 12_000,
        )
        val segments = mutableListOf<Pcm16VoiceSegmenter.AudioSegment>()

        Pcm16VoiceSegmenter(maxSegmentMs = 1_000L).forEachFixedRange(
            pcmFile = pcm,
            ranges = listOf(SttRetryRange(1_000L, 2_000L)),
            cancellationCheck = {},
        ) { segments += it }

        assertEquals(1, segments.size)
        assertEquals(1_000L, segments.single().startMs)
        assertEquals(2_000L, segments.single().endMs)
    }

    private fun writeFrames(
        silenceFrames: Int,
        voicedFrames: Int,
        trailingSilenceFrames: Int,
        amplitude: Int,
    ) {
        FileOutputStream(pcm).use { output ->
            repeat(silenceFrames) { output.write(frame(0)) }
            repeat(voicedFrames) { output.write(frame(amplitude)) }
            repeat(trailingSilenceFrames) { output.write(frame(0)) }
        }
    }

    private fun frame(amplitude: Int): ByteArray = ByteArray(320 * 2).also { bytes ->
        var index = 0
        repeat(320) {
            bytes[index++] = (amplitude and 0xff).toByte()
            bytes[index++] = ((amplitude ushr 8) and 0xff).toByte()
        }
    }
}
