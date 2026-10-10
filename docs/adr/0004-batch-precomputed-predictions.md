# ADR 0004 — Batch-precomputed predictions, never inline inference

**Status:** accepted (Phase 0)

**Context.** Serving ~500 symbols × 3 horizons with per-stock models inline
would make API latency unpredictable and couple request latency to model size.

**Decision.** Predictions are computed by scheduled jobs (pre-market 08:30 ET
and nightly) and stored in Postgres (`predictions`, `rankings`), cached in Redis
with short TTLs. Read APIs only query the store. Every response carries
`modelVersion`, `asOf` and the disclaimer; a missed job serves the previous run
flagged `stale: true`.

**Consequences.** Predictable p95 latency (target ≤200–300 ms) and simple
scaling; job freshness must be monitored and alerted.
