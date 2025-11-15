package com.example.rag.model

data class EmbeddingResult(
    val id: String,
    val vector: FloatArray,
    val model: String,
    val createdAtIso: String
)

data class QdrantPoint(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, Any?>
)

data class RetrievedChunk(
    val id: String,
    val text: String,
    val score: Double,
    val chunkIndex: Int?,
    val source: String?
)
