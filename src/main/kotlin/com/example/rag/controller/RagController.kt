package com.example.rag.controller

import com.example.rag.model.*
import com.example.rag.service.EmbeddingService
import com.example.rag.service.RagService
import com.example.rag.service.VectorStoreService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@RestController
@RequestMapping("/api")
class RagController(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStoreService,
    private val ragService: RagService
) {

    @PostMapping("/embed")
    fun embed(@Valid @RequestBody req: EmbedRequest): Mono<ResponseEntity<EmbedResponse>> {
        return Mono.fromCallable {
            val res = embeddingService.embedText(req.text)
            val response = EmbedResponse(
                embedding_id = res.id,
                vector_dim = res.vector.size,
                vector = if (req.debug) res.vector.toList() else null
            )
            ResponseEntity.ok(response)
        }.subscribeOn(Schedulers.boundedElastic())
    }

    @PostMapping("/search")
    fun search(@Valid @RequestBody req: SearchRequest): Mono<ResponseEntity<SearchResponse>> {
        return Mono.fromCallable {
            val query = embeddingService.embedText(req.query)
            val results = vectorStore.search(query.vector, req.top_k).map {
                SearchResult(
                    id = it.id,
                    text = it.text,
                    score = it.score,
                    chunk_index = it.chunkIndex,
                    source = it.source
                )
            }
            ResponseEntity.ok(SearchResponse(results))
        }.subscribeOn(Schedulers.boundedElastic())
    }

    @PostMapping("/ask")
    fun ask(@Valid @RequestBody req: AskRequest): Mono<ResponseEntity<AskResponse>> {
        return Mono.fromCallable {
            val resp = ragService.answer(req.question, req.top_k, req.temperature)
            ResponseEntity.ok(resp)
        }.subscribeOn(Schedulers.boundedElastic())
    }
}
