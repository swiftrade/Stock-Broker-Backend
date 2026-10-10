package com.stockpredict.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-plan rate limiting backed by Redis (fixed window, one key per identity and
 * minute). Disabled by default so local development needs no Redis; when enabled
 * and Redis is unavailable the request is allowed (fail-open) and a warning is
 * logged once per failure burst.
 */
@Component
public class PlanRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PlanRateLimitFilter.class);

    private static final long WINDOW_TTL_SECONDS = 70;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]); "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; "
                    + "return c",
            Long.class);

    private final boolean enabled;
    private final int freePerMinute;
    private final int premiumPerMinute;
    private final boolean trustForwardedFor;
    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;

    public PlanRateLimitFilter(
            @Value("${stockpredict.gateway.rate-limit-enabled:false}") boolean enabled,
            @Value("${stockpredict.gateway.free-requests-per-minute:120}") int freePerMinute,
            @Value("${stockpredict.gateway.premium-requests-per-minute:1200}") int premiumPerMinute,
            @Value("${stockpredict.gateway.trust-forwarded-for:false}") boolean trustForwardedFor,
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.enabled = enabled;
        this.freePerMinute = freePerMinute;
        this.premiumPerMinute = premiumPerMinute;
        this.trustForwardedFor = trustForwardedFor;
        this.redisProvider = redisProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return chain.filter(exchange);
        }
        String identity = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (identity == null || identity.isBlank()) {
            identity = clientIp(exchange);
        }
        String plan = exchange.getRequest().getHeaders().getFirst("X-User-Plan");
        int limit = "PREMIUM".equalsIgnoreCase(plan) ? premiumPerMinute : freePerMinute;
        long window = System.currentTimeMillis() / 60_000;
        String key = "ratelimit:" + identity + ":" + window;

        return redis.execute(RATE_LIMIT_SCRIPT, List.of(key), List.of(String.valueOf(WINDOW_TTL_SECONDS)))
                .next()
                .flatMap(count -> {
                    if (count > limit) {
                        return tooManyRequests(exchange, limit);
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> {
                    log.warn("Rate limiter unavailable ({}); allowing request", error.getMessage());
                    return chain.filter(exchange);
                });
    }

    private String clientIp(ServerWebExchange exchange) {
        if (trustForwardedFor) {
            List<String> forwarded = exchange.getRequest().getHeaders().get("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.get(0).split(",")[0].trim();
            }
        }
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, int limit) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set("Retry-After", "60");
        String body = "{\"type\":\"https://errors.stockpredict.com/rate-limited\","
                + "\"title\":\"Rate limit exceeded\",\"status\":429,"
                + "\"detail\":\"Plan limit of " + limit + " requests/minute exceeded\","
                + "\"code\":\"RATE_LIMITED\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -50;
    }

    /** Exposed for diagnostics/tests. */
    public Map<String, Integer> limits() {
        return Map.of("free", freePerMinute, "premium", premiumPerMinute);
    }
}
