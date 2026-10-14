package com.kaliscriber.core.transcript

/** Applies glossary corrections (misheard phrase → correct term) to transcript text. */
object Glossary {

    /**
     * Replaces each misheard phrase in [text] with its correction, matching whole words and
     * ignoring case, so "pre a" becomes "Priya" but "prepare" is left alone.
     */
    fun apply(text: String, fixes: List<Pair<String, String>>): String {
        var out = text
        // Longer phrases first, so "cali scribe app" wins over "cali scribe".
        for ((from, to) in fixes.sortedByDescending { it.first.length }) {
            val words = from.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val pattern = words.joinToString("[\\s,.-]+") { Regex.escape(it) }
            out = Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(out) { to }
        }
        return out
    }
}
