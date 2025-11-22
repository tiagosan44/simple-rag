package com.example.rag.service

import com.example.rag.config.OpenAIProps
import com.example.rag.error.EmbeddingProviderUnavailable
import com.example.rag.error.LlmProviderUnavailable
import com.example.rag.model.*
import com.example.rag.util.defaultRetryPolicy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Client for OpenAI API calls
 * Separates HTTP communication logic from business logic
 */
@Component
class OpenAIClient(
    private val openAIProps: OpenAIProps,
    private val webClient: WebClient
) {
    private val log = LoggerFactory.getLogger(OpenAIClient::class.java)
    /**
     * Calls OpenAI Chat Completions API
     */
    fun callOpenAIChat(prompt: String, temperature: Double): LlmResult {
        if (openAIProps.apiKey.isBlank()) {
            return LlmResult(messageContent = null, raw = null, model = openAIProps.llmModel, usage = null)
        }
        val req = OpenAIChatRequest(
            model = openAIProps.llmModel,
            temperature = temperature,
            max_tokens = openAIProps.maxTokens,
            messages = listOf(OpenAIMessage(role = "user", content = prompt))
        )
        return try {
            val resp = webClient.post()
                .uri("${openAIProps.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${openAIProps.apiKey}")
                .header("Content-Type", "application/json")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(OpenAIChatResponse::class.java)
                .retryWhen(defaultRetryPolicy())
                .block()
            val raw = resp?.let { jacksonObjectMapper().writeValueAsString(it) }
            val content = resp?.choices?.firstOrNull()?.message?.content
            val usage = resp?.usage?.let { Usage(it.prompt_tokens ?: 0, it.completion_tokens ?: 0, it.total_tokens ?: 0) }
            val model = resp?.model
            LlmResult(messageContent = content, raw = raw, model = model, usage = usage)
        } catch (e: WebClientResponseException) {
            log.error("OpenAI chat failed: status={}", e.statusCode.value())
            throw LlmProviderUnavailable(
                "LLM provider returned ${e.statusCode.value()} ${e.statusText}",
                mapOf("status" to e.statusCode.value(), "body" to e.responseBodyAsString.take(200))
            )
        } catch (e: WebClientRequestException) {
            log.error("OpenAI chat request failed", e)
            throw LlmProviderUnavailable(
                "LLM provider request failed: ${e.rootCause?.javaClass?.simpleName ?: e.javaClass.simpleName}",
                mapOf("cause" to (e.rootCause?.message ?: e.message))
            )
        } catch (e: Exception) {
            log.error("OpenAI chat failed", e)
            throw LlmProviderUnavailable("LLM provider error", mapOf("cause" to e.message))
        }
    }

    /**
     * Calls OpenAI Embeddings API
     */
    fun callOpenAIEmbedding(text: String): EmbeddingResult {
        val req = mapOf(
            "model" to openAIProps.embeddingModel,
            "input" to text
        )
        val start = System.currentTimeMillis()
        return try {
            val resp = webClient.post()
                .uri("${openAIProps.baseUrl}/embeddings")
                .header("Authorization", "Bearer ${openAIProps.apiKey}")
                .header("Content-Type", "application/json")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(OpenAIEmbeddingResponse::class.java)
                .retryWhen(defaultRetryPolicy())
                .block() ?: throw RuntimeException("Empty response from OpenAI embeddings")

            if (resp.data.isEmpty()) throw RuntimeException("No embedding data")
            val vector = resp.data[0].embedding.map { it.toFloat() }.toFloatArray()
            val now = if (resp.created != null) resp.created * 1000L else start
            EmbeddingResult(
                id = generateId(text),
                vector = vector,
                model = resp.model ?: openAIProps.embeddingModel,
                createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))
            )
        } catch (e: WebClientResponseException) {
            log.error("OpenAI embedding failed: status={}", e.statusCode.value())
            throw EmbeddingProviderUnavailable(
                "Embedding provider returned ${e.statusCode.value()} ${e.statusText}",
                mapOf("status" to e.statusCode.value(), "body" to e.responseBodyAsString.take(200))
            )
        } catch (e: WebClientRequestException) {
            log.error("OpenAI embedding request failed", e)
            throw EmbeddingProviderUnavailable(
                "Embedding provider request failed: ${e.rootCause?.javaClass?.simpleName ?: e.javaClass.simpleName}",
                mapOf("cause" to (e.rootCause?.message ?: e.message))
            )
        } catch (e: Exception) {
            log.error("OpenAI embedding failed", e)
            throw EmbeddingProviderUnavailable("Embedding provider error", mapOf("cause" to e.message))
        }
    }

    private fun generateId(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return md.take(6).joinToString("") { String.format("%02x", it) }
    }
}

