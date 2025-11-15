package com.example.rag.error

import com.example.rag.model.ErrorBody
import com.example.rag.model.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import reactor.core.publisher.Mono
import java.util.*

@ControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        val details = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        val body = ErrorBody(
            code = ErrorCode.VALIDATION_ERROR.name,
            message = "Validation failed",
            details = details,
            trace_id = traceId
        )
        log.debug("Validation error trace_id={} details={}", traceId, details)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(body))
    }

    @ExceptionHandler(VectorStoreUnavailable::class)
    fun handleVectorStore(ex: VectorStoreUnavailable): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        val body = ErrorBody(
            code = ErrorCode.VECTOR_STORE_UNAVAILABLE.name,
            message = ex.message ?: "Vector store unavailable",
            details = ex.details,
            trace_id = traceId
        )
        log.warn("Vector store unavailable trace_id={} details={}", traceId, ex.details)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(body))
    }

    @ExceptionHandler(EmbeddingProviderUnavailable::class)
    fun handleEmbedding(ex: EmbeddingProviderUnavailable): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        val body = ErrorBody(
            code = ErrorCode.EMBEDDING_PROVIDER_UNAVAILABLE.name,
            message = ex.message ?: "Embedding provider unavailable",
            details = ex.details,
            trace_id = traceId
        )
        log.warn("Embedding provider unavailable trace_id={} details={}", traceId, ex.details)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(body))
    }

    @ExceptionHandler(LlmProviderUnavailable::class)
    fun handleLlm(ex: LlmProviderUnavailable): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        val body = ErrorBody(
            code = ErrorCode.LLM_PROVIDER_UNAVAILABLE.name,
            message = ex.message ?: "LLM provider unavailable",
            details = ex.details,
            trace_id = traceId
        )
        log.warn("LLM provider unavailable trace_id={} details={}", traceId, ex.details)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(body))
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(ex: Exception): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        val body = ErrorBody(
            code = ErrorCode.INTERNAL_ERROR.name,
            message = ex.message ?: "Internal error",
            details = null,
            trace_id = traceId
        )
        log.error("Unhandled exception trace_id={}", traceId, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse(body))
    }
}

class VectorStoreUnavailable(message: String, val details: Map<String, Any?>? = null) : RuntimeException(message)
class EmbeddingProviderUnavailable(message: String, val details: Map<String, Any?>? = null) : RuntimeException(message)
class LlmProviderUnavailable(message: String, val details: Map<String, Any?>? = null) : RuntimeException(message)
