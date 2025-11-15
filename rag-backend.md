# backend.md

## Project Overview

This markdown file contains detailed, implementable instructions for the **backend** of the RAG demo app. The backend must be implemented in **Kotlin** using **Spring Boot** and should provide endpoints for embedding, searching, and answering (`/api/embed`, `/api/search`, `/api/ask`) as defined in the frontend contract. The file includes full technical details, dependencies, configuration, local dev, Docker, vector store setup, and sample data. It is written so an AI agent in an IDE can generate the project and run it.

---

## Business Goals

- Provide a reliable backend that demonstrates a full RAG pipeline.
- Keep the system lightweight: use a small vector DB (Qdrant) running locally via Docker.
- Use an embedding provider (OpenAI or Claude) configurable via environment variables.
- Provide clear logs and a debug endpoint that returns the prompt and retrieved chunks.
- Keep costs low: the knowledge base is a small static JSON included in the repo (10–20 chunks).

---

## Tech Stack

- Language: Kotlin (JDK 17 or 21)
- Framework: Spring Boot 3.x (Kotlin DSL / Gradle Kotlin build)
- HTTP: Spring WebFlux is acceptable, but Spring MVC is fine (synchronous). Use Spring MVC for simplicity.
- Vector store: Qdrant running in Docker (accessible via HTTP). Use Qdrant's REST API.
- Embeddings/LLM: OpenAI embeddings (text-embedding-3-small / text-embedding-3-large) or Anthropic Claude embeddings if preferred. LLM generation via OpenAI ChatCompletion or Claude API.
- JSON parsing: Jackson Kotlin module
- Docker: Dockerfile for the app and docker-compose to spin up Qdrant

---

## Project Structure (exact)

```
backend/
├─ src/main/kotlin/com/example/rag/
│  ├─ Application.kt
│  ├─ config/
│  │   ├─ AppConfig.kt
│  │   └─ OpenAIConfig.kt
│  ├─ controller/
│  │   └─ RagController.kt
│  ├─ service/
│  │   ├─ EmbeddingService.kt
│  │   ├─ VectorStoreService.kt
│  │   └─ RagService.kt
│  ├─ model/
│  │   ├─ ApiModels.kt
│  │   └─ DomainModels.kt
│  └─ util/
│      └─ HttpClientFactory.kt
├─ src/main/resources/
│  └─ application.yml
├─ data/
│  └─ knowledge.json
├─ build.gradle.kts
├─ settings.gradle.kts
├─ Dockerfile
└─ docker-compose.yml
```

---

## Required Dependencies (Gradle Kotlin DSL)

Add the following dependencies to `build.gradle.kts`:

```kotlin
plugins {
  id("org.springframework.boot") version "3.3.1"
  id("io.spring.dependency-management") version "1.1.0"
  kotlin("jvm") version "1.9.21"
  kotlin("plugin.spring") version "1.9.21"
}

java { sourceCompatibility = JavaVersion.VERSION_17 }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-logging")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-webflux") // optional
  implementation("org.springframework.boot:spring-boot-starter-security") // optional if auth
  implementation("org.slf4j:slf4j-api:2.0.7")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

---

## Configuration (application.yml)

Provide a sample `application.yml` which the AI agent can copy and adapt.

```yaml
server:
  port: 8080

rag:
  embeddingProvider: openai # or anthropic
  openai:
    apiKey: ${OPENAI_API_KEY:}
    embeddingModel: text-embedding-3-small
    llmModel: gpt-4o-mini # optional
  anthropic:
    apiKey: ${ANTHROPIC_API_KEY:}
    embeddingModel: claude-embed-1
    llmModel: claude-2 # optional

qdrant:
  url: http://localhost:6333
  collection: rag_demo

logging:
  level:
    root: INFO
    com.example.rag: DEBUG
```

---

## Docker Compose (Qdrant + App)

Provide a `docker-compose.yml` that starts Qdrant and the Kotlin app (the Kotlin app will be built by the developer and run separately in dev; compose primarily helps start Qdrant):

```yaml
version: '3.8'
services:
  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"
    volumes:
      - qdrant_storage:/qdrant/storage

volumes:
  qdrant_storage:
```

---

## knowledge.json (example)

Place this file under `/data/knowledge.json`. This is the small static knowledge base used by the demo (10–20 entries). The AI should create it automatically if missing.

```json
[
  { "id": "doc-1", "text": "Orders are processed within 24 hours of payment confirmation." },
  { "id": "doc-2", "text": "Premium customers have priority support and a dedicated manager." },
  { "id": "doc-3", "text": "We use eventual consistency for order status across microservices." },
  { "id": "doc-4", "text": "Refunds are issued within 5–7 business days after review." }
]
```

---

## Service Responsibilities

### EmbeddingService.kt
- Responsibilities:
  - Given a text, call the configured embedding provider API (OpenAI or Anthropic).
  - Return the float[] vector and metadata (length, model used).
  - Provide caching for identical texts (in-memory LRU cache recommended).
- Important: include exponential backoff retry logic for API calls and circuit-breaker style fallback to an offline embedding (e.g., hash-based placeholder) if provider unreachable.

### VectorStoreService.kt
- Responsibilities:
  - Interact with Qdrant HTTP API to create collection, upload vectors and metadata, search by vector, and fetch points by id.
  - Methods: `initCollection()`, `upsert(points)`, `search(vector, topK)`, `getById(id)`.
  - Map metadata: id, original_text, source (knowledge.json), chunk_index.

### RagService.kt
- Responsibilities:
  - Accept `question`, `top_k`, `temperature`.
  - Use EmbeddingService to create query vector.
  - Use VectorStoreService to retrieve top_k chunks.
  - Build the prompt template:
    ```text
    You are an assistant. Use only the following context. If answer unknown, say "I don't know".
    Context:
    ---
    {chunk1}
    {chunk2}
    ---
    Question: {user_question}
    Provide concise answer and cite source ids.
    ```
  - Call LLM API to generate answer, include `raw_llm` and `prompt` in response.
  - Return object with: answer, source_chunks, raw_llm, prompt, latency_ms.

---

## HTTP Controller (RagController.kt) — Endpoint Details

1. `POST /api/embed`
   - Request: `{ "text": "..." }`
   - Response: `{ "embedding_id": "doc-1-0", "vector_dim": 1536 }`

2. `POST /api/search`
   - Request: `{ "query": "...", "top_k": 5 }`
   - Response: `{ "results": [{ "id":"doc-1","text":"...","score":0.92 }] }`

3. `POST /api/ask`
   - Request: `{ "question": "...", "top_k": 4 }`
   - Response: as defined in frontend API Contract.

---

## Sample Kotlin snippets (pseudocode with exact types)

**Application.kt**

```kotlin
package com.example.rag

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

fun main(args: Array<String>) {
  runApplication<Application>(*args)
}
```

**ApiModels.kt**

```kotlin
package com.example.rag.model

data class AskRequest(val question: String, val top_k: Int = 4, val temperature: Double = 0.0)

data class SourceChunk(val id: String, val text: String, val score: Double)

data class AskResponse(
  val answer: String,
  val source_chunks: List<SourceChunk>,
  val raw_llm: String,
  val prompt: String,
  val latency_ms: Long
)
```

**RagController.kt** (sketch)

```kotlin
@RestController
@RequestMapping("/api")
class RagController(val ragService: RagService) {

  @PostMapping("/ask")
  fun ask(@RequestBody req: AskRequest): ResponseEntity<AskResponse> {
    val start = System.currentTimeMillis()
    val resp = ragService.answer(req.question, req.top_k, req.temperature)
    return ResponseEntity.ok(resp)
  }
}
```

---

## Error handling & Resilience

- All external calls (embedding API, Qdrant, LLM) must have timeouts and retry policies. Use Spring `WebClient` with `retryWhen` or `Resilience4j` for circuit breaker patterns.
- If the vector store is down, return a friendly error: `503 Service Unavailable` and include an explanation in the JSON.

---

## Tests

- Unit tests for `EmbeddingService` using mock HTTP server (WireMock). Validate embedding vector shape and caching.
- Unit tests for `VectorStoreService` using a small Qdrant test instance or mocked responses.
- Integration tests for `/api/ask` using a stubbed embedding provider and a prefilled Qdrant collection.

---

## CI/CD (GitHub Actions sample)

Create `.github/workflows/ci.yml`:

```yaml
name: CI
on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Build
        run: ./gradlew build
      - name: Run tests
        run: ./gradlew test
```

---

## Deployment recommendations

- For demos, run the backend in a small Docker container and Qdrant via docker-compose. Use a small VM (DigitalOcean / Render) or GitHub Codespaces for quick demos.
- For production-quality demo (optional): add TLS, basic auth on endpoints, and deploy Qdrant as managed service (e.g., Qdrant Cloud) to avoid Docker management.

---

## Environment variables (list)

- `OPENAI_API_KEY` (or `ANTHROPIC_API_KEY`) — required for embeddings + LLM
- `QDRANT_URL` — default `http://localhost:6333`
- `RAG_COLLECTION` — default `rag_demo`
- `SERVER_PORT` — default `8080`

---

## Developer Checklist (what the AI should implement step-by-step)

1. Create Gradle Kotlin project skeleton and the folder structure above.
2. Add dependencies and create `Application.kt`.
3. Implement `EmbeddingService` with OpenAI client integration and caching.
4. Implement `VectorStoreService` that talks to Qdrant via HTTP (create collection if not exist).
5. Add `RagService` to implement prompt building and LLM call.
6. Add controller `RagController` and wire services (use Spring DI).
7. Add `data/knowledge.json` and a startup routine to load items and upsert into Qdrant (skip if already present).
8. Implement simple logging, Prometheus metrics optionally (actuator endpoints).
9. Add Dockerfile and docker-compose.yml for Qdrant.
10. Add tests and GitHub Actions CI job.

---

## Acceptance Criteria

- The app starts with `./gradlew bootRun` and can connect to Qdrant at `http://localhost:6333`.
- `POST /api/ask` returns an `AskResponse` with `answer`, at least one `source_chunk`, `raw_llm`, and `prompt` fields.
- Debug mode in frontend displays the prompt and retrieved chunks exactly as returned from the backend.

---

## Additional notes & hints for the AI implementer

- Use small embedding models to reduce token/cost (e.g., OpenAI text-embedding-3-small) for the demo.
- Keep the knowledge.json small but semantically diverse so retrieval examples look convincing.
- Include clear README instructions and sample Postman collection for manual testing.
- Make sure to include quick-start commands in README so reviewers can run the demo in <10 minutes.

---

## Deliverables for backend.md

- Kotlin + Spring Boot backend project with endpoints described.
- Docker and docker-compose files to run Qdrant locally.
- Sample knowledge.json loaded at startup.
- Unit and integration tests and CI pipeline.


---

