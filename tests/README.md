# Test layers (A8)

| Layer | Location | How to run |
| --- | --- | --- |
| Unit | `*/src/test` | `./dev.sh test` |
| Integration (Testcontainers) | `services/auth-service/src/test` | `./dev.sh test-integration` |
| ML parity | `libs/feature-engine`, `services/prediction-service` | `./dev.sh :libs:feature-engine:test` (uses the golden files) |
| End-to-end | `tests/e2e` | `./dev.sh :tests:e2e:test -Pe2eBaseUrl=http://localhost:8080 [-Pe2eToken=<token>]` |
| Performance | `tests/perf` | `./dev.sh :tests:perf:run --args="--url=http://localhost:8080 --users=50 --seconds=30"` |
| Security | `tests/security` | `./tests/security/zap-baseline.sh http://localhost:8080` (ZAP) + Trivy in CI |

The e2e suite is skipped unless a base URL is provided, so `./dev.sh build`
stays fast and offline. Bring the stack up first:

```bash
./dev.sh up
# then boot the services (one container, multiple bootRun tasks):
./dev.sh boot :services:auth-service:bootRun :services:gateway:bootRun \
  :services:market-data-service:bootRun :services:prediction-service:bootRun \
  :services:ranking-service:bootRun :services:paper-trading-service:bootRun --parallel
```

The performance tool reports p50/p95/p99 and the error rate, failing when p95
exceeds 300 ms or the error rate reaches 0.1% — matching the plan's acceptance
criteria. A Gatling simulation can replace it without changing the targets.
