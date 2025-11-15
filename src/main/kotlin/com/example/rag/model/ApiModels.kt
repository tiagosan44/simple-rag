package com.example.rag.model

import jakarta.validation.constraints.NotBlank

// Requests

data class AskRequest(
    @field:NotBlank(message = "question must not be blank")
    val question: String,
    val top_k: Int = 4,
    val temperature: Double = 0.0
)

data class EmbedRequest(
    @field:NotBlank(message = "text must not be blank")
    val text: String,
    val debug: Boolean = false
)

data class SearchRequest(
    @field:NotBlank(message = "query must not be blank")
    val query: String,
    val top_k: Int = 5
)

// Responses

data class SourceChunk(
    val id: String,
    val text: String,
    val score: Double,
    val chunk_index: Int? = null,
    val source: String? = null
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class AskResponse(
    val answer: String,
    val source_chunks: List<SourceChunk>,
    val raw_llm: String,
    val prompt: String,
    val latency_ms: Long,
    val model: String? = null,
    val usage: Usage? = null
)

data class EmbedResponse(
    val embedding_id: String,
    val vector_dim: Int,
    val vector: List<Float>? = null // present only when debug=true
)

data class SearchResult(
    val id: String,
    val text: String,
    val score: Double,
    val chunk_index: Int? = null,
    val source: String? = null
)

data class SearchResponse(
    val results: List<SearchResult>
)

// Error shape

data class ErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null,
    val trace_id: String? = null
)

data class ErrorResponse(
    val error: ErrorBody
)
