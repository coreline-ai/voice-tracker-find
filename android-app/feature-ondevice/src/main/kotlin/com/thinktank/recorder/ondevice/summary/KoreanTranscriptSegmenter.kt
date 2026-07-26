package com.thinktank.recorder.ondevice.summary

internal data class SourceSegment(
    val id: Int,
    val text: String,
)

/**
 * Splits punctuation-light Korean STT into source-preserving utterance units.
 *
 * No text is shortened. If a recognizer emits one very long clause without a usable boundary,
 * that clause remains long and is later rejected as a summary candidate instead of being cut.
 */
internal class KoreanTranscriptSegmenter(
    private val minimumChars: Int = 8,
) {
    fun segment(transcript: String): List<SourceSegment> {
        val source = normalizeText(transcript)
        if (source.isBlank()) return emptyList()

        val rough = mutableListOf<String>()
        var start = 0
        BOUNDARY.findAll(source).forEach { match ->
            val end = match.range.last + 1
            source.substring(start, end)
                .trim()
                .takeIf(String::isNotBlank)
                ?.let(rough::add)
            start = end
        }
        if (start < source.length) {
            source.substring(start).trim().takeIf(String::isNotBlank)?.let(rough::add)
        }
        if (rough.isEmpty()) rough += source

        val merged = mutableListOf<String>()
        rough.forEach { current ->
            if (current.length < minimumChars && merged.isNotEmpty()) {
                merged[merged.lastIndex] = normalizeText("${merged.last()} $current")
            } else {
                merged += current
            }
        }
        if (merged.size > 1 && merged.first().length < minimumChars) {
            merged[1] = normalizeText("${merged.first()} ${merged[1]}")
            merged.removeAt(0)
        }
        return merged
            .map(::normalizeText)
            .filter(String::isNotBlank)
            .mapIndexed { index, text -> SourceSegment(index + 1, text) }
    }

    private companion object {
        private val BOUNDARY = Regex(
            """
            [.!?。！？]+
            |
            (?:
                했습니다|하였습니다|되었습니다|됐습니다|있습니다|없습니다|같습니다|
                합니다|됩니다|입니다|였습니다|것입니다|
                했어요|하였어요|됐어요|되었어요|있어요|없어요|같아요|
                해요|돼요|예요|이에요|거예요|거든요|는데요|네요|지요|죠|
                하고요|하구요
            )(?=\s+|$)
            """.trimIndent().replace(Regex("\\s+"), ""),
        )
    }
}
