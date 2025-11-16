package com.example.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableConfigurationProperties(
    RagProps::class,
    QdrantProps::class,
    FeatureFlags::class,
    SecurityProps::class,
    CorsProps::class
)
class AppConfig {
    @Bean
    fun requestTimeouts(): Timeouts = Timeouts(
        connect = Duration.ofSeconds(3),
        read = Duration.ofSeconds(10)
    )

    @Bean
    fun openAIProps(ragProps: RagProps): OpenAIProps = ragProps.openai
}

@ConfigurationProperties(prefix = "rag")
data class RagProps(
    var embeddingProvider: String = "openai",
    var openai: OpenAIProps = OpenAIProps(),
    var anthropic: AnthropicProps = AnthropicProps()
)

data class OpenAIProps(
    var apiKey: String = "",
    var embeddingModel: String = "text-embedding-3-small",
    var llmModel: String = "gpt-4o-mini",
    var temperature: Double = 0.0,
    var maxTokens: Int = 1024,
    var baseUrl: String = "https://api.openai.com/v1"
)

data class AnthropicProps(
    var apiKey: String = "",
    var embeddingModel: String = "claude-embed-1",
    var llmModel: String = "claude-2"
)

@ConfigurationProperties(prefix = "qdrant")
data class QdrantProps(
    var url: String = "http://localhost:6333",
    var collection: String = "rag_demo"
)

@ConfigurationProperties(prefix = "features")
data class FeatureFlags(
    var authEnabled: Boolean = false,
    var debug: Boolean = false,
    var forceRecreate: Boolean = false
)

@ConfigurationProperties(prefix = "security")
data class SecurityProps(
    var apiKey: String = "",
    var basic: BasicAuthProps = BasicAuthProps()
)

data class BasicAuthProps(
    var user: String = "",
    var pass: String = ""
)

@ConfigurationProperties(prefix = "cors")
data class CorsProps(
    var allowedOrigins: String = "http://localhost:5173"
)

data class Timeouts(
    val connect: Duration,
    val read: Duration
)
