package com.example.rag.controller

import com.example.rag.error.GlobalExceptionHandler
import com.example.rag.service.EmbeddingService
import com.example.rag.service.RagService
import com.example.rag.service.VectorStoreService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [RagController::class])
@Import(GlobalExceptionHandler::class, TestSecurityConfig::class)
class ValidationErrorTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockBean
    lateinit var ragService: RagService

    @MockBean
    lateinit var embeddingService: EmbeddingService

    @MockBean
    lateinit var vectorStoreService: VectorStoreService

    @Test
    fun `ask endpoint with blank question returns simple validation error`() {
        val body = """{"question":"","top_k":4,"temperature":0.0}"""

        webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.error.message").isEqualTo("Question must not be blank")
            .jsonPath("$.error.trace_id").exists()
    }

    @Test
    fun `embed endpoint with blank text returns simple validation error`() {
        val body = """{"text":"","debug":false}"""

        webTestClient.post()
            .uri("/api/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.error.message").isEqualTo("Text must not be blank")
            .jsonPath("$.error.trace_id").exists()
    }

    @Test
    fun `search endpoint with blank query returns simple validation error`() {
        val body = """{"query":"","top_k":5}"""

        webTestClient.post()
            .uri("/api/search")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.error.message").isEqualTo("Query must not be blank")
            .jsonPath("$.error.trace_id").exists()
    }
}
