package com.example.rag.service

import com.example.rag.config.OpenAIProps
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddingServiceImplTest {

    private val svc = EmbeddingServiceImpl(OpenAIProps(), OpenAIClient(OpenAIProps(), org.springframework.web.reactive.function.client.WebClient.create()))

    @Test
    fun `embedText returns deterministic 1536-dim normalized vector`() {
        val text = "Hello world"
        val e1 = svc.embedText(text)
        val e2 = svc.embedText(text)

        assertEquals(1536, e1.vector.size)
        assertEquals(1536, e2.vector.size)
        assertEquals(e1.id, e2.id, "Embedding id should be stable for same text")

        fun norm(v: FloatArray): Double = kotlin.math.sqrt(v.fold(0.0) { acc, f -> acc + f * f })
        val n1 = norm(e1.vector)
        val n2 = norm(e2.vector)
        assertTrue(kotlin.math.abs(1.0 - n1) < 1e-3, "Vector should be L2 normalized ~1.0, was $n1")
        assertTrue(kotlin.math.abs(1.0 - n2) < 1e-3, "Vector should be L2 normalized ~1.0, was $n2")

        // Vectors should be equal element-wise (deterministic hash-based fallback)
        for (i in e1.vector.indices) {
            assertEquals(e1.vector[i], e2.vector[i], 1e-6f)
        }
    }
}
