package com.kaliscriber.core.ai

/** A meeting's agenda, as the user typed it. */
object Agenda {
    private val BULLET = Regex("""^\s*(?:[-*•–]|\d+[.)]|\[[ xX]?])\s*""")

    /** One item per non-empty line, without bullets or numbering. */
    fun items(text: String): List<String> =
        text.lines().map { it.replace(BULLET, "").trim() }.filter { it.isNotEmpty() }
}
