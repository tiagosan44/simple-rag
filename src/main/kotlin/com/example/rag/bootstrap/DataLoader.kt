package com.example.rag.bootstrap

import com.example.rag.model.QdrantPoint
import com.example.rag.service.EmbeddingService
import com.example.rag.service.VectorStoreService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class DataLoader(
    private val embeddingService: EmbeddingService,
    private val vectorStoreService: VectorStoreService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataLoader::class.java)

    data class KnowledgeItem(val id: String, val text: String)

    override fun run(args: ApplicationArguments?) {
        try {
            val resource = ClassPathResource("data/knowledge.json")
            if (!resource.exists()) {
                log.warn("knowledge.json not found; skipping ingestion")
                return
            }
            val mapper = jacksonObjectMapper()
            val items: List<KnowledgeItem> = resource.inputStream.use { mapper.readValue(it) }

            // Probe vector size using a small embedding
            val probe = embeddingService.embedText("dimension probe")
            val vectorSize = probe.vector.size
            vectorStoreService.initCollection(vectorSize)

            val points = mutableListOf<QdrantPoint>()
            var totalChunks = 0
            for (item in items) {
                val chunks = chunkText(item.text)
                for ((idx, chunk) in chunks.withIndex()) {
                    val emb = embeddingService.embedText(chunk)
                    val payload = mapOf(
                        "id" to (if (chunks.size == 1) item.id else "${item.id}-$idx"),
                        "original_text" to chunk,
                        "source" to "knowledge.json",
                        "chunk_index" to idx,
                        "created_at" to DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                        "model" to emb.model
                    )
                    val pointId = if (chunks.size == 1) item.id else "${item.id}-$idx"
                    points.add(QdrantPoint(id = pointId, vector = emb.vector, payload = payload))
                    totalChunks++
                }
            }

            if (points.isNotEmpty()) {
                // Upsert in batches to avoid huge payloads
                val batchSize = 64
                var i = 0
                while (i < points.size) {
                    val batch = points.subList(i, kotlin.math.min(i + batchSize, points.size))
                    vectorStoreService.upsert(batch)
                    i += batchSize
                }
            }
            log.info("Ingestion completed: {} documents -> {} chunks", items.size, totalChunks)
        } catch (e: Exception) {
            log.warn("Startup ingestion skipped due to error: {}", e.toString())
        }
    }

    private fun chunkText(text: String): List<String> {
        val hardLimit = 3000 // chars threshold to split
        if (text.length <= hardLimit) return listOf(text)
        // Sentence-aware simple splitter
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        for (s in sentences) {
            if (current.length + s.length + 1 > 900) {
                if (current.isNotEmpty()) chunks.add(current)
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks.map { it.toString() }.ifEmpty { listOf(text) }
    }
}
