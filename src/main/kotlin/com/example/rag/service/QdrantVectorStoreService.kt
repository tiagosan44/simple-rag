package com.example.rag.service

import com.example.rag.config.QdrantProps
import com.example.rag.config.FeatureFlags
import com.example.rag.error.VectorStoreUnavailable
import com.example.rag.model.QdrantPoint
import com.example.rag.model.RetrievedChunk
import com.example.rag.util.defaultRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * Qdrant REST-backed implementation of VectorStoreService.
 * Uses cosine distance with normalized 0..1 scores.
 */
@Primary
@Service
class QdrantVectorStoreService(
    private val webClient: WebClient,
    private val props: QdrantProps,
    private val features: FeatureFlags
) : VectorStoreService {

    private val log = LoggerFactory.getLogger(QdrantVectorStoreService::class.java)

    override fun initCollection(vectorSize: Int) {
        // Check collection
        val exists = webClient.get()
            .uri("${props.url}/collections/${props.collection}")
            .retrieve()
            .bodyToMono(QdrantCollectionInfo::class.java)
            .map { true }
            .onErrorResume { Mono.just(false) }
            .block() ?: false

        if (!exists) {
            createCollection(vectorSize)
            return
        }

        // Verify vector size
        try {
            val info = webClient.get()
                .uri("${props.url}/collections/${props.collection}")
                .retrieve()
                .bodyToMono(QdrantCollectionInfo::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
            val current = info?.result?.vectors?.size ?: vectorSize
            if (current != vectorSize) {
                val msg = "Qdrant collection vector size mismatch: have=$current expected=$vectorSize"
                if (features.forceRecreate) {
                    log.warn("{} – recreating due to FORCE_RECREATE=true", msg)
                    deleteCollection()
                    createCollection(vectorSize)
                } else {
                    log.error(msg)
                    throw VectorStoreUnavailable(msg, mapOf("expected" to vectorSize, "actual" to current))
                }
            }
        } catch (e: Exception) {
            if (e is VectorStoreUnavailable) throw e
            throw VectorStoreUnavailable("Failed verifying Qdrant collection", mapOf("error" to e.toString()))
        }
    }

    private fun createCollection(vectorSize: Int) {
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
        try {
            webClient.put()
                .uri("${props.url}/collections/${props.collection}")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
            log.info("Created Qdrant collection '{}' (size={}, distance=Cosine)", props.collection, vectorSize)
        } catch (e: Exception) {
            throw VectorStoreUnavailable("Cannot create Qdrant collection at ${props.url}", mapOf("cause" to e.toString()))
        }
    }

    private fun deleteCollection() {
        try {
            webClient.delete()
                .uri("${props.url}/collections/${props.collection}")
                .retrieve()
                .bodyToMono(Void::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
        } catch (e: Exception) {
            log.warn("Failed deleting collection {}: {}", props.collection, e.toString())
        }
    }

    override fun upsert(points: List<QdrantPoint>) {
        if (points.isEmpty()) return
        val upsertReq = QdrantUpsertRequest(
            points = points.map {
                QdrantUpsertPoint(
                    id = it.id,
                    vector = it.vector.toList(),
                    payload = it.payload
                )
            }
        )
        try {
            webClient.put()
                .uri("${props.url}/collections/${props.collection}/points?wait=true")
                .bodyValue(upsertReq)
                .retrieve()
                .bodyToMono(QdrantOpResult::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
        } catch (e: Exception) {
            throw VectorStoreUnavailable("Qdrant upsert failed", mapOf("cause" to e.toString()))
        }
    }

    override fun search(vector: FloatArray, topK: Int): List<RetrievedChunk> {
        val req = QdrantSearchRequest(
            vector = vector.toList(),
            limit = topK
        )
        val resp = try {
            webClient.post()
                .uri("${props.url}/collections/${props.collection}/points/search")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .retryWhen(defaultRetryPolicy())
                .block() ?: QdrantSearchResponse(result = emptyList())
        } catch (e: Exception) {
            throw VectorStoreUnavailable("Qdrant search failed", mapOf("cause" to e.toString()))
        }

        return resp.result.map { r ->
            val payload = r.payload ?: emptyMap<String, Any?>()
            val text = payload["original_text"] as? String ?: ""
            val chunkIndex = (payload["chunk_index"] as? Number)?.toInt()
            val source = payload["source"] as? String
            val score01 = r.score?.let { ((it + 1.0) / 2.0).coerceIn(0.0, 1.0) } ?: 0.0
            RetrievedChunk(id = r.id?.toString() ?: "", text = text, score = score01, chunkIndex = chunkIndex, source = source)
        }
    }

    override fun getById(id: String): RetrievedChunk? {
        val payloadFields = listOf("original_text", "chunk_index", "source")
        val req = mapOf("filter" to mapOf("must" to listOf(mapOf("has_id" to listOf(id)))), "with_payload" to payloadFields)
        val resp = try {
            webClient.post()
                .uri("${props.url}/collections/${props.collection}/points/scroll")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(QdrantScrollResponse::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
        } catch (e: Exception) {
            throw VectorStoreUnavailable("Qdrant getById failed", mapOf("cause" to e.toString(), "id" to id))
        }
        val first = resp?.result?.firstOrNull() ?: return null
        val text = (first.payload?.get("original_text") as? String) ?: ""
        val chunkIndex = (first.payload?.get("chunk_index") as? Number)?.toInt()
        val source = first.payload?.get("source") as? String
        return RetrievedChunk(id = first.id?.toString() ?: id, text = text, score = 1.0, chunkIndex = chunkIndex, source = source)
    }
}

// --- Qdrant DTOs ---
private data class QdrantCollectionInfo(val result: QdrantCollectionResult? = null)
private data class QdrantCollectionResult(val vectors: QdrantVectors? = null)
private data class QdrantVectors(val size: Int? = null, val distance: String? = null)

private data class QdrantUpsertRequest(val points: List<QdrantUpsertPoint>)
private data class QdrantUpsertPoint(
    val id: Any,
    val vector: List<Float>,
    val payload: Map<String, Any?>
)
private data class QdrantOpResult(val status: String? = null)

private data class QdrantSearchRequest(
    val vector: List<Float>,
    val limit: Int
)
private data class QdrantSearchResponse(val result: List<QdrantScoredPoint> = emptyList())
private data class QdrantScoredPoint(
    val id: Any? = null,
    val score: Double? = null,
    val payload: Map<String, Any?>? = null
)

private data class QdrantScrollResponse(val result: List<QdrantPointPayload> = emptyList())
private data class QdrantPointPayload(
    val id: Any? = null,
    val payload: Map<String, Any?>? = null
)
