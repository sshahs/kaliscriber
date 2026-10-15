package com.kaliscriber.core.ai.tts

/**
 * Cuts an answer that is still being written into complete sentences, so each can be spoken as
 * soon as it is finished. Feed it the text so far; it returns the sentences completed since the
 * last call. Very short sentences wait to be joined with the next one so speech flows naturally.
 */
class SentenceStream(private val minChars: Int = 24) {
    private var consumed = 0
    private var held = ""

    /** New complete sentences in [partial] (the whole answer so far), ready to speak. */
    @Synchronized
    fun feed(partial: String): List<String> {
        if (partial.length < consumed) return emptyList()
        val out = mutableListOf<String>()
        var start = consumed
        var i = consumed
        while (i < partial.length) {
            val c = partial[i]
            val atBoundary = c == '\n' ||
                (c in ".!?" && i + 1 < partial.length && partial[i + 1].isWhitespace() && !isAbbreviation(partial, i))
            if (atBoundary) {
                emit(partial.substring(start, i + 1), out)
                start = i + 1
            }
            i++
        }
        consumed = start
        return out
    }

    /** What remains to speak once [full] (the finished answer) is known. */
    @Synchronized
    fun finish(full: String): List<String> {
        val out = mutableListOf<String>()
        val rest = if (full.length >= consumed) full.substring(consumed) else ""
        consumed = full.length
        val text = SpeechText.fromMarkdown(listOf(held, rest.trim()).filter { it.isNotEmpty() }.joinToString(" ")).trim()
        held = ""
        if (text.isNotEmpty()) out += text
        return out
    }

    private fun emit(raw: String, out: MutableList<String>) {
        val spoken = SpeechText.fromMarkdown(raw).trim()
        if (spoken.isEmpty()) return
        val joined = if (held.isEmpty()) spoken else "$held $spoken"
        if (joined.length < minChars) {
            held = joined
        } else {
            out += joined
            held = ""
        }
    }

    /** "e.g." or "Dr." in the middle of a sentence is not its end. */
    private fun isAbbreviation(text: String, dot: Int): Boolean {
        if (text[dot] != '.') return false
        val word = text.substring(0, dot).takeLastWhile { it.isLetter() || it == '.' }.lowercase()
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter())
    }

    private companion object {
        val ABBREVIATIONS = setOf("e.g", "i.e", "etc", "vs", "mr", "mrs", "ms", "dr", "approx", "no", "st")
    }
}
