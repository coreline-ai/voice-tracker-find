package com.coreline.ai.voice.ondevice

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coreline.ai.voice.ondevice.api.LocalSummary
import com.coreline.ai.voice.ondevice.audio.AndroidPcmNormalizer
import com.coreline.ai.voice.ondevice.modelpack.ModelCatalog
import com.coreline.ai.voice.ondevice.modelpack.ModelId
import com.coreline.ai.voice.ondevice.modelpack.ModelStore
import com.coreline.ai.voice.ondevice.stt.Pcm16WavReader
import com.coreline.ai.voice.ondevice.stt.SenseVoiceFileSpeechEngine
import com.coreline.ai.voice.ondevice.summary.GemmaSummaryEngine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit Samsung-only QA for five synthetic Korean recordings.
 *
 * Fixtures are generated on the development Mac with an offline Korean TTS voice, normalized to
 * mono 16 kHz PCM WAV, and copied into [INPUT_DIRECTORY] after the disposable debuggable package
 * is installed. No user recording is read and no network client is involved in STT or summary.
 */
@RunWith(AndroidJUnit4::class)
class FiveTtsLocalAiPipelineDeviceTest {
    @Test
    fun fiveOneToThreeMinuteTtsRecordingsRunSenseVoiceAndGemma() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "명시적 5개 TTS 풀 파이프라인 QA에서만 실행합니다.",
            arguments.getString(ARG_RUN_FIVE_TTS_PIPELINE) == "true",
        )
        assertTrue(
            "승인된 Samsung SM-S931N에서만 실행합니다.",
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) && Build.MODEL == "SM-S931N",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ModelStore(context)
        assertTrue(
            "SenseVoice 모델이 준비되지 않았습니다.",
            store.snapshot(ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)).ready,
        )
        assertTrue(
            "Gemma 모델이 준비되지 않았습니다.",
            store.snapshot(ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)).ready,
        )

        val inputDir = File(context.cacheDir, INPUT_DIRECTORY)
        val resultDir = File(context.filesDir, RESULT_DIRECTORY).apply { mkdirs() }
        val resultFile = File(resultDir, RESULT_FILE)
        val normalizer = AndroidPcmNormalizer()
        val speech = SenseVoiceFileSpeechEngine(context, store)
        val rows = mutableListOf<PipelineRow>()
        try {
            FIXTURES.forEach { fixture ->
                val source = File(inputDir, fixture.fileName)
                assertTrue("TTS fixture가 없습니다: ${fixture.fileName}", source.isFile)
                val durationMs = Pcm16WavReader.inspect(source).durationMs
                assertTrue(
                    "${fixture.fileName} 길이가 1~3분 범위를 벗어났습니다: $durationMs ms",
                    durationMs in MIN_DURATION_MS..MAX_DURATION_MS,
                )
                val pcm = File(context.cacheDir, "${fixture.id}.pcm")
                pcm.delete()
                try {
                    val normalized = normalizer.normalize(source, pcm)
                    assertEquals(durationMs, normalized.durationMs)
                    val sttStartedAt = SystemClock.elapsedRealtime()
                    val stt = withTimeout(STT_TIMEOUT_MS) { speech.transcribe(pcm) }
                    val sttElapsedMs = SystemClock.elapsedRealtime() - sttStartedAt
                    val compactTranscript = stt.text.replace(Regex("[^가-힣A-Za-z0-9]"), "")
                    val keywordHits = fixture.keywords.filter(compactTranscript::contains)
                    assertTrue("${fixture.fileName} STT 결과가 비어 있습니다.", compactTranscript.isNotBlank())
                    assertTrue(
                        "${fixture.fileName} STT에서 주제 근거를 찾지 못했습니다: ${fixture.keywords}",
                        keywordHits.isNotEmpty(),
                    )
                    assertEquals(durationMs, stt.diagnostics?.inputDurationMs)
                    assertEquals(durationMs, stt.diagnostics?.processedThroughMs)
                    rows += PipelineRow(
                        fixture = fixture,
                        source = source,
                        durationMs = durationMs,
                        transcript = stt.text,
                        transcriptHash = sha256(stt.text),
                        sttElapsedMs = sttElapsedMs,
                        sttKeywordHits = keywordHits,
                        sttQualityStatus = stt.diagnostics?.qualityStatus?.name.orEmpty(),
                        sttSegmentCount = stt.diagnostics?.segmentCount ?: 0,
                    )
                    writeEvidence(resultFile, rows)
                } finally {
                    pcm.delete()
                }
            }
        } finally {
            speech.release()
        }

        val summaryEngine = GemmaSummaryEngine(context, store)
        withTimeout(SUMMARY_BATCH_TIMEOUT_MS) {
            summaryEngine.withBatch(expectedNodeCount = rows.size) { summarize ->
                rows.forEach { row ->
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = runCatching { summarize(row.transcript) }
                    row.summaryElapsedMs = SystemClock.elapsedRealtime() - startedAt
                    row.summary = result.getOrNull()
                    row.summaryError = result.exceptionOrNull()?.message
                    writeEvidence(resultFile, rows)
                }
            }
        }

        rows.forEach { row ->
            val summary = row.summary
            if (summary == null) {
                row.summaryQualityPassed = false
                return@forEach
            }
            val summaryText = summary.bullets.joinToString(" ").trim()
            val compactSummary = (summary.title + summaryText)
                .replace(Regex("[^가-힣A-Za-z0-9]"), "")
            row.summaryKeywordHits = row.fixture.keywords.filter(compactSummary::contains)
            row.summaryQualityPassed =
                summary.validationStatus == EXPECTED_VALIDATION_STATUS &&
                summary.title.isNotBlank() &&
                summaryText.isNotBlank() &&
                summaryText.length < row.transcript.length &&
                row.summaryKeywordHits.isNotEmpty()
            assertEquals(EXPECTED_VALIDATION_STATUS, summary.validationStatus)
            assertTrue("${row.fixture.fileName} 요약 제목이 비어 있습니다.", summary.title.isNotBlank())
            assertTrue("${row.fixture.fileName} 요약이 비어 있습니다.", summaryText.isNotBlank())
            assertTrue(
                "${row.fixture.fileName} 요약이 전사 원문보다 짧지 않습니다.",
                summaryText.length < row.transcript.length,
            )
        }
        writeEvidence(resultFile, rows)
        assertEquals(FIXTURE_COUNT, rows.size)
        val failedSummaryIds = rows.filterNot { it.summaryQualityPassed == true }.map { it.fixture.id }
        assertTrue(
            "Gemma 요약이 TTS 원문의 핵심 주제를 유지하지 못했습니다: $failedSummaryIds",
            failedSummaryIds.isEmpty(),
        )
    }

    private fun writeEvidence(file: File, rows: List<PipelineRow>) {
        val root = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("fixtureCount", rows.size)
            .put(
                "results",
                JSONArray().apply {
                    rows.forEach { row ->
                        put(
                            JSONObject()
                                .put("id", row.fixture.id)
                                .put("fileName", row.fixture.fileName)
                                .put("audioBytes", row.source.length())
                                .put("durationMs", row.durationMs)
                                .put("transcriptChars", row.transcript.length)
                                .put("transcriptSha256", row.transcriptHash)
                                .put("sttElapsedMs", row.sttElapsedMs)
                                .put("sttKeywordHits", JSONArray(row.sttKeywordHits))
                                .put("sttQualityStatus", row.sttQualityStatus)
                                .put("sttSegmentCount", row.sttSegmentCount)
                                .put("summaryElapsedMs", row.summaryElapsedMs)
                                .put("summaryTitle", row.summary?.title)
                                .put("summaryText", row.summary?.bullets?.joinToString(" "))
                                .put("summaryChars", row.summary?.bullets?.sumOf(String::length))
                                .put("summaryValidationStatus", row.summary?.validationStatus)
                                .put("summaryKeywordHits", JSONArray(row.summaryKeywordHits))
                                .put("summaryQualityPassed", row.summaryQualityPassed)
                                .put("summaryError", row.summaryError),
                        )
                    }
                },
            )
        file.writeText(root.toString(2))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val id: String,
        val fileName: String,
        val keywords: List<String>,
    )

    private data class PipelineRow(
        val fixture: Fixture,
        val source: File,
        val durationMs: Long,
        val transcript: String,
        val transcriptHash: String,
        val sttElapsedMs: Long,
        val sttKeywordHits: List<String>,
        val sttQualityStatus: String,
        val sttSegmentCount: Int,
        var summaryElapsedMs: Long? = null,
        var summary: LocalSummary? = null,
        var summaryKeywordHits: List<String> = emptyList(),
        var summaryQualityPassed: Boolean? = null,
        var summaryError: String? = null,
    )

    private companion object {
        const val ARG_RUN_FIVE_TTS_PIPELINE = "runFiveTtsPipeline"
        const val INPUT_DIRECTORY = "qa-tts-five"
        const val RESULT_DIRECTORY = "qa-results"
        const val RESULT_FILE = "five-tts-pipeline.json"
        const val FIXTURE_COUNT = 5
        const val MIN_DURATION_MS = 60_000L
        const val MAX_DURATION_MS = 180_500L
        const val STT_TIMEOUT_MS = 8L * 60L * 1_000L
        const val SUMMARY_BATCH_TIMEOUT_MS = 30L * 60L * 1_000L
        const val EXPECTED_VALIDATION_STATUS = "PASSED_GROUNDED_V6"

        val FIXTURES = listOf(
            Fixture("tts_01", "tts_01_release.wav", listOf("출시", "테스트", "품질")),
            Fixture("tts_02", "tts_02_customer.wav", listOf("고객", "배송", "환불")),
            Fixture("tts_03", "tts_03_backup.wav", listOf("데이터", "백업", "복구")),
            Fixture("tts_04", "tts_04_content.wav", listOf("상품", "영상", "콘텐츠")),
            Fixture("tts_05", "tts_05_ondevice.wav", listOf("음성", "모델", "기기")),
        )
    }
}
