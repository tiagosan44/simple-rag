package com.example.rag.service

import com.example.rag.config.OpenAIProps
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class EmbeddingServiceOpenAIWireMockTest {

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

    @Test
    fun `success returns provider vector`() {
        val baseUrl = "http://localhost:${wm.port()}"
        val props = OpenAIProps(apiKey = "test-key", embeddingModel = "text-embedding-3-small", baseUrl = baseUrl)
        val svc = EmbeddingServiceImpl(props, WebClient.builder().build())

        val vector = List(1536) { 0.001 * (it + 1) }
        wm.stubFor(post(urlEqualTo("/embeddings")).willReturn(
            aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(
                    """
                    {
                      "object": "list",
                      "data": [ { "object": "embedding", "embedding": ${vector}, "index": 0 } ],
                      "model": "text-embedding-3-small",
                      "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                    }
                    """.trimIndent()
                )
        ))

        val res = svc.embedText("hello")
        assertEquals(1536, res.vector.size)
        assertEquals("text-embedding-3-small", res.model)
    }

    @Test
    fun `failure falls back to deterministic embedding`() {
        val baseUrl = "http://localhost:${wm.port()}"
        val props = OpenAIProps(apiKey = "test-key", embeddingModel = "text-embedding-3-small", baseUrl = baseUrl)
        val svc = EmbeddingServiceImpl(props, WebClient.builder().build())

        wm.stubFor(post(urlEqualTo("/embeddings")).willReturn(
            aResponse().withStatus(503)
        ))

        val res1 = svc.embedText("hello")
        val res2 = svc.embedText("hello")
        assertEquals(1536, res1.vector.size)
        // deterministic fallback: vectors equal
        for (i in res1.vector.indices) {
            assertEquals(res1.vector[i], res2.vector[i], 1e-6f)
        }
    }
}
