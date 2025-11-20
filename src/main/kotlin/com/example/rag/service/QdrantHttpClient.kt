package com.example.rag.service

import com.example.rag.config.QdrantProps
import com.example.rag.model.*
import com.example.rag.util.defaultRetryPolicy
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * HTTP client for Qdrant vector database operations.
 * Encapsulates all REST API calls to Qdrant.
 */
@Component
class QdrantHttpClient(
    private val webClient: WebClient,
    private val props: QdrantProps
) {

    /**
     * Returns the base URI for the collection
     */
    private fun collectionUri(): String = "${props.url}/collections/${props.collection}"

    /**
     * Returns the URI for collection points operations
     */
    private fun collectionPointsUri(path: String): String = "${collectionUri()}/points$path"

    /**
     * Retrieves collection information from Qdrant
     */
    internal fun getCollectionInfo(): QdrantCollectionInfo? {
        return try {
            webClient.get()
                .uri(collectionUri())
                .retrieve()
                .bodyToMono(QdrantCollectionInfo::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if collection exists
     */
    internal fun checkCollectionExists(): Boolean {
        return try {
            webClient.get()
                .uri(collectionUri())
                .retrieve()
                .bodyToMono(QdrantCollectionInfo::class.java)
                .map { true }
                .onErrorResume { Mono.just(false) }
                .block() ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates a collection with the specified vector size
     */
    internal fun createCollection(vectorSize: Int) {
        val body = mapOf(
            "vectors" to mapOf(
                "size" to vectorSize,
                "distance" to "Cosine"
            ),
            "hnsw_config" to mapOf(
                "m" to 16,
                "ef_construct" to 128
            )
        )
        webClient.put()
            .uri(collectionUri())
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Void::class.java)
            .retryWhen(defaultRetryPolicy())
            .block()
    }

    /**
     * Deletes the collection
     */
    internal fun deleteCollection() {
        webClient.delete()
            .uri(collectionUri())
            .retrieve()
            .bodyToMono(Void::class.java)
            .retryWhen(defaultRetryPolicy())
            .block()
    }

    /**
     * Upserts points into the collection
     */
    internal fun upsertPoints(points: List<QdrantUpsertPoint>): QdrantOpResult? {
        val upsertReq = QdrantUpsertRequest(points = points)
        return webClient.put()
            .uri(collectionPointsUri("?wait=true"))
            .bodyValue(upsertReq)
            .retrieve()
            .bodyToMono(QdrantOpResult::class.java)
            .retryWhen(defaultRetryPolicy())
            .block()
    }

    /**
     * Searches for similar vectors in the collection
     */
    internal fun searchPoints(vector: List<Float>, limit: Int): QdrantSearchResponse {
        val req = QdrantSearchRequest(vector = vector, limit = limit)
        return webClient.post()
            .uri(collectionPointsUri("/search"))
            .bodyValue(req)
            .retrieve()
            .bodyToMono(QdrantSearchResponse::class.java)
            .retryWhen(defaultRetryPolicy())
            .block() ?: QdrantSearchResponse(result = emptyList())
    }

    /**
     * Scrolls through points with a filter
     */
    internal fun scrollPoints(filter: Map<String, Any>, withPayload: List<String>): QdrantScrollResponse? {
        val req = mapOf("filter" to filter, "with_payload" to withPayload)
        return webClient.post()
            .uri(collectionPointsUri("/scroll"))
            .bodyValue(req)
            .retrieve()
            .bodyToMono(QdrantScrollResponse::class.java)
            .retryWhen(defaultRetryPolicy())
            .block()
    }
}

