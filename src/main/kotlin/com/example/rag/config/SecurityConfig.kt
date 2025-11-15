package com.example.rag.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.web.server.WebFilter
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
class SecurityConfig @Autowired constructor(
    private val features: FeatureFlags,
    private val securityProps: SecurityProps
) {
    @Bean
    fun filterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        // If auth disabled, permit all
        if (!features.authEnabled) {
            http
                .csrf { it.disable() }
                .authorizeExchange { exchanges ->
                    exchanges
                        .pathMatchers("/api/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .anyExchange().permitAll()
                }
            return http.build()
        }

        // Auth enabled: protect API and actuator
        http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .pathMatchers("/actuator/**").authenticated()
                    .pathMatchers("/api/**").authenticated()
                    .anyExchange().permitAll()
            }
            .httpBasic { }

        return http.build()
    }

    @Bean
    fun userDetailsService(): MapReactiveUserDetailsService {
        val user = securityProps.basic.user
        val pass = securityProps.basic.pass
        return if (user.isNotBlank() && pass.isNotBlank()) {
            val u = User.withUsername(user).password("{noop}" + pass).roles("USER").build()
            MapReactiveUserDetailsService(u)
        } else {
            MapReactiveUserDetailsService()
        }
    }

    @Bean
    fun apiKeyWebFilter(): WebFilter = ApiKeyWebFilter(securityProps.apiKey)
}

class ApiKeyWebFilter(private val configuredKey: String) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (configuredKey.isNotBlank()) {
            val provided = exchange.request.headers.getFirst("X-API-Key")
            if (provided != null && provided == configuredKey) {
                val auth = UsernamePasswordAuthenticationToken("api-key-user", "N/A", listOf(SimpleGrantedAuthority("ROLE_USER")))
                return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            }
        }
        return chain.filter(exchange)
    }
}
