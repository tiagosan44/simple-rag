package com.example.rag.controller

import com.example.rag.error.GlobalExceptionHandler
import com.example.rag.model.*
import com.example.rag.service.EmbeddingService
import com.example.rag.service.RagService
import com.example.rag.service.VectorStoreService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [RagController::class])
@Import(GlobalExceptionHandler::class, TestSecurityConfig::class)
class PositiveFlowTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockBean
    lateinit var ragService: RagService

    @MockBean
    lateinit var embeddingService: EmbeddingService

    @MockBean
    lateinit var vectorStoreService: VectorStoreService

    @Test
    fun `embed endpoint returns embedding with vector when debug=true`() {
        val mockEmbedding = EmbeddingResult(
            id = "embed-123",
            vector = FloatArray(1536) { 0.001f * it },
            model = "text-embedding-3-small",
            createdAtIso = "2024-01-01T00:00:00Z"
        )
        Mockito.`when`(embeddingService.embedText("test text")).thenReturn(mockEmbedding)

        val body = """{"text":"test text","debug":true}"""

        webTestClient.post()
            .uri("/api/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.embedding_id").isEqualTo("embed-123")
            .jsonPath("$.vector_dim").isEqualTo(1536)
            .jsonPath("$.vector").isArray
            .jsonPath("$.vector.length()").isEqualTo(1536)
    }

    @Test
    fun `embed endpoint returns embedding without vector when debug=false`() {
        val mockEmbedding = EmbeddingResult(
            id = "embed-456",
            vector = FloatArray(1536) { 0.002f * it },
            model = "text-embedding-3-small",
            createdAtIso = "2024-01-01T00:00:00Z"
        )
        Mockito.`when`(embeddingService.embedText("another text")).thenReturn(mockEmbedding)

        val body = """{"text":"another text","debug":false}"""

        webTestClient.post()
            .uri("/api/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.embedding_id").isEqualTo("embed-456")
            .jsonPath("$.vector_dim").isEqualTo(1536)
            .jsonPath("$.vector").doesNotExist()
    }

    @Test
    fun `ask endpoint returns answer with source chunks and usage`() {
        val mockResponse = AskResponse(
            answer = "Refunds are issued within 5-7 business days [doc-4]",
            source_chunks = listOf(
                SourceChunk(
                    id = "doc-4",
                    text = "Refunds are issued within 5–7 business days after review.",
                    score = 0.92,
                    chunk_index = 0,
                    source = "knowledge.json"
                )
            ),
            raw_llm = "Refunds are issued within 5-7 business days [doc-4]",
            prompt = "Context:\n- Refunds are issued within 5–7 business days after review.\n\nQuestion: What is the refund policy?",
            latency_ms = 1250,
            model = "gpt-4o-mini",
            usage = Usage(
                prompt_tokens = 100,
                completion_tokens = 20,
                total_tokens = 120
            )
        )
        Mockito.`when`(ragService.answer("What is the refund policy?", 4, 0.0)).thenReturn(mockResponse)

        val body = """{"question":"What is the refund policy?","top_k":4,"temperature":0.0}"""

        webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.answer").isEqualTo("Refunds are issued within 5-7 business days [doc-4]")
            .jsonPath("$.source_chunks").isArray
            .jsonPath("$.source_chunks.length()").isEqualTo(1)
            .jsonPath("$.source_chunks[0].id").isEqualTo("doc-4")
            .jsonPath("$.source_chunks[0].score").isEqualTo(0.92)
            .jsonPath("$.model").isEqualTo("gpt-4o-mini")
            .jsonPath("$.usage.total_tokens").isEqualTo(120)
            .jsonPath("$.latency_ms").isNumber
            .jsonPath("$.prompt").exists()
    }

    @Test
    fun `ask endpoint with custom top_k and temperature`() {
        val mockResponse = AskResponse(
            answer = "Test answer",
            source_chunks = listOf(),
            raw_llm = "Test answer",
            prompt = "Test prompt",
            latency_ms = 500,
            model = "gpt-4o-mini",
            usage = Usage(50, 10, 60)
        )
        Mockito.`when`(ragService.answer("Test question?", 10, 0.7)).thenReturn(mockResponse)

        val body = """{"question":"Test question?","top_k":10,"temperature":0.7}"""

        webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.answer").exists()
            .jsonPath("$.model").isEqualTo("gpt-4o-mini")
    }

}
