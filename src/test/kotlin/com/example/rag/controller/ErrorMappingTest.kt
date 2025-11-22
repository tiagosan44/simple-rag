package com.example.rag.controller

import com.example.rag.error.EmbeddingProviderUnavailable
import com.example.rag.error.GlobalExceptionHandler
import com.example.rag.error.VectorStoreUnavailable
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
class ErrorMappingTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockBean
    lateinit var ragService: RagService

    @MockBean
    lateinit var embeddingService: EmbeddingService

    @MockBean
    lateinit var vectorStoreService: VectorStoreService

    @Test
    fun `embed endpoint with EmbeddingProviderUnavailable returns 503`() {
        Mockito.`when`(embeddingService.embedText(Mockito.anyString()))
            .thenThrow(EmbeddingProviderUnavailable("OpenAI embedding service unavailable", mapOf("cause" to "timeout")))

        val body = """{"text":"test text","debug":false}"""

        webTestClient.post()
            .uri("/api/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("EMBEDDING_PROVIDER_UNAVAILABLE")
            .jsonPath("$.error.message").exists()
            .jsonPath("$.error.trace_id").exists()
    }

    @Test
    fun `search endpoint with EmbeddingProviderUnavailable returns 503`() {
        Mockito.`when`(embeddingService.embedText(Mockito.anyString()))
            .thenThrow(EmbeddingProviderUnavailable("OpenAI embedding service unavailable", mapOf("cause" to "connection_error")))

        val body = """{"query":"test query","top_k":5}"""

        webTestClient.post()
            .uri("/api/search")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("EMBEDDING_PROVIDER_UNAVAILABLE")
            .jsonPath("$.error.message").exists()
            .jsonPath("$.error.trace_id").exists()
    }

    @Test
    fun `ask endpoint with EmbeddingProviderUnavailable returns 503`() {
        Mockito.`when`(ragService.answer(Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
            .thenThrow(EmbeddingProviderUnavailable("Embedding service down", null))

        val body = """{"question":"What is the refund policy?","top_k":4,"temperature":0.0}"""

        webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("EMBEDDING_PROVIDER_UNAVAILABLE")
            .jsonPath("$.error.message").exists()
            .jsonPath("$.error.trace_id").exists()
    }

    @Test
    fun `ask endpoint with VectorStoreUnavailable returns 503`() {
        Mockito.`when`(ragService.answer(Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
            .thenThrow(VectorStoreUnavailable("Vector store connection failed", mapOf("status" to "unreachable")))

        val body = """{"question":"What is the refund policy?","top_k":4,"temperature":0.0}"""

        webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("VECTOR_STORE_UNAVAILABLE")
            .jsonPath("$.error.message").exists()
            .jsonPath("$.error.trace_id").exists()
    }

    @Test
    fun `generic exception returns 500 with INTERNAL_ERROR`() {
        Mockito.`when`(ragService.answer(Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
            .thenThrow(RuntimeException("Unexpected error"))

        val body = """{"question":"What is the refund policy?","top_k":4,"temperature":0.0}"""

        webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(500)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.error.message").exists()
            .jsonPath("$.error.trace_id").exists()
    }
}
