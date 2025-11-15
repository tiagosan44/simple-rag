package com.example.rag.service

import com.example.rag.config.OpenAIProps
import com.example.rag.model.EmbeddingResult
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
    private val openAIProps: OpenAIProps
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

        // Placeholder: hash-based deterministic embedding fallback
        val vector = hashEmbedding(text, 1536)
        val res = EmbeddingResult(
            id = generateId(text),
            vector = vector,
            model = openAIProps.embeddingModel,
            createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))
        )
        synchronized(cache) { cache[text] = now to res }
        return res
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
