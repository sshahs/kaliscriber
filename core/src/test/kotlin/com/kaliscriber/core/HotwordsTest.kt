package com.kaliscriber.core

import com.kaliscriber.core.transcript.Glossary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HotwordsTest {
    @Test
    fun `glossary lines become the terms the recognizer listens for`() {
        val glossary = """
            Priya Sharma — product lead
            KaliScriber (our app)
            AFFiNE: notes tool
            pre a → Priya
            Kubernetes, k8s
            O'Brien
            AT&T
            Zoë Müller - design

            Q3 2026 roadmap
        """.trimIndent()
        assertEquals(
            listOf("Priya Sharma", "KaliScriber", "AFFiNE", "Priya", "Kubernetes", "O'Brien", "AT&T", "Zoë Müller", "Q3 2026 roadmap"),
            Glossary.hotwords(glossary),
        )
    }

    @Test
    fun `characters sherpa-onnx treats as instructions never reach it`() {
        val hotwords = Glossary.hotwords("#launch\n@sam\nfoo/bar\n:2.5\n...\nemoji 😀 party\nC++ team\n\"Project X\"")
        assertEquals(listOf("launch", "sam", "foobar", "emoji party", "C team", "Project X"), hotwords)
        assertTrue(hotwords.none { w -> w.any { it in ":#@/" } })
    }

    @Test
    fun `a NeMo token list becomes a SentencePiece vocabulary`() {
        assertEquals(
            "<unk>\t0\n▁the\t-1\nab\t-2\n<blk>\t-3\n",
            Glossary.bpeVocab("<unk> 0\n▁the 1\nab 2\n\nbroken\n<blk> 3\n"),
        )
    }

    @Test
    fun `long lines are left out and the list is capped`() {
        assertEquals(emptyList(), Glossary.hotwords("this is a whole sentence about the team and its plans"))
        assertEquals(10, Glossary.hotwords((1..50).joinToString("\n") { "Term$it" }, max = 10).size)
        assertEquals(listOf("Sam"), Glossary.hotwords("Sam\nSam\nSam — again"))
    }
}
