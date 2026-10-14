package com.kaliscriber.core.ai.tts

/** Turns Markdown answers into text that sounds natural when read aloud. */
object SpeechText {

    fun fromMarkdown(markdown: String): String = markdown.lines()
        .map { line ->
            line.trim()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[-*+]\\s+\\[[ xX]]\\s+"), "")
                .replace(Regex("^[-*+]\\s+"), "")
                .replace(Regex("\\*\\*|__|`|~~"), "")
                .replace(Regex("(?<![\\w*])[*_](\\S[^*_]*)[*_](?![\\w*])"), "$1")
                .replace(Regex("\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)]"), "at $1")
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        }
        .filter { it.isNotBlank() && !it.startsWith("```") }
        .joinToString("\n") { if (it.last() in ".!?:;,") it else "$it." }

    /** Splits into pieces of at most [max] characters, preferring sentence then word boundaries. */
    fun split(text: String, max: Int): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = Regex("(?<=[.!?])\\s+|\\n+").split(clean).filter { it.isNotBlank() }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            var s = sentence.trim()
            while (s.length > max) {
                val cut = s.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                if (current.isNotEmpty()) {
                    parts += current.toString(); current.clear()
                }
                parts += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (current.isNotEmpty() && current.length + 1 + s.length > max) {
                parts += current.toString(); current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }
}
