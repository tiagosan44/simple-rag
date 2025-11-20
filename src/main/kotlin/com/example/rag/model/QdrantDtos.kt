package com.example.rag.model

/**
 * DTOs for Qdrant Vector Database API
 */

// Collection management DTOs
internal data class QdrantCollectionInfo(
    val result: QdrantCollectionResult? = null
)

internal data class QdrantCollectionResult(
    val vectors: QdrantVectors? = null
)

internal data class QdrantVectors(
    val size: Int? = null,
    val distance: String? = null
)

// Upsert operation DTOs
internal data class QdrantUpsertRequest(
    val points: List<QdrantUpsertPoint>
)

internal data class QdrantUpsertPoint(
    val id: Any,
    val vector: List<Float>,
    val payload: Map<String, Any?>
)

internal data class QdrantOpResult(
    val status: String? = null
)

// Search operation DTOs
internal data class QdrantSearchRequest(
    val vector: List<Float>,
    val limit: Int,
    val with_payload: Boolean = true
)

internal data class QdrantSearchResponse(
    val result: List<QdrantScoredPoint> = emptyList()
)

internal data class QdrantScoredPoint(
    val id: Any? = null,
    val score: Double? = null,
    val payload: Map<String, Any?>? = null
)

// Scroll operation DTOs
internal data class QdrantScrollResponse(
    val result: List<QdrantPointPayload> = emptyList()
)

internal data class QdrantPointPayload(
    val id: Any? = null,
    val payload: Map<String, Any?>? = null
)

