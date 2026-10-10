package com.stockpredict.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway pre-check: verifies the Bearer JWT on protected routes and forwards
 * trusted identity headers ({@code X-User-Id/Plan/Roles/Email}) downstream.
 * Any inbound copy of those headers is stripped first so clients cannot spoof
 * identity. Services still verify tokens locally (defence in depth).
 */
@Component
public class JwtPreCheckFilter implements GlobalFilter, Ordered {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/.well-known/jwks.json");

    private final GatewayJwtVerifier verifier;
    private final ObjectMapper objectMapper;

    public JwtPreCheckFilter(GatewayJwtProperties properties, ObjectMapper objectMapper) {
        this.verifier = new GatewayJwtVerifier(
                URI.create(properties.getJwksUri()),
                properties.getIssuer(),
                properties.getAudience(),
                Clock.systemUTC(),
                properties.getClockSkew());
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (HttpMethod.OPTIONS.equals(request.getMethod())
                || PUBLIC_PATHS.contains(path)
                || path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return problem(exchange, "UNAUTHORIZED", "Authentication required");
        }
        try {
            GatewayJwtVerifier.GatewayClaims claims = verifier.verify(header.substring(7).trim());
            ServerHttpRequest mutated = request.mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-User-Plan");
                        headers.remove("X-User-Roles");
                        headers.remove("X-User-Email");
                        headers.set("X-User-Id", claims.subject());
                        headers.set("X-User-Plan", claims.plan());
                        headers.set("X-User-Roles", claims.roles());
                        headers.set("X-User-Email", claims.email());
                    })
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (GatewayJwtVerifier.GatewayAuthException ex) {
            return problem(exchange, ex.code(), ex.getMessage());
        }
    }

    private Mono<Void> problem(ServerWebExchange exchange, String code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://errors.stockpredict.com/" + code.toLowerCase().replace('_', '-'));
        body.put("title", "Unauthorized");
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getURI().getPath());
        body.put("code", code);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            bytes = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
