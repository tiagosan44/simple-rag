package com.example.rag.controller

import com.example.rag.error.GlobalExceptionHandler
import com.example.rag.error.LlmProviderUnavailable
import com.example.rag.model.AskRequest
import com.example.rag.service.EmbeddingService
import com.example.rag.service.RagService
import com.example.rag.service.VectorStoreService
import org.junit.jupiter.api.Assertions.assertEquals
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
class AskEndpointErrorMappingTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockBean
    lateinit var ragService: RagService

    @MockBean
    lateinit var embeddingService: EmbeddingService

    @MockBean
    lateinit var vectorStoreService: VectorStoreService

    @Test
    fun `LLM provider errors return 503 with canonical error body`() {
        Mockito.`when`(ragService.answer(Mockito.anyString(), Mockito.anyInt(), Mockito.anyDouble()))
            .thenThrow(LlmProviderUnavailable("LLM provider unreachable", mapOf("cause" to "timeout")))

        val body = "{" +
                "\"question\":\"Hello?\",\n" +
                "\"top_k\":4,\n" +
                "\"temperature\":0.0" +
                "}"

        val resp = webTestClient.post()
            .uri("/api/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("LLM_PROVIDER_UNAVAILABLE")
            .jsonPath("$.error.message").value<String> { msg -> assertEquals(true, msg.contains("LLM provider")) }
            .returnResult()
    }
}
