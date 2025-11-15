package com.example.rag.service

import com.example.rag.model.QdrantPoint
import com.example.rag.model.RetrievedChunk
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

interface VectorStoreService {
    fun initCollection(vectorSize: Int)
    fun upsert(points: List<QdrantPoint>)
    fun search(vector: FloatArray, topK: Int): List<RetrievedChunk>
    fun getById(id: String): RetrievedChunk?
}

// Temporary in-memory stub to allow the app to run before Qdrant integration is added.
@Service
class InMemoryVectorStoreService : VectorStoreService {
    private val store = ConcurrentHashMap<String, QdrantPoint>()

    override fun initCollection(vectorSize: Int) { /* no-op for in-memory */ }

    override fun upsert(points: List<QdrantPoint>) {
        points.forEach { store[it.id] = it }
    }

    override fun search(vector: FloatArray, topK: Int): List<RetrievedChunk> {
        if (store.isEmpty()) return emptyList()
        // naive cosine similarity against all points
        val qNorm = l2Norm(vector)
        return store.values.asSequence()
            .map { p ->
                val sim = cosine(vector, qNorm, p.vector, l2Norm(p.vector))
                val id = p.id
                val text = (p.payload["original_text"] as? String) ?: ""
                val chunkIndex = (p.payload["chunk_index"] as? Number)?.toInt()
                val source = p.payload["source"] as? String
                RetrievedChunk(id, text, ((sim + 1.0) / 2.0).coerceIn(0.0, 1.0), chunkIndex, source)
            }
            .sortedByDescending { it.score }
            .take(max(1, topK))
            .toList()
    }

    override fun getById(id: String): RetrievedChunk? {
        val p = store[id] ?: return null
        val text = (p.payload["original_text"] as? String) ?: ""
        val chunkIndex = (p.payload["chunk_index"] as? Number)?.toInt()
        val source = p.payload["source"] as? String
        return RetrievedChunk(p.id, text, 1.0, chunkIndex, source)
    }

    private fun l2Norm(v: FloatArray): Double {
        var s = 0.0
        for (x in v) s += x * x
        return kotlin.math.sqrt(s)
    }

    private fun cosine(a: FloatArray, aNorm: Double, b: FloatArray, bNorm: Double): Double {
        var dot = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        val denom = (aNorm * bNorm).coerceAtLeast(1e-6)
        return dot / denom
    }
}
