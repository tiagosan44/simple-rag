package com.example.rag.service

import com.example.rag.config.OpenAIProps
import com.example.rag.model.AskResponse
import com.example.rag.model.SourceChunk
import org.springframework.stereotype.Service

interface RagService {
    fun answer(question: String, topK: Int, temperature: Double): AskResponse
}

@Service
class RagServiceImpl(
    private val embeddingService: EmbeddingService,
    private val vectorStoreService: VectorStoreService,
    private val openAIProps: OpenAIProps,
    private val webClient: org.springframework.web.reactive.function.client.WebClient
) : RagService {

    override fun answer(question: String, topK: Int, temperature: Double): AskResponse {
        val start = System.currentTimeMillis()
        val q = embeddingService.embedText(question)
        val chunks = vectorStoreService.search(q.vector, topK)

        val prompt = buildPrompt(question, chunks.map { it.text })

        val llm = callOpenAIChat(prompt, temperature)
        val answer = llm.messageContent ?: run {
            if (chunks.isNotEmpty()) {
                val inlineCitations = chunks.take(3).joinToString(" ") { "[${it.id}]" }
                "Based on the provided context, here is a concise answer. $inlineCitations\n\nSources:\n" +
                        chunks.joinToString("\n") { s -> "- ${s.id} (${"%.2f".format(s.score)}): ${s.text.take(100)}" }
            } else {
                "I don't know."
            }
        }

        val sourceChunks = chunks.map { c ->
            SourceChunk(id = c.id, text = c.text, score = c.score, chunk_index = c.chunkIndex, source = c.source)
        }

        val end = System.currentTimeMillis()
        return AskResponse(
            answer = answer,
            source_chunks = sourceChunks,
            raw_llm = llm.raw ?: "",
            prompt = prompt,
            latency_ms = end - start,
            model = llm.model ?: openAIProps.llmModel,
            usage = llm.usage
        )
    }

    private fun buildPrompt(question: String, chunks: List<String>): String {
        val sb = StringBuilder()
        sb.append("You are an assistant. Use only the following context. If answer unknown, say \"I don't know\".\n")
        sb.append("Context:\n---\n")
        chunks.forEach { sb.append(it).append("\n") }
        sb.append("---\n")
        sb.append("Question: ").append(question).append("\n")
        sb.append("Provide concise answer and cite source ids.\n")
        return sb.toString()
    }

    private fun callOpenAIChat(prompt: String, temperature: Double): LlmResult {
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
                .retryWhen(com.example.rag.util.defaultRetryPolicy())
                .block()
            val raw = resp?.let { com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(it) }
            val content = resp?.choices?.firstOrNull()?.message?.content
            val usage = resp?.usage?.let { com.example.rag.model.Usage(it.prompt_tokens ?: 0, it.completion_tokens ?: 0, it.total_tokens ?: 0) }
            val model = resp?.model
            LlmResult(messageContent = content, raw = raw, model = model, usage = usage)
        } catch (e: org.springframework.web.reactive.function.client.WebClientResponseException) {
            throw com.example.rag.error.LlmProviderUnavailable(
                "LLM provider returned ${e.statusCode.value()} ${e.statusText}",
                mapOf("status" to e.statusCode.value(), "body" to e.responseBodyAsString.take(500))
            )
        } catch (e: org.springframework.web.reactive.function.client.WebClientRequestException) {
            throw com.example.rag.error.LlmProviderUnavailable(
                "LLM provider request failed: ${e.rootCause?.javaClass?.simpleName ?: e.javaClass.simpleName}",
                mapOf("cause" to (e.rootCause?.message ?: e.message))
            )
        } catch (e: Exception) {
            throw com.example.rag.error.LlmProviderUnavailable("LLM provider error", mapOf("error" to e.toString()))
        }
    }
}

// --- OpenAI Chat DTOs & helper ---
private data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Double = 0.0,
    val max_tokens: Int = 1024
)
private data class OpenAIMessage(val role: String, val content: String)
private data class OpenAIChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAIChoice> = emptyList(),
    val usage: OpenAIUsage? = null
)
private data class OpenAIChoice(val index: Int? = null, val message: OpenAIMessage? = null)
private data class OpenAIUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)
private data class LlmResult(
    val messageContent: String?,
    val raw: String?,
    val model: String?,
    val usage: com.example.rag.model.Usage?
)
