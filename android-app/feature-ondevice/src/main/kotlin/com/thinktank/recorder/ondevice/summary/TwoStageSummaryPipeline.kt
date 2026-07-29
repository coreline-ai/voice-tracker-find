package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.modelpack.ModelRuntimeType
import org.json.JSONObject

internal fun interface LocalTextGenerator {
    suspend fun generate(request: GenerationRequest): String
}

internal data class GenerationRequest(
    val prompt: String,
    val maxTokens: Int,
    val grammar: String,
)

/**
 * Keeps the small model's jobs deliberately narrow:
 * 1) choose one or two source IDs, 2) compress only those source rows.
 */
internal class TwoStageSummaryPipeline(
    private val candidateReducer: SummaryCandidateReducer = SummaryCandidateReducer(),
    private val qualityGate: SummaryQualityGate = SummaryQualityGate(),
) {
    suspend fun summarize(
        transcript: String,
        profile: LocalLlmProfile,
        generator: LocalTextGenerator,
    ): LocalSummary {
        val candidates = candidateReducer.reduce(transcript)
        require(candidates.isNotEmpty()) { "요약할 원문 구간이 없습니다" }

        val selected = if (canSummarizeDirectly(transcript, candidates, profile)) {
            candidates
        } else {
            selectEvidence(candidates, profile, generator)
        }
        check(selected.isNotEmpty()) { "선택된 원문 구간이 없습니다" }

        val first = runCatching {
            parseAndValidate(
                raw = generator.generate(
                    GenerationRequest(
                        prompt = buildSummaryPrompt(transcript, selected),
                        maxTokens = profile.summaryTokens,
                        grammar = SUMMARY_GRAMMAR,
                    ),
                ),
                transcript = transcript,
                selected = selected,
                profile = profile,
            )
        }
        return first.getOrElse { firstError ->
            val reason = rejectionReason(firstError)
            runCatching {
                parseAndValidate(
                    raw = generator.generate(
                        GenerationRequest(
                            prompt = buildCorrectionPrompt(transcript, selected, reason),
                            maxTokens = profile.summaryTokens,
                            grammar = SUMMARY_GRAMMAR,
                        ),
                    ),
                    transcript = transcript,
                    selected = selected,
                    profile = profile,
                )
            }.getOrElse { secondError ->
                throw FinalLocalLlmOutputRejectedException(rejectionReason(secondError), secondError)
            }
        }
    }

    private suspend fun selectEvidence(
        candidates: List<SourceSegment>,
        profile: LocalLlmProfile,
        generator: LocalTextGenerator,
    ): List<SourceSegment> {
        val allowedIds = candidates.map(SourceSegment::id).toSet()
        val selectionRequest = GenerationRequest(
            prompt = buildSelectionPrompt(candidates),
            maxTokens = profile.selectionTokens,
            grammar = selectionGrammar(candidates.map(SourceSegment::id)),
        )
        val firstSelection = runCatching {
            parseSelectedIds(generator.generate(selectionRequest), allowedIds)
        }
        val selectedIds = firstSelection.getOrElse { firstError ->
            runCatching {
                parseSelectedIds(
                    generator.generate(
                        selectionRequest.copy(
                            prompt = """
                                이전 구간 선택 응답이 잘못되었습니다: ${rejectionReason(firstError)}
                                허용된 ID만 사용하고 설명 없이 JSON 하나만 반환하세요.
                                ${buildSelectionPrompt(candidates)}
                            """.trimIndent(),
                        ),
                    ),
                    allowedIds,
                )
            }.getOrElse { secondError ->
                throw FinalLocalLlmOutputRejectedException(
                    rejectionReason(secondError),
                    secondError,
                )
            }
        }
        return candidates.filter { it.id in selectedIds }
    }

    private fun canSummarizeDirectly(
        transcript: String,
        candidates: List<SourceSegment>,
        profile: LocalLlmProfile,
    ): Boolean =
        profile.runtimeType == ModelRuntimeType.LITERT_LM &&
            transcript.length <= DIRECT_SUMMARY_MAX_CHARS &&
            candidates.size <= DIRECT_SUMMARY_MAX_SEGMENTS

    private fun parseAndValidate(
        raw: String,
        transcript: String,
        selected: List<SourceSegment>,
        profile: LocalLlmProfile,
    ): LocalSummary {
        val payload = SummaryPayloadParser.parse(
            raw = raw,
            allowCompletePrefix = profile.runtimeType == ModelRuntimeType.LITERT_LM,
        )
        val summary = LocalSummary(
            title = compactTitle(payload.title),
            bullets = payload.bullets,
            actionItems = ExplicitActionItemExtractor.extract(selected),
            engine = profile.engineType,
            sourceHash = sourceHash(transcript),
            policyVersion = SummaryPolicy.VERSION,
            promptVersion = SummaryPolicy.PROMPT_VERSION,
            modelVersion = ModelCatalogVersion.version(profile.modelId),
            validationStatus = SUMMARY_VALIDATION_PASSED,
            requestedModelId = profile.modelId.name,
            actualModelId = profile.modelId.name,
            runtimeType = profile.runtimeType.name,
            generationProfile = profile.generationProfile,
            inputChars = transcript.length,
            outputChars = payload.bullets.sumOf(String::length),
        )
        return qualityGate.requireValid(
            summary = summary,
            transcript = transcript,
            evidenceIds = payload.bullets.map { selected.map(SourceSegment::id).toSet() },
        )
    }

    /**
     * A title is a label rather than a factual sentence, so shortening it at a word boundary does
     * not change the summary evidence. This avoids another expensive model pass when the only
     * violation is a verbose title.
     */
    private fun compactTitle(raw: String): String {
        val normalized = normalizeText(raw)
            .replace("...", "")
            .replace("…", "")
            .trim()
        if (normalized.length <= SummaryPolicy.MAX_TITLE_CHARS) return normalized

        val words = normalized.split(' ')
        val compact = StringBuilder()
        for (word in words) {
            val separator = if (compact.isEmpty()) 0 else 1
            if (compact.length + separator + word.length > SummaryPolicy.MAX_TITLE_CHARS) break
            if (separator == 1) compact.append(' ')
            compact.append(word)
        }
        return compact.toString()
            .takeIf(String::isNotBlank)
            ?: normalized.take(SummaryPolicy.MAX_TITLE_CHARS)
    }

    private fun buildSelectionPrompt(candidates: List<SourceSegment>): String = """
        다음 원문 후보에서 전체 기록의 핵심을 직접 말하는 구간 ID를 고르세요.
        기본은 1개이며 서로 독립된 핵심이 있을 때만 2개를 고르세요.
        다른 설명 없이 {"selectedIds":[1]} 형식의 JSON만 반환하세요.

        ${candidates.joinToString("\n") { "[${it.id}] ${it.text}" }}
    """.trimIndent()

    private fun buildSummaryPrompt(
        transcript: String,
        selected: List<SourceSegment>,
    ): String = """
        선택된 원문만 사용해 핵심 의미를 짧고 완결된 한국어 문장으로 압축하세요.
        summary는 기본 1개, 필요한 경우 최대 2개입니다.
        전체 ${SummaryPolicy.totalBudget(transcript)}자 이내이며 원문보다 반드시 짧아야 합니다.
        title은 ${SummaryPolicy.MAX_TITLE_CHARS}자 이내의 짧은 명사형 제목으로 쓰세요.
        이름, 날짜, 숫자, 영문 용어를 원문에 없으면 만들지 마세요.
        말줄임표, 문장 중간 절단, 일반적인 제목 반복을 금지합니다. 문장은 반드시 '다' 또는 '합니다'로 끝내세요.
        다른 설명 없이 다음 구조의 JSON만 반환하세요.
        {"title":"짧은 제목","summary":["완결된 핵심 문장"]}

        ${selected.joinToString("\n") { "[${it.id}] ${it.text}" }}
    """.trimIndent()

    private fun buildCorrectionPrompt(
        transcript: String,
        selected: List<SourceSegment>,
        reason: String,
    ): String = """
        이전 응답은 품질 검사에서 거절되었습니다: $reason
        아래 원문만 사용해 다시 작성하세요.
        title은 ${SummaryPolicy.MAX_TITLE_CHARS}자 이내,
        summary 1개를 우선하고 최대 2개, 전체 ${SummaryPolicy.totalBudget(transcript)}자 이내,
        완결된 한국어 문장만 허용하고 반드시 '다' 또는 '합니다'로 끝내세요. 설명 없이 JSON 하나만 반환하세요.
        {"title":"짧은 제목","summary":["완결된 핵심 문장"]}

        ${selected.joinToString("\n") { "[${it.id}] ${it.text}" }}
    """.trimIndent()

    private fun parseSelectedIds(raw: String, allowed: Set<Int>): Set<Int> {
        val json = JSONObject(QwenOutputParser.extractJsonObject(raw))
        val values = json.optJSONArray("selectedIds")
            ?: throw IllegalArgumentException("선택 응답에 selectedIds가 없습니다")
        val selected = buildSet {
            for (index in 0 until values.length()) {
                val id = values.optInt(index, -1)
                require(id in allowed) { "INVALID_EVIDENCE_ID" }
                add(id)
            }
        }
        require(selected.isNotEmpty() && selected.size <= 2) { "INVALID_EVIDENCE_ID" }
        return selected
    }

    private fun selectionGrammar(ids: List<Int>): String {
        val alternatives = ids.distinct().joinToString(" | ") { "\"$it\"" }
        return """
            root ::= "{" ws "\"selectedIds\"" ws ":" ws "[" ws id (ws "," ws id)? ws "]" ws "}"
            id ::= $alternatives
            ws ::= [ \t\n\r]*
        """.trimIndent()
    }

    private fun rejectionReason(error: Throwable): String =
        when (error) {
            is SummaryQualityException -> error.validation.correctionHint()
            else -> error.message?.takeIf(String::isNotBlank) ?: "INVALID_JSON_OR_SCHEMA"
        }

    private companion object {
        const val DIRECT_SUMMARY_MAX_CHARS = 600
        const val DIRECT_SUMMARY_MAX_SEGMENTS = 4

        val SUMMARY_GRAMMAR = """
            root ::= "{" ws "\"title\"" ws ":" ws string ws "," ws "\"summary\"" ws ":" ws "[" ws string (ws "," ws string)? ws "]" ws "}"
            string ::= "\"" chars "\""
            chars ::= char*
            char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
            ws ::= [ \t\n\r]*
        """.trimIndent()
    }
}

internal object ExplicitActionItemExtractor {
    fun extract(selected: List<SourceSegment>): List<String> =
        selected.asSequence()
            .map(SourceSegment::text)
            .map(::normalizeText)
            .filter(EXPLICIT_ACTION::containsMatchIn)
            .distinct()
            .take(MAX_ACTIONS)
            .toList()

    private val EXPLICIT_ACTION = Regex(
        """(?:해야\s*(?:한다|합니다|해요)|할\s*필요|하기로\s*(?:했다|했습니다)|요청(?:한다|합니다|했다|했습니다)|예정(?:이다|입니다))""",
    )
    private const val MAX_ACTIONS = 3
}

internal class SummaryCandidateReducer(
    private val segmenter: KoreanTranscriptSegmenter = KoreanTranscriptSegmenter(),
) {
    fun reduce(transcript: String): List<SourceSegment> {
        val all = segmenter.segment(transcript)
        if (all.size <= MAX_CANDIDATES) return all
        val selected = linkedSetOf<SourceSegment>()
        selected += all.first()
        selected += all.last()
        all.sortedByDescending(::score)
            .take(MAX_CANDIDATES - selected.size)
            .forEach(selected::add)
        return selected.sortedBy(SourceSegment::id).take(MAX_CANDIDATES)
    }

    private fun score(segment: SourceSegment): Int {
        val text = segment.text
        val signal = SIGNALS.sumOf { regex -> regex.findAll(text).count() * 20 }
        return text.length.coerceAtMost(160) + signal
    }

    private companion object {
        const val MAX_CANDIDATES = 8
        val SIGNALS = listOf(
            Regex("""\d+"""),
            Regex("""핵심|결론|문제|목표|필요|결정|성과|이유|방법|해야|예정"""),
        )
    }
}

internal class FinalLocalLlmOutputRejectedException(
    val reason: String,
    cause: Throwable,
) : IllegalArgumentException(reason, cause)

private object ModelCatalogVersion {
    fun version(modelId: com.thinktank.recorder.ondevice.modelpack.ModelId): String =
        com.thinktank.recorder.ondevice.modelpack.ModelCatalog.get(modelId).version
}
