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

    /**
     * The terms of a glossary as phrases for the on-device recognizer to listen out for: the term
     * of each line without its description ("Priya Sharma — product lead" gives "Priya Sharma"),
     * or the part after an arrow ("pre a → Priya"). Words keep only letters, digits and inner
     * apostrophes, hyphens, dots and ampersands; sherpa-onnx reads ":", "#", "@" and "/" as
     * instructions and stops the app on some of them.
     */
    fun hotwords(glossary: String, max: Int = 200): List<String> = glossary.lines().mapNotNull { line ->
        val term = line.split("→", "->", "=>").last()
            .split(" — ", " – ", " - ", ":", "(", "=", ";", ",", "\t").first()
        val words = term.split(Regex("\\s+")).mapNotNull { raw ->
            raw.filter { it.isLetterOrDigit() || it in WORD_PUNCTUATION || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() } }
                .trim { it in WORD_PUNCTUATION }
                .take(MAX_WORD_CHARS)
                .takeIf { it.isNotEmpty() }
        }
        words.takeIf { it.isNotEmpty() && it.size <= MAX_PHRASE_WORDS }?.joinToString(" ")
    }.distinct().take(max)

    /**
     * A SentencePiece vocabulary ("piece<TAB>score" lines) from a sherpa-onnx tokens.txt ("piece id"
     * lines), which sherpa-onnx needs to split hotwords into a NeMo model's tokens. BPE pieces
     * score minus their id, as SentencePiece itself stores them, so earlier merges win.
     */
    fun bpeVocab(tokens: String): String = buildString {
        for (line in tokens.lineSequence()) {
            val split = line.trimEnd().lastIndexOf(' ')
            if (split <= 0) continue
            val id = line.substring(split + 1).trim().toIntOrNull() ?: continue
            append(line, 0, split).append('\t').append(-id).append('\n')
        }
    }

    private const val WORD_PUNCTUATION = "'’-.&"
    private const val MAX_PHRASE_WORDS = 6
    private const val MAX_WORD_CHARS = 40
}
