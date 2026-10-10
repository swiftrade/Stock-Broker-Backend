package com.stockpredict.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(com.stockpredict.gateway.security.GatewayJwtProperties.class)
public class GatewayConfig {

    /** Propagates or generates a request id for tracing across services. */
    @Bean
    public GlobalFilter requestIdFilter() {
        return (ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String requestId = request.getHeaders().getFirst("X-Request-Id");
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            }
            String finalRequestId = requestId;
            ServerHttpRequest mutated = request.mutate()
                    .headers(headers -> headers.set("X-Request-Id", finalRequestId))
                    .build();
            exchange.getResponse().getHeaders().set("X-Request-Id", finalRequestId);
            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }
}
