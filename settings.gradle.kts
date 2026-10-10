rootProject.name = "stockpredict"

include(
    ":common-lib",
    ":db-migrations",
    ":libs:feature-engine",
    ":libs:jwt-core",
    ":services:auth-service",
    ":services:market-data-service",
    ":services:prediction-service",
    ":services:ranking-service",
    ":services:paper-trading-service",
    ":services:gateway",
    ":tests:e2e",
    ":tests:perf",
)
