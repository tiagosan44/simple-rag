package com.example.rag.service

import com.example.rag.config.OpenAIProps
import com.example.rag.error.LlmProviderUnavailable
import com.example.rag.model.AskResponse
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class RagServiceChatWireMockTest {

    companion object {
        private lateinit var wm: WireMockServer

        @BeforeAll
        @JvmStatic
        fun setup() {
            wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
            wm.start()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            wm.stop()
        }
    }

    private fun serviceUnderTest(): Pair<RagServiceImpl, VectorStoreService> {
        val baseUrl = "http://localhost:${wm.port()}"
        val webClient = WebClient.builder().build()
        val props = OpenAIProps(apiKey = "test-key", llmModel = "gpt-4o-mini", baseUrl = baseUrl)
        val openAIClient = OpenAIClient(props, webClient)

        // Simple vector store stub that returns one chunk
        val vs = object : VectorStoreService {
            override fun initCollection(vectorSize: Int) {}
            override fun upsert(points: List<com.example.rag.model.QdrantPoint>) {}
            override fun search(vector: FloatArray, topK: Int): List<com.example.rag.model.RetrievedChunk> =
                listOf(com.example.rag.model.RetrievedChunk("doc-4", "Refunds are issued within 5–7 business days after review.", 0.92, null, null))
            override fun getById(id: String) = null
        }

        val embedding = object : EmbeddingService {
            override fun embedText(text: String) = com.example.rag.model.EmbeddingResult(
                id = "probe",
                vector = FloatArray(1536) { if (it == 0) 1f else 0f },
                model = "text-embedding-3-small",
                createdAtIso = java.time.Instant.now().toString()
            )
        }

        val rag = RagServiceImpl(embedding, vs, props, openAIClient)
        return rag to vs
    }

    @Test
    fun `chat success returns answer model and usage`() {
        // Arrange WireMock
        wm.stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    """
                    {
                      "id": "cmpl-123",
                      "model": "gpt-4o-mini",
                      "choices": [ { "index": 0, "message": { "role":"assistant", "content":"Refunds are issued within 5–7 business days [doc-4]." } } ],
                      "usage": { "prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120 }
                    }
                    """.trimIndent()
                )))

        val (rag, _) = serviceUnderTest()

        // Act
        val resp: AskResponse = rag.answer("What is our refund policy?", 4, 0.0)

        // Assert
        assertTrue(resp.answer.contains("[doc-4]"))
        assertEquals("gpt-4o-mini", resp.model)
        assertNotNull(resp.usage)
        assertEquals(120, resp.usage!!.total_tokens)
        assertTrue(resp.prompt.contains("Context:"))
        assertTrue(resp.source_chunks.isNotEmpty())
    }

    @Test
    fun `chat provider unavailable maps to LLM_PROVIDER_UNAVAILABLE`() {
        // Return 503 from provider
        wm.stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse().withStatus(503)))

        val (rag, _) = serviceUnderTest()

        val ex = assertThrows(LlmProviderUnavailable::class.java) {
            rag.answer("What is our refund policy?", 4, 0.0)
        }
        assertTrue(ex.message!!.contains("LLM provider"))
        assertNotNull(ex.details)
    }
}
