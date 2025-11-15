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
    private val openAIProps: OpenAIProps
) : RagService {

    override fun answer(question: String, topK: Int, temperature: Double): AskResponse {
        val start = System.currentTimeMillis()
        val q = embeddingService.embedText(question)
        val chunks = vectorStoreService.search(q.vector, topK)

        val prompt = buildPrompt(question, chunks.map { it.text })

        // Placeholder LLM call: until OpenAI Messages API integration is added, we synthesize an answer
        val inlineCitations = chunks.take(3).joinToString(" ") { "[${it.id}]" }
        val answer = if (chunks.isNotEmpty()) {
            "Based on the provided context, here is a concise answer. $inlineCitations\n\nSources:\n" +
                    chunks.joinToString("\n") { s -> "- ${s.id} (${"%.2f".format(s.score)}): ${s.text.take(100)}" }
        } else {
            "I don't know."
        }

        val sourceChunks = chunks.map { c ->
            SourceChunk(id = c.id, text = c.text, score = c.score, chunk_index = c.chunkIndex, source = c.source)
        }

        val end = System.currentTimeMillis()
        return AskResponse(
            answer = answer,
            source_chunks = sourceChunks,
            raw_llm = "placeholder",
            prompt = prompt,
            latency_ms = end - start,
            model = openAIProps.llmModel,
            usage = null
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
}
