package com.stockpredict.perf;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency/latency smoke for read endpoints (A8). Usage:
 *
 * <pre>
 *   ./dev.sh :tests:perf:run --args="--url=http://localhost:8080 --users=500 \
 *       --seconds=30 --token=&lt;access token&gt;"
 * </pre>
 *
 * The default path is the authenticated read endpoint
 * {@code /api/v1/predictions/intraday?limit=20}; pass {@code --token=<jwt>} to
 * measure it with a bearer token. Without a token the run measures the
 * unauthenticated path (401s count as errors).
 *
 * Acceptance target (plan): 500 concurrent users, 50 RPS sustained, p95 ≤ 300 ms
 * for read endpoints, error rate &lt; 0.1%.
 */
public final class LoadTest {

    private LoadTest() {
    }

    public static void main(String[] args) throws Exception {
        String url = arg(args, "--url", "http://localhost:8080");
        int users = Integer.parseInt(arg(args, "--users", "50"));
        int seconds = Integer.parseInt(arg(args, "--seconds", "30"));
        String path = arg(args, "--path", "/api/v1/predictions/intraday?limit=20");
        String token = arg(args, "--token", "");
        if (token.isBlank()) {
            System.err.println("WARNING: no --token provided; this run measures the unauthenticated path "
                    + path + " and 401 responses will be counted as errors. "
                    + "Pass --token=<jwt> to measure the authenticated path.");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        URI uri = URI.create(url + path);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        AtomicLong requests = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);

        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        try (ExecutorService pool = Executors.newFixedThreadPool(users)) {
            for (int i = 0; i < users; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        while (System.nanoTime() < deadline) {
                            long t0 = System.nanoTime();
                            try {
                                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET();
                                if (!token.isBlank()) {
                                    request.header("Authorization", "Bearer " + token);
                                }
                                HttpResponse<Void> response = client.send(
                                        request.build(), HttpResponse.BodyHandlers.discarding());
                                if (response.statusCode() >= 400) {
                                    errors.incrementAndGet();
                                }
                            } catch (Exception ex) {
                                errors.incrementAndGet();
                            } finally {
                                latencies.add((System.nanoTime() - t0) / 1_000_000);
                                requests.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            System.out.printf("Load test: %d users for %ds against %s%n", users, seconds, uri);
            start.countDown();
            done.await();
        }

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long total = requests.get();
        System.out.printf("requests=%d errors=%d errorRate=%.4f%% rps=%.1f%n",
                total, errors.get(), total == 0 ? 0 : 100.0 * errors.get() / total,
                seconds == 0 ? 0 : (double) total / seconds);
        System.out.printf("latency ms: p50=%d p95=%d p99=%d max=%d%n",
                percentile(sorted, 50), percentile(sorted, 95),
                percentile(sorted, 99), sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));

        boolean ok = total > 0
                && errors.get() * 1000 < total
                && percentile(sorted, 95) <= 300;
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}
