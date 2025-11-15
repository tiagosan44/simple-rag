# Multi-stage Dockerfile for Kotlin Spring Boot (WebFlux)
# Build stage
FROM gradle:8.7-jdk17-alpine AS builder
WORKDIR /workspace
COPY . .
# Speed up gradle by using daemon and caching dependencies
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --no-daemon clean bootJar

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
ENV SERVER_PORT=8080 \
    QDRANT_URL=http://localhost:6333 \
    RAG_COLLECTION=rag_demo \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC"

# Copy the fat jar from builder
COPY --from=builder /workspace/build/libs/*.jar /app/app.jar

EXPOSE 8080

# Healthcheck (optional)
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
