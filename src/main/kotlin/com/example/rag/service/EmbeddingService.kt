package com.example.rag.service

import com.example.rag.config.OpenAIProps
import com.example.rag.model.EmbeddingResult
import com.example.rag.util.defaultRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs

interface EmbeddingService {
    fun embedText(text: String): EmbeddingResult
}

@Service
class EmbeddingServiceImpl(
    private val openAIProps: OpenAIProps,
    private val webClient: org.springframework.web.reactive.function.client.WebClient
) : EmbeddingService {
    private val log = LoggerFactory.getLogger(EmbeddingServiceImpl::class.java)

    // Simple synchronized LRU with TTL
    private val capacity = 1000
    private val ttlMillis = 60 * 60 * 1000L
    private val cache = object : LinkedHashMap<String, Pair<Long, EmbeddingResult>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, EmbeddingResult>>?): Boolean {
            return size > capacity
        }
    }

    override fun embedText(text: String): EmbeddingResult {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[text]?.let { (ts, res) ->
                if (now - ts < ttlMillis) return res
                else cache.remove(text)
            }
        }

        // Try OpenAI embeddings first if apiKey is set; fallback to hash embedding on failure
        return try {
            if (openAIProps.apiKey.isBlank()) {
                log.debug("OPENAI_API_KEY not set, using fallback embedding")
                fallbackEmbedding(text, now)
            } else {
                val result = callOpenAIEmbedding(text)
                synchronized(cache) { cache[text] = now to result }
                result
            }
        } catch (e: Exception) {
            log.warn("OpenAI embedding failed, using fallback. cause={}", e.toString())
            val res = fallbackEmbedding(text, now)
            synchronized(cache) { cache[text] = now to res }
            res
        }
    }

    private fun fallbackEmbedding(text: String, nowMillis: Long): EmbeddingResult {
        val vector = hashEmbedding(text, 1536)
        return EmbeddingResult(
            id = generateId(text),
            vector = vector,
            model = openAIProps.embeddingModel,
            createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMillis))
        )
    }

    private fun callOpenAIEmbedding(text: String): EmbeddingResult {
        val req = mapOf(
            "model" to openAIProps.embeddingModel,
            "input" to text
        )
        val start = System.currentTimeMillis()
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
        val now = if (resp.created != null) resp.created!! * 1000L else start
        return EmbeddingResult(
            id = generateId(text),
            vector = vector,
            model = resp.model ?: openAIProps.embeddingModel,
            createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))
        )
    }

    private fun generateId(text: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return md.take(6).joinToString("") { String.format("%02x", it) }
    }

    private fun hashEmbedding(text: String, dim: Int): FloatArray {
        val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val arr = FloatArray(dim)
        for (i in 0 until dim) {
            val b = md[i % md.size]
            val v = ((b.toInt() and 0xFF) - 128) / 128.0f
            arr[i] = v
        }
        // L2 normalize
        var sum = 0.0
        for (v in arr) sum += (v * v)
        val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-6f)
        for (i in arr.indices) arr[i] = arr[i] / norm
        return arr
    }
}


// --- OpenAI DTOs for Embeddings ---
private data class OpenAIEmbeddingResponse(
    val data: List<EmbeddingItem> = emptyList(),
    val model: String? = null,
    val created: Long? = null
)

private data class EmbeddingItem(
    val embedding: List<Double> = emptyList(),
    val index: Int? = null,
    val `object`: String? = null
)
