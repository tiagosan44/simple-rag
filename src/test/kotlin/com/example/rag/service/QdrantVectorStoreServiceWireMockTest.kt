package com.example.rag.service

import com.example.rag.config.FeatureFlags
import com.example.rag.config.QdrantProps
import com.example.rag.error.VectorStoreUnavailable
import com.example.rag.model.QdrantPoint
import com.example.rag.model.RetrievedChunk
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class QdrantVectorStoreServiceWireMockTest {

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

    private fun service(forceRecreate: Boolean = false): QdrantVectorStoreService {
        val props = QdrantProps(url = "http://localhost:${wm.port()}", collection = "rag_test")
        val features = FeatureFlags(authEnabled = false, debug = false, forceRecreate = forceRecreate)
        val webClient = WebClient.builder().build()
        return QdrantVectorStoreService(webClient, props, features)
    }

    @Test
    fun `initCollection creates when missing`() {
        // GET collection -> 404 triggers create
        wm.stubFor(get(urlEqualTo("/collections/rag_test")).willReturn(aResponse().withStatus(404)))
        wm.stubFor(put(urlEqualTo("/collections/rag_test")).willReturn(aResponse().withStatus(200)))

        assertDoesNotThrow { service().initCollection(1536) }
    }

    @Test
    fun `initCollection size mismatch without recreate throws`() {
        // Exists with wrong size
        wm.stubFor(get(urlEqualTo("/collections/rag_test")).willReturn(
            aResponse().withStatus(200).withHeader("Content-Type","application/json")
                .withBody("""{ "result": { "vectors": { "size": 512, "distance": "Cosine" } } }""")
        ))
        // Second GET for verification returns the same body
        wm.stubFor(get(urlEqualTo("/collections/rag_test")).willReturn(
            aResponse().withStatus(200).withHeader("Content-Type","application/json")
                .withBody("""{ "result": { "vectors": { "size": 512, "distance": "Cosine" } } }""")
        ))

        val ex = assertThrows(VectorStoreUnavailable::class.java) { service(forceRecreate = false).initCollection(1536) }
        assertTrue(ex.message!!.contains("mismatch"))
    }

    @Test
    fun `initCollection size mismatch with recreate deletes and creates`() {
        // Exists with wrong size
        wm.stubFor(get(urlEqualTo("/collections/rag_test")).willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/json")
            .withBody("""{ "result": { "vectors": { "size": 512, "distance": "Cosine" } } }""")))
        // Delete and create
        wm.stubFor(delete(urlEqualTo("/collections/rag_test")).willReturn(aResponse().withStatus(200)))
        wm.stubFor(put(urlEqualTo("/collections/rag_test")).willReturn(aResponse().withStatus(200)))

        assertDoesNotThrow { service(forceRecreate = true).initCollection(1536) }
    }

    @Test
    fun `upsert and search map payload and score`() {
        wm.stubFor(put(urlPathEqualTo("/collections/rag_test/points")).withQueryParam("wait", equalTo("true"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/json").withBody("{\"status\":\"ok\"}")))

        val svc = service()
        val p = QdrantPoint(
            id = "doc-4",
            vector = FloatArray(4) { 0.1f },
            payload = mapOf("original_text" to "Refunds are issued within 5–7 business days after review.", "chunk_index" to 0, "source" to "knowledge.json")
        )
        assertDoesNotThrow { svc.upsert(listOf(p)) }

        wm.stubFor(post(urlEqualTo("/collections/rag_test/points/search")).willReturn(
            aResponse().withStatus(200).withHeader("Content-Type","application/json").withBody(
                """
                {
                  "result": [
                    { "id": "doc-4", "score": 0.8, "payload": { "original_text": "Refunds are issued within 5–7 business days after review.", "chunk_index": 0, "source": "knowledge.json" } }
                  ]
                }
                """.trimIndent()
            )
        ))

        val results: List<RetrievedChunk> = svc.search(FloatArray(4) { 0.1f }, 1)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals("doc-4", r.id)
        assertEquals("knowledge.json", r.source)
        assertTrue(r.score in 0.0..1.0)
    }
}
