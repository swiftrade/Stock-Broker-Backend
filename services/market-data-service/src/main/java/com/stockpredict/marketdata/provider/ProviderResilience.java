package com.stockpredict.marketdata.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Programmatic (non-AOP) resilience for provider calls: three attempts with a
 * fixed 200ms backoff, guarded by a circuit breaker that opens at a 50% failure
 * rate over a 20-call sliding window.
 */
public class ProviderResilience {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF = Duration.ofMillis(200);

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ProviderResilience(String name) {
        this.retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .waitDuration(BACKOFF)
                .build());
        this.circuitBreaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .build());
    }

    public <T> T execute(Supplier<T> supplier) {
        Supplier<T> retried = Retry.decorateSupplier(retry, supplier);
        return CircuitBreaker.decorateSupplier(circuitBreaker, retried).get();
    }
}
