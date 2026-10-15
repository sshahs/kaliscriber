package com.kaliscriber.core.library

/** Up to two characters standing for a person in an avatar: "Priya Shah" → "PS", "Speaker 3" → "S3". */
object Initials {
    fun of(name: String): String {
        val words = name.trim().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val first = words.first()
        if (words.size == 1) return first.take(if (first.all(Char::isDigit)) 2 else 1).uppercase()
        val last = words.last()
        return (first.take(1) + last.take(1)).uppercase()
    }
}
