package com.example.rag.util

import com.example.rag.config.Timeouts
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.util.retry.Retry
import java.time.Duration

@Configuration
class HttpClientFactory {
    private val log = LoggerFactory.getLogger(HttpClientFactory::class.java)

    @Bean
    fun webClient(timeouts: Timeouts): WebClient {
        val provider = ConnectionProvider.builder("rag-pool")
            .maxConnections(100)
            .pendingAcquireMaxCount(1000)
            .build()

        val httpClient = HttpClient.create(provider)
            .responseTimeout(timeouts.read)
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, timeouts.connect.toMillis().toInt())

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .filter(loggingFilter())
            .build()
    }

    private fun loggingFilter(): ExchangeFilterFunction = ExchangeFilterFunction.ofRequestProcessor { req ->
        log.debug("HTTP {} {}", req.method(), req.url())
        Mono.just(req)
    }
}

fun defaultRetryPolicy(): Retry = Retry
    .backoff(3, Duration.ofMillis(500))
    .maxBackoff(Duration.ofSeconds(5))
    .transientErrors(true)
