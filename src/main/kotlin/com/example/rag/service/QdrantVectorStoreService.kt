package com.example.rag.service

import com.example.rag.config.QdrantProps
import com.example.rag.config.FeatureFlags
import com.example.rag.error.VectorStoreUnavailable
import com.example.rag.model.QdrantPoint
import com.example.rag.model.RetrievedChunk
import com.example.rag.model.*
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
/**
 * Qdrant REST-backed implementation of VectorStoreService.
 * Uses cosine distance with normalized 0..1 scores.
 */
@Primary
@Service
class QdrantVectorStoreService(
    private val qdrantHttpClient: QdrantHttpClient,
    private val props: QdrantProps,
    private val features: FeatureFlags
) : VectorStoreService {

    private val log = LoggerFactory.getLogger(QdrantVectorStoreService::class.java)

    override fun initCollection(vectorSize: Int) {
        // Check collection
        val exists = qdrantHttpClient.checkCollectionExists()

        if (!exists) {
            createCollection(vectorSize)
            return
        }

        // Verify vector size
        try {
            val info = qdrantHttpClient.getCollectionInfo()
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
            log.error("Failed verifying Qdrant collection", e)
            throw VectorStoreUnavailable("Failed verifying Qdrant collection", mapOf("cause" to e.message))
        }
    }

    private fun createCollection(vectorSize: Int) {
        try {
            qdrantHttpClient.createCollection(vectorSize)
            log.info("Created Qdrant collection '{}' (size={}, distance=Cosine)", props.collection, vectorSize)
        } catch (e: Exception) {
            log.error("Cannot create Qdrant collection", e)
            throw VectorStoreUnavailable("Cannot create Qdrant collection at ${props.url}", mapOf("cause" to e.message))
        }
    }

    private fun deleteCollection() {
        try {
            qdrantHttpClient.deleteCollection()
        } catch (e: Exception) {
            log.warn("Failed deleting collection: {}", props.collection, e)
        }
    }

    override fun upsert(points: List<QdrantPoint>) {
        if (points.isEmpty()) return

        // Validate points before sending
        points.firstOrNull()?.let { firstPoint ->
            if (firstPoint.vector.isEmpty()) {
                log.error("Cannot upsert point with empty vector: id={}", firstPoint.id)
                throw VectorStoreUnavailable("Point has empty vector", mapOf("point_id" to firstPoint.id))
            }
            log.debug("Upserting {} points, vector_size={}", points.size, firstPoint.vector.size)
        }

        val upsertPoints = points.map {
            QdrantUpsertPoint(
                id = it.id,
                vector = it.vector.toList(),
                payload = it.payload
            )
        }

        try {
            qdrantHttpClient.upsertPoints(upsertPoints)
        } catch (e: org.springframework.web.reactive.function.client.WebClientResponseException) {
            log.error("Qdrant upsert failed: status={}", e.statusCode.value())
            throw VectorStoreUnavailable(
                "Qdrant upsert failed: ${e.statusCode.value()} ${e.statusText}",
                mapOf(
                    "status" to e.statusCode.value(),
                    "body" to e.responseBodyAsString.take(200),
                    "points_count" to points.size
                )
            )
        } catch (e: Exception) {
            log.error("Qdrant upsert failed", e)
            throw VectorStoreUnavailable("Qdrant upsert failed", mapOf("cause" to e.message))
        }
    }

    override fun search(vector: FloatArray, topK: Int): List<RetrievedChunk> {
        val resp = try {
            qdrantHttpClient.searchPoints(vector.toList(), topK)
        } catch (e: Exception) {
            log.error("Qdrant search failed", e)
            throw VectorStoreUnavailable("Qdrant search failed", mapOf("cause" to e.message))
        }

        return resp.result.map { r ->
            val payload = r.payload ?: emptyMap()
            val text = payload["original_text"] as? String ?: ""
            val chunkIndex = (payload["chunk_index"] as? Number)?.toInt()
            val source = payload["source"] as? String
            val score01 = r.score?.let { ((it + 1.0) / 2.0).coerceIn(0.0, 1.0) } ?: 0.0
            RetrievedChunk(id = r.id?.toString() ?: "", text = text, score = score01, chunkIndex = chunkIndex, source = source)
        }
    }

    override fun getById(id: String): RetrievedChunk? {
        val payloadFields = listOf("original_text", "chunk_index", "source")
        val filter = mapOf("must" to listOf(mapOf("has_id" to listOf(id))))
        val resp = try {
            qdrantHttpClient.scrollPoints(filter, payloadFields)
        } catch (e: Exception) {
            log.error("Qdrant getById failed: id={}", id, e)
            throw VectorStoreUnavailable("Qdrant getById failed", mapOf("cause" to e.message, "id" to id))
        }
        val first = resp?.result?.firstOrNull() ?: return null
        val text = (first.payload?.get("original_text") as? String) ?: ""
        val chunkIndex = (first.payload?.get("chunk_index") as? Number)?.toInt()
        val source = first.payload?.get("source") as? String
        return RetrievedChunk(id = first.id?.toString() ?: id, text = text, score = 1.0, chunkIndex = chunkIndex, source = source)
    }
}
