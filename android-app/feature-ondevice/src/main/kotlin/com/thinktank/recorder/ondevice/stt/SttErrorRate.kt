package com.thinktank.recorder.ondevice.stt

internal data class SttErrorRate(
    val edits: Int,
    val referenceUnits: Int,
) {
    val rate: Float
        get() = if (referenceUnits == 0) {
            if (edits == 0) 0f else 1f
        } else {
            edits.toFloat() / referenceUnits.toFloat()
        }
}

internal object SttErrorRates {
    fun cer(reference: String, hypothesis: String): SttErrorRate {
        val expected = reference.filterNot(Char::isWhitespace).map(Char::toString)
        val actual = hypothesis.filterNot(Char::isWhitespace).map(Char::toString)
        return SttErrorRate(levenshtein(expected, actual), expected.size)
    }

    fun wer(reference: String, hypothesis: String): SttErrorRate {
        val expected = reference.normalizedWords()
        val actual = hypothesis.normalizedWords()
        return SttErrorRate(levenshtein(expected, actual), expected.size)
    }

    private fun String.normalizedWords(): List<String> =
        lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)

    private fun <T> levenshtein(expected: List<T>, actual: List<T>): Int {
        if (expected.isEmpty()) return actual.size
        if (actual.isEmpty()) return expected.size
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { expectedIndex, expectedUnit ->
            val current = IntArray(actual.size + 1)
            current[0] = expectedIndex + 1
            actual.forEachIndexed { actualIndex, actualUnit ->
                val substitution = previous[actualIndex] +
                    if (expectedUnit == actualUnit) 0 else 1
                current[actualIndex + 1] = minOf(
                    current[actualIndex] + 1,
                    previous[actualIndex + 1] + 1,
                    substitution,
                )
            }
            previous = current
        }
        return previous[actual.size]
    }
}
