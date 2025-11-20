package com.example.rag.model

/**
 * DTOs for OpenAI Chat Completions API
 */
internal data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Double = 0.0,
    val max_tokens: Int = 1024
)

internal data class OpenAIMessage(
    val role: String,
    val content: String
)

internal data class OpenAIChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAIChoice> = emptyList(),
    val usage: OpenAIUsage? = null
)

internal data class OpenAIChoice(
    val index: Int? = null,
    val message: OpenAIMessage? = null
)

internal data class OpenAIUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

/**
 * Internal result DTO for LLM operations
 */
internal data class LlmResult(
    val messageContent: String?,
    val raw: String?,
    val model: String?,
    val usage: Usage?
)

/**
 * DTOs for OpenAI Embeddings API
 */
internal data class OpenAIEmbeddingResponse(
    val data: List<EmbeddingItem> = emptyList(),
    val model: String? = null,
    val created: Long? = null
)

internal data class EmbeddingItem(
    val embedding: List<Double> = emptyList(),
    val index: Int? = null,
    val `object`: String? = null
)

