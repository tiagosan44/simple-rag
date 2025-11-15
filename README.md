# RAG Backend (Kotlin + Spring Boot WebFlux)

A lightweight Retrieval-Augmented Generation (RAG) backend written in Kotlin (JDK 17) using Spring Boot 3.x WebFlux. It exposes three HTTP endpoints — `/api/embed`, `/api/search`, and `/api/ask` — and integrates with an embedding provider (OpenAI by default) and a vector database (Qdrant). The repo includes Docker assets, a small demo knowledge base, security/CORS toggles, and CI.

This README is intentionally detailed to help you run the demo in under 10 minutes and understand how to configure/extend it.

---

## Table of contents
- What you get
- Architecture overview
- Quick start (10 minutes)
- Running locally (dev workflow)
- Configuration (application.yml + env vars)
- Security and CORS
- API reference (with examples)
- Data ingestion (knowledge.json)
- Docker and docker-compose
- Makefile shortcuts
- Testing & CI
- Troubleshooting
- Roadmap and notes

---

## What you get
- Kotlin + Spring Boot 3.x (WebFlux)
- Endpoints:
  - POST `/api/embed` — get embeddings for text
  - POST `/api/search` — similarity search over vector store
  - POST `/api/ask` — retrieve context + LLM answer, with citations and prompt returned
- Pluggable providers: OpenAI (default). Anthropic can be added later.
- Vector store: Qdrant via REST. A minimal in-memory store is available as a temporary stub so you can run the app without Qdrant during early dev.
- Startup configuration via `application.yml` with environment overrides.
- Security toggle (API Key + BasicAuth) and CORS config.
- Dockerfile, docker-compose (Qdrant), Makefile, GitHub Actions CI.
- A small demo dataset at `data/knowledge.json`.

> Status: OpenAI and Qdrant full integrations are planned per `rag-backend.md`. The current code runs with an in-memory vector store and a placeholder embedding implementation that is deterministic and cacheable. This lets you run the app and endpoints immediately while integrations are completed.

---

## Architecture overview

```
Client (frontend or curl)
        |
        v
   Spring Boot WebFlux (Kotlin)
        |
        |-- EmbeddingService  ---->  Embedding Provider (OpenAI)
        |
        |-- VectorStoreService ---->  Qdrant (vector DB via REST)
        |
        '-- RagService         ---->  LLM (OpenAI Chat Completions)
```

Key behavior for `/api/ask`:
1. Embed the user question.
2. Search top-k similar chunks in the vector store.
3. Build a prompt containing retrieved chunks.
4. Call the LLM to produce an answer (with inline `[doc-id]` citations + trailing Sources list).
5. Return `answer`, `source_chunks`, `prompt`, `raw_llm`, latency, and when available `model` and `usage`.

---

## Quick start (10 minutes)

Prerequisites:
- JDK 17 (Temurin recommended)
- Docker (for Qdrant)

Steps:
1) Start Qdrant (optional for first run — in-memory stub is available):
```
make up
```
2) Run the backend:
```
make dev
```
3) Test an endpoint:
```
curl -s localhost:8080/api/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is our refund policy?","top_k":4}' | jq
```

If you want to try the embed and search endpoints as well, see API reference below.

Stop services:
```
make down
```

---

## Running locally (dev workflow)

- Build
```
./gradlew clean build
```
- Run (dev)
```
./gradlew bootRun
```
- Run tests
```
./gradlew test
```
- Package Docker image
```
make docker
```
- Run the container (assumes Qdrant on host)
```
make docker-run
```

---

## Configuration

All settings live in `src/main/resources/application.yml` and can be overridden via environment variables.

Key sections (defaults shown):

```
server:
  port: ${SERVER_PORT:8080}

rag:
  embeddingProvider: ${RAG_EMBEDDING_PROVIDER:openai}
  openai:
    apiKey: ${OPENAI_API_KEY:}
    embeddingModel: ${RAG_OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
    llmModel: ${RAG_OPENAI_LLM_MODEL:gpt-4o-mini}
    temperature: ${RAG_TEMPERATURE:0.0}
    maxTokens: ${RAG_MAX_TOKENS:1024}
  anthropic:
    apiKey: ${ANTHROPIC_API_KEY:}
    embeddingModel: ${RAG_ANTHROPIC_EMBEDDING_MODEL:claude-embed-1}
    llmModel: ${RAG_ANTHROPIC_LLM_MODEL:claude-2}

qdrant:
  url: ${QDRANT_URL:http://localhost:6333}
  collection: ${RAG_COLLECTION:rag_demo}

features:
  authEnabled: ${RAG_AUTH_ENABLED:false}
  debug: ${RAG_DEBUG:false}
  forceRecreate: ${FORCE_RECREATE:false}

security:
  apiKey: ${RAG_API_KEY:}
  basic:
    user: ${RAG_BASIC_USER:}
    pass: ${RAG_BASIC_PASS:}

cors:
  allowedOrigins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
```

Important env vars:
- `OPENAI_API_KEY` — required when real OpenAI calls are enabled
- `QDRANT_URL` — Qdrant REST endpoint (default `http://localhost:6333`)
- `RAG_COLLECTION` — collection name (default `rag_demo`)
- `RAG_AUTH_ENABLED` — enable security (false by default)
- `RAG_API_KEY` — API key for header `X-API-Key` when security is enabled
- `RAG_BASIC_USER` / `RAG_BASIC_PASS` — optional BasicAuth credentials when security is enabled
- `CORS_ALLOWED_ORIGINS` — comma-separated list for allowed origins (default `http://localhost:5173`)

Timeouts: connect=3s, read=10s. Retries: 3 with exponential backoff (configurable in code; see `HttpClientFactory.kt`).

---

## Security and CORS

- Security uses Spring WebFlux Security and is disabled by default (`RAG_AUTH_ENABLED=false`).
- When enabled:
  - API key via header `X-API-Key: <RAG_API_KEY>`
  - Optional BasicAuth with `RAG_BASIC_USER`/`RAG_BASIC_PASS`
  - `/api/**` and `/actuator/**` require authentication
- CORS is configured via `CORS_ALLOWED_ORIGINS` (comma-separated). Do not use `*` in production.

---

## API reference

Base URL: `http://localhost:8080/api`

### 1) POST /api/embed
Request:
```
{ "text": "hello world", "debug": false }
```
Response (default):
```
{ "embedding_id": "abc123", "vector_dim": 1536 }
```
Response when `debug=true` (includes vector):
```
{ "embedding_id": "abc123", "vector_dim": 1536, "vector": [0.01, -0.03, ...] }
```
Curl:
```
curl -s localhost:8080/api/embed -H 'Content-Type: application/json' \
  -d '{"text":"hello world","debug":true}' | jq
```

### 2) POST /api/search
Request:
```
{ "query": "refund policy", "top_k": 5 }
```
Response:
```
{
  "results": [
    { "id": "doc-4", "text": "Refunds are issued within 5–7 business days after review.", "score": 0.92, "chunk_index": null, "source": null }
  ]
}
```
Curl:
```
curl -s localhost:8080/api/search -H 'Content-Type: application/json' \
  -d '{"query":"refund policy","top_k":5}' | jq
```

### 3) POST /api/ask
Request:
```
{ "question": "What is our refund policy?", "top_k": 4, "temperature": 0.0 }
```
Response (shape):
```
{
  "answer": "... [doc-4] ...\n\nSources:\n- doc-4 (0.92): Refunds are issued within 5–7 business days after review.",
  "source_chunks": [{ "id": "doc-4", "text": "...", "score": 0.92, "chunk_index": null, "source": null }],
  "raw_llm": "placeholder",
  "prompt": "You are an assistant...",
  "latency_ms": 123,
  "model": "gpt-4o-mini",
  "usage": null
}
```
Curl:
```
curl -s localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"question":"What is our refund policy?","top_k":4}' | jq
```

Error schema (canonical):
```
{
  "error": {
    "code": "VECTOR_STORE_UNAVAILABLE",
    "message": "Qdrant is unreachable at http://localhost:6333",
    "details": { "attempts": 3, "cause": "connect_timeout" },
    "trace_id": "..."
  }
}
```

---

## Data ingestion (knowledge.json)

A small demo dataset is provided at `data/knowledge.json` with 10 entries. The end-to-end ingestion at startup (embedding + upsert into Qdrant) will be wired as part of the Qdrant/OpenAI integration. For now, the in-memory vector store can be populated programmatically during tests or future startup code.

Planned behavior:
- If an entry exceeds ~800 tokens or ~3000 characters, it will be split into chunks (`id-0`, `id-1`, ...).
- Upserts will include payload fields: `id`, `original_text`, `source: "knowledge.json"`, `chunk_index`, `created_at` (ISO), `model` (embedding model).
- Cosine similarity with normalized scores 0..1.

---

## Docker and docker-compose

- Multi-stage `Dockerfile` builds a runnable JAR on Temurin JRE 17.
- `docker-compose.yml` includes Qdrant with a healthcheck. The app service is provided as commented example.

Build image:
```
make docker
```
Run image (macOS/Windows with Docker Desktop):
```
make docker-run
```
Start only Qdrant for local dev:
```
make up
```
Stop:
```
make down
```

---

## Makefile shortcuts

- `make dev` — run Spring Boot locally (`bootRun`)
- `make build` — clean build
- `make test` — run tests
- `make run` — run built JAR
- `make up` — start Qdrant via docker-compose
- `make down` — stop compose
- `make docker` — build Docker image
- `make docker-run` — run Docker image (binds 8080)

---

## Testing & CI

- Unit tests live under `src/test/kotlin`. Current example: `EmbeddingServiceImplTest` verifies deterministic, normalized embeddings from the placeholder implementation.
- Additional tests planned:
  - WireMock tests for OpenAI embeddings and Qdrant REST client
  - Integration tests for `/api/ask` with mocked external services, and optional Testcontainers Qdrant for local debugging

CI:
- GitHub Actions workflow at `.github/workflows/ci.yml` runs build and tests on pushes and PRs.

Run tests locally:
```
./gradlew test
```

---

## Troubleshooting

- Port already in use (8080):
  - Change `SERVER_PORT` env or modify `application.yml`.
- CORS blocked:
  - Set `CORS_ALLOWED_ORIGINS` to include your frontend origin(s), e.g. `http://localhost:5173,http://localhost:3000`.
- 401/403 errors with security enabled:
  - Ensure `RAG_AUTH_ENABLED=true` and provide either `X-API-Key: <RAG_API_KEY>` or BasicAuth credentials (`RAG_BASIC_USER`/`RAG_BASIC_PASS`).
- Qdrant not reachable:
  - Start Qdrant via `make up` or adjust `QDRANT_URL`. Check `docker ps` and Qdrant health `http://localhost:6333/readyz`.
- OpenAI errors:
  - Ensure `OPENAI_API_KEY` is set. Check network egress. Verify model names in `application.yml`.
- Slow responses:
  - Tune retries/timeouts in `HttpClientFactory.kt`. Reduce `top_k` or prompt size.

Logs:
- Root logger is `INFO` by default. Set `RAG_DEBUG=true` (if wired) or `logging.level.com.example.rag=DEBUG` for more logs.

---

## Roadmap and notes

- Implement OpenAI Embeddings API and Chat Completions integration with usage reporting.
- Implement Qdrant REST client: collection init/validate, upsert, search, getById, and `FORCE_RECREATE` behavior on vector size mismatch.
- Startup loader to embed and upsert `data/knowledge.json` into Qdrant, with chunking for long entries.
- Add `/api/debug` gated by `RAG_DEBUG=true` or auth to return retrieved chunks, prompt, and raw LLM response.
- Provide a Postman collection (embed/search/ask) in `postman/`.
- Expand tests: WireMock for provider clients, integration tests, and optional Testcontainers.

---

## License

This project is provided as-is for demo and educational purposes. Add your preferred license if distributing.
