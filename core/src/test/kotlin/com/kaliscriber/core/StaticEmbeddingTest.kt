package com.kaliscriber.core

import com.kaliscriber.core.search.StaticEmbeddingModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compares with encodings from the model2vec Python library on models saved in its own format. */
class StaticEmbeddingTest {
    private fun check(name: String, tolerance: Double) {
        val dir = File(javaClass.getResource("/model2vec/$name/config.json")!!.toURI()).parentFile
        val model = StaticEmbeddingModel.load(dir)
        val expected = Json.parseToJsonElement(File(dir, "expected.json").readText()).jsonObject
        val texts = expected.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
        val ids = expected.getValue("ids").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }
        val vectors = expected.getValue("vectors").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.double } }
        texts.forEachIndexed { i, text ->
            assertEquals(ids[i], model.tokenIds(text), "tokens of \"$text\"")
            val got = model.embed(text)
            assertEquals(vectors[i].size, got.size)
            vectors[i].forEachIndexed { k, v -> assertTrue(abs(v - got[k]) < tolerance, "\"$text\"[$k]: expected $v, got ${got[k]}") }
        }
    }

    @Test
    fun `matches model2vec on a plain model`() = check("plain", 1e-5)

    @Test
    fun `matches model2vec with token weights, a token mapping and half-precision vectors`() = check("weighted", 1e-3)
}
