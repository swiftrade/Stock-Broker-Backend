plugins {
    application
}

// Dependency-free load test (A8). Gatling can replace this later; the tool
// reports p50/p95/p99 and error rate for a read-heavy journey.
//
//   ./dev.sh :tests:perf:run --args="--url=http://localhost:8080 --users=50 --seconds=30"

application {
    mainClass.set("com.stockpredict.perf.LoadTest")
}
