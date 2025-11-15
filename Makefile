APP_NAME=rag-backend

.PHONY: dev build test run up down docker

dev:
	./gradlew bootRun

build:
	./gradlew clean build

test:
	./gradlew test

run:
	java -jar build/libs/*.jar

up:
	docker compose up -d qdrant

down:
	docker compose down

docker:
	docker build -t $(APP_NAME):latest .

docker-run:
	docker run --rm -p 8080:8080 \
	  -e QDRANT_URL=http://host.docker.internal:6333 \
	  -e RAG_COLLECTION=rag_demo \
	  $(APP_NAME):latest
