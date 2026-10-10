# Stock Prediction Webservice (Java) — Architecture and Algorithms

Cloud-portable Java 21 / Spring Boot 3.3 webservice for stock prediction and
freemium paper trading. Online serving is all Java; model training stays in the
existing Python research pipeline (`../STOCKMARKETANALYSIS`) and hands off
versioned XGBoost files through `ml-export/`.

This repository implements the build plan in
`../Stock Prediction Webservice — Java Build Plan.md` (agents A0–A9).
Validation status and remaining operational work are in
[§15](#15-validation-gates) and [§18](#18-known-limitations--remaining-work).

---

## Table of contents

1. [Overview](#1-overview)
2. [System architecture](#2-system-architecture)
3. [End-to-end data flow](#3-end-to-end-data-flow)
4. [Contracts and the error model](#4-contracts-and-the-error-model)
5. [Security and identity algorithms](#5-security-and-identity-algorithms)
6. [Market data service](#6-market-data-service)
7. [Feature engine — the Python port](#7-feature-engine--the-python-port)
8. [Prediction service](#8-prediction-service)
9. [Ranking service](#9-ranking-service)
10. [Paper trading service](#10-paper-trading-service)
11. [Trading calendar](#11-trading-calendar)
12. [Persistence schema](#12-persistence-schema)
13. [Configuration reference](#13-configuration-reference)
14. [Build, run, test](#14-build-run-test)
15. [Validation gates](#15-validation-gates)
16. [Infrastructure and CI](#16-infrastructure-and-ci)
17. [Architecture decision records](#17-architecture-decision-records)
18. [Known limitations & remaining work](#18-known-limitations--remaining-work)

---

## 1. Overview

| Capability | Endpoint surface | Plan |
| --- | --- | --- |
| Login / registration | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `GET /auth/me`, `GET /.well-known/jwks.json` | Public / any |
| Intraday predictions | `GET /predictions/intraday`, `/predictions/intraday/{symbol}`, `/predictions/model-info` | FREE: top 20 · PREMIUM: all |
| Historical charts | `GET /stocks/{symbol}/history?interval=1d\|5m`, `/stocks/{symbol}/quote`, `/stocks/quotes?symbols=`, `/stocks/search` | Any |
| Long-term top 10 | `GET /recommendations/long-term?limit=10` | Any |
| Paper trading | `/paper/watchlist`, `/paper/account`, `/paper/orders`, `/paper/positions`, `/paper/portfolio` | FREE: 10 symbols · PREMIUM: unlimited |

Design rules:

- **Contract-first.** OpenAPI 3.1 specs in `/contracts` are frozen after gate G0;
  Java interfaces are generated from them and never edited.
- **Java serves, Python trains.** XGBoost models are exported as JSON and loaded
  with XGBoost4J. Feature engineering is a pure-Java port whose output must match
  Python on golden files (train/serve-skew gate).
- **Batch-precomputed predictions.** Scheduled jobs write to Postgres; API calls
  never run model inference inline.
- **In-house paper trading.** Simulated fills in our DB; no per-user broker
  accounts.
- **Cloud-portable.** Containers on Kubernetes, managed Postgres/Redis,
  Terraform per cloud, no cloud-proprietary SDKs in service code.

---

## 2. System architecture

```text
                        ┌───────────────────────────────────────┐
                        │             gateway :8080             │
                        │  routing · JWT pre-check · rate limit │
                        └───────┬────────┬─────────┬────────────┘
            ┌───────────────────┘        │         └───────────────────┐
            ▼                            ▼                             ▼
   ┌─────────────────┐        ┌──────────────────┐          ┌───────────────────┐
   │  auth-service   │        │ market-data-svc  │          │ prediction-svc    │
   │      :8081      │        │      :8082       │          │      :8083        │
   │ Argon2id · JWT  │        │ Polygon/synthetic│          │ XGBoost4J batch   │
   │ refresh rotate  │        │ ingestion·guard  │          │ registry·read API │
   └────────┬────────┘        └────────┬─────────┘          └─────────┬─────────┘
            │                          │                              │
            │            ┌─────────────┴──────────────┐               │
            │            ▼                            ▼               ▼
            │   ┌──────────────────┐         ┌───────────────────────────────┐
            │   │  ranking-svc     │         │  paper-trading-svc            │
            │   │     :8084        │         │         :8085                 │
            │   │ inverse-vol caps │         │ matching engine · P&L         │
            │   └────────┬─────────┘         └───────────────┬───────────────┘
            │            │                                   │
            ▼            ▼                                   ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │              Postgres 16 + TimescaleDB   ·   Redis 7                 │
   │  users · refresh_tokens · bars_daily/5m · model_registry ·           │
   │  predictions · rankings · paper_accounts/orders/fills/positions/...  │
   └──────────────────────────────────────────────────────────────────────┘
                              ▲
                              │ models/<version>/{metadata.json, *.json}
                       ┌──────┴───────┐
                       │  ml-export   │  Python: export models + goldens
                       └──────────────┘
```

Modules (`settings.gradle.kts`):

| Module | Responsibility |
| --- | --- |
| `contracts/` | Frozen OpenAPI 3.1 specs + event schema |
| `db-migrations/` | Flyway migrations for all 15 tables (incl. `symbol_universe`) |
| `common-lib/` | JWT wrapper, RFC 7807 errors, plan enforcement, trading calendar, clock, bar-timestamp convention |
| `libs/jwt-core/` | Framework-free RS256 verification shared by services and gateway (ADR-0009) |
| `libs/feature-engine/` | Pure-Java `features.py` port + golden parity harness |
| `services/auth-service` | Registration, login, token lifecycle, JWKS |
| `services/market-data-service` | Provider adapters, ingestion, quality guard, chart APIs |
| `services/prediction-service` | Model registry, pre-market batch, prediction APIs |
| `services/ranking-service` | Nightly top-10 with risk caps |
| `services/paper-trading-service` | Accounts, watchlist, matching engine, P&L |
| `services/gateway` | Edge routing, JWT pre-check, rate limits |
| `tests/e2e`, `tests/perf`, `tests/security` | Journey, load and security layers |
| `ml-export/` | Model/golden export from the Python pipeline |
| `infra/` | Helm chart + Terraform modules (GCP/AWS/Azure) |

---

## 3. End-to-end data flow

```text
 provider (Polygon / synthetic)
      │  daily + 5-minute bars
      ▼
 market-data-service ── quality guard ──► bars_daily / bars_5m
      │                                        │  Redis Stream: bars.updated
      ▼                                        ▼
 history / quote APIs            prediction-service  (pre-market 08:30 ET)
      │                            1. load bars + market ETFs
      │                            2. feature-engine  ──► feature table (lagged)
      │                            3. XGBoost pooled + per-stock ensemble
      │                            4. upsert predictions · activate model_registry
      ▼                                        ▼
 paper-trading-service            ranking-service  (nightly 02:00 ET)
 quotes (HTTP → DB fallback)      1. latest 20d predictions
 matching engine · fills          2. realized vol from bars_daily
 positions · P&L · snapshots      3. inverse-vol weights + position/sector caps
      │                            4. upsert rankings (history retained)
      ▼                                        ▼
                 gateway  ◄── clients (JWT + plan)
```

Two properties hold across the pipeline:

1. **Point-in-time safety.** Features are lagged by `prediction_delay` (1 trading
   day); targets are forward-looking and excluded from predictors. Batch jobs
   read only data available before the decision time.
2. **Determinism.** Every batch step is a pure function of stored inputs, so the
   same date + model version produces the same output (validated by the
   "deterministic for a fixed date" acceptance criterion).

---

## 4. Contracts and the error model

OpenAPI 3.1 specs (`contracts/*.yaml`) define every endpoint. Server interfaces
are generated with `openapi-generator` (`spring`, `interfaceOnly`,
Spring Boot 3/Jakarta) into each service's `build/generated/openapi`; generated
code is excluded from coverage and never edited.

All errors are **RFC 7807** (`application/problem+json`) with a stable `code`:

```json
{
  "type": "https://errors.stockpredict.com/plan-limit",
  "title": "Plan limit exceeded",
  "status": 403,
  "detail": "FREE plan allows at most 10 watchlist symbols",
  "instance": "/api/v1/paper/watchlist",
  "code": "PLAN_LIMIT"
}
```

| Code | HTTP | Raised when |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Bean validation, missing/blank fields, malformed body, breached password |
| `INVALID_CREDENTIALS` | 401 | Wrong email/password |
| `TOKEN_INVALID` / `TOKEN_EXPIRED` | 401 | JWT/refresh verification failure |
| `EMAIL_NOT_VERIFIED` | 403 | Login before verification |
| `PLAN_LIMIT` | 403 | Entitlement exceeded (watchlist, predictions cap) |
| `SYMBOL_NOT_IN_WATCHLIST` | 403 | Order for a non-watchlist symbol |
| `NOT_FOUND` | 404 | Unknown account/order/symbol |
| `EMAIL_ALREADY_REGISTERED`, `CONFLICT` | 409 | Duplicate email, swap lock, non-cancelable order |
| `GONE` | 410 | Verification token expired/used |
| `LOCKED` | 423 | 5 failed logins in 15 minutes |
| `MARKET_CLOSED`, `INSUFFICIENT_CASH`, `UNPROCESSABLE` | 422 | Order/batch cannot execute |
| `RATE_LIMITED` | 429 | Gateway plan rate limit |
| `INTERNAL` | 500 | Unexpected (logged, detail hidden) |

`common-lib` provides `ErrorCode`, `ApiException`, `ProblemFactory` and a
`@RestControllerAdvice` that also maps `MethodArgumentNotValidException`,
`ConstraintViolationException`, `AccessDeniedException` and malformed bodies.
A `RequestIdFilter` adds/propagates `X-Request-Id` (also in the MDC).

---

## 5. Security and identity algorithms

### 5.1 Passwords

- **Hashing:** Argon2id via Spring Security's `Argon2PasswordEncoder`
  (`defaultsForSpringSecurity_v5_8`, memory-hard, tuned for ~250 ms per verify).
- **Breached-password check:** HaveIBeenPwned **k-anonymity range API**. The
  password's SHA-1 is computed locally; only the first 5 hex characters are sent
  (`GET api.pwnedpasswords.com/range/{prefix}`); the suffix is matched against the
  returned list. The call **fails open** (logs a warning, allows the password) if
  the API is unreachable.
- **Policy:** ≥ 12 characters (contract), duplicate email rejected
  case-insensitively (`lower(email)` unique index).

### 5.2 Access tokens (RS256 JWT)

Issued by auth-service, verified locally by every service and the gateway:

```text
header  { alg: RS256, kid: <active key id>, typ: JWT }
payload { sub: <user uuid>, email, plan: FREE|PREMIUM, roles: [...],
          iss, aud, iat, exp = iat + 15 min, jti }
```

Verification (`common-lib::JwtVerifier` for services, `GatewayJwtVerifier` at
the edge) both delegate to the shared framework-free
`libs/jwt-core::JwtVerificationCore` so the two cannot drift (ADR-0009):

1. Parse; require `alg = RS256`.
2. Select candidate RSA keys from the JWKS by `kid` (remote JWKS with built-in
   caching; `JWKSourceBuilder`).
3. Verify the RSA signature against each candidate.
4. Validate `exp`/`nbf`/`iat` with the configured clock skew (default ±30 s,
   `stockpredict.jwt.clock-skew`), `iss`, `aud`, non-empty `sub`.
5. Map claims to `JwtClaims` (services) or `GatewayClaims` (edge headers).

In dev the key pair is generated on first boot and persisted as PKCS#8/X.509 PEM
with owner-only permissions; in prod generation is disabled
(`AUTH_JWT_ALLOW_KEY_GENERATION=false`) and a missing key fails fast.
`GET /.well-known/jwks.json` publishes `{kty, use, alg, kid, n, e}`. Key rotation
is additive (new `kid` published, old retired).

`JwtAuthenticationFilter` installs the claims as the security-context principal
with authorities `ROLE_USER`, `PLAN_FREE|PLAN_PREMIUM`, and `ROLE_<role>`.
Invalid tokens produce an RFC 7807 401.

### 5.3 Refresh tokens — rotation with reuse detection

- 256-bit random URL-safe token; only its **SHA-256 hash** is stored.
- Every login starts a **family** (`family_id = uuid`), TTL 7 days.
- On refresh: look up by hash → if the token is **already revoked**, treat it as
  theft and **revoke the entire family** (all sessions in that chain); if
  expired, reject. Otherwise atomically claim the token with a conditional
  update (`revoked_at IS NULL`); a losing concurrent replay is itself treated as
  reuse and revokes the family. The winner issues a new token in the same family
  and links `replaced_by`.
- Logout revokes the presented token's family (idempotent for unknown tokens).
- Email verification tokens are also 256-bit random, stored SHA-256-hashed with a
  24 h TTL; expired/used tokens return `410 GONE`.

### 5.4 Plan entitlement

`@RequiresPlan(PREMIUM)` on handlers is enforced server-side by
`PlanEnforcementAspect` against the JWT `plan` claim (ordinal `FREE < PREMIUM`);
anonymous callers are rejected. Plan limits that are data-dependent (e.g. FREE
top-20 predictions, FREE 10-symbol watchlist) are enforced in the service layer,
never in the client. `plan` changes take effect at the next token refresh.

### 5.5 Gateway pre-check and rate limiting

- Routes `/api/v1/**` to the services; public paths (register/login/refresh/
  logout/verify/resend/JWKS/actuator) bypass authentication.
- For protected paths the gateway verifies the JWT locally (same RS256 rules),
  **strips any inbound `X-User-*` headers** (anti-spoofing), then forwards
  trusted `X-User-Id / X-User-Plan / X-User-Roles / X-User-Email`. Services still
  verify tokens themselves (defence in depth).
- **Rate limiting** (optional, enabled by `stockpredict.gateway.rate-limit-enabled`):
  fixed one-minute window keyed by user id (or client IP for anonymous) in Redis:
  FREE 120 req/min, PREMIUM 1200 req/min. Redis failures **fail open**.
- `X-Request-Id` is propagated or generated at the edge.

---

## 6. Market data service

### 6.1 Providers and resilience

`MarketDataProvider` has two implementations selected automatically
(`provider=auto`): **Polygon** when an API key is configured, otherwise a
deterministic **Synthetic** provider (hash-seeded, so local dev and tests are
reproducible offline).

| Operation | Polygon call |
| --- | --- |
| Daily bars | `GET /v2/aggs/ticker/{sym}/range/1/day/{from}/{to}?adjusted=true&sort=asc&limit=50000` + `next_url` pagination |
| 5-minute bars | same with `5/minute` |
| Quote | `GET /v2/last/nbbo/{sym}` + `/v2/last/trade/{sym}` |
| Search | `GET /v3/reference/tickers?search=…&active=true` |

Every provider call is wrapped in **Resilience4j Retry** (3 attempts, 200 ms
backoff) and a **CircuitBreaker** (50% failure rate over a 20-call window).
Quote failures return empty rather than throwing.

### 6.2 Ingestion pipeline

```text
universe (Wikipedia S&P 500 via jsoup, fallback symbol list + ETFs)
   └─► for each symbol: provider.fetchDailyBars(backfill = 5 years)
       provider.fetchIntradayBars(days = 30, rolling)
         └─► IngestionQualityGuard.validate(...)
               └─► BarRepository.upsert*  (INSERT … ON CONFLICT (symbol, ts) DO UPDATE)
                     └─► BarsUpdatedPublisher  (Redis Stream "bars.updated")
```

Per-symbol failures are isolated (logged, counted) and never abort a batch.
Daily ingestion is **incremental** (from the day after the last stored bar; the
full backfill runs only on first sight of a symbol). The explicit ingestion
universe lives in `symbol_universe`; user-requested on-demand ingestion never
joins it. Schedules: daily refresh `0 15 6 * * TUE-SAT` ET and a 5-minute
refresh during market hours (gated by `TradingCalendar.isMarketOpen`). One-shot
mode: `MARKET_DATA_RUN_INGEST_ONCE=true` runs a backfill and exits 0/1.

### 6.3 Data-quality guard

Port of the essential checks in the Python `marketdata_guard.py`:

| Check | Rule | Outcome |
| --- | --- | --- |
| Future timestamp | `ts > now + 5 min` | reject |
| Stale (live intraday only) | `now − ts > 30 min` during a session | reject |
| Price sanity | price ≤ 0, `high < low`, or `close ∉ [low, high]` | reject |
| Deviation | `|close/prev_close − 1| > 35%` (configurable) | accept + flag |
| Duplicate/order | repeated or non-monotonic timestamps | reject/dedupe |

The result carries `accepted / rejected / flagged` counts; thresholds live under
`stockpredict.market-data.guard.*`. Staleness is only enforced for live
ingestion (a historical backfill would otherwise be rejected wholesale).

### 6.4 Read APIs

- `GET /stocks/{symbol}/history?interval=1d|5m&from=&to=` — DB first; if empty,
  fetch from the provider and ingest best-effort. Defaults: 5 years (1d),
  30 days (5m). Long series are evenly sampled to a 2000-bar cap for charting.
  `stale=true` when the newest bar predates the expected session
  (`TradingCalendar`).
- `GET /stocks/{symbol}/quote` — provider quote, cached in Redis for 60 s
  (key `quote:{symbol}`); falls back to the latest daily close with
  `stale=true` when the provider is unavailable.
- `GET /stocks/quotes?symbols=` — batch quotes for portfolio marking (single
  call, invalid/unknown symbols omitted); each quote passes the `QuoteGuard`.
- `GET /stocks/search?q=` — DB symbols first, then provider search, deduped.

---

## 7. Feature engine — the Python port

`libs/feature-engine` is a dependency-free Java port of
`STOCKMARKETANALYSIS/features.py::build_feature_dataset`. Column names and
semantics match exactly; feature **order** for serving always comes from the
model metadata, never from this class.

### 7.1 Pipeline order

```text
1. per-symbol technicals
2. merge market/sector ETF features (by timestamp)
3. residualize (stock − SPY momentum)
4. excess-return target (subtract SPY forward returns; drop spy_fwd_* columns)
5. sector dummies (one-hot)
6. cross-sectional ranks (all names, and within sector)
7. execution delay (shift feature columns by prediction_delay within symbol)
8. sanitize (+∞/−∞ → NaN)
```

`FeatureTable` stores `(symbol, timestamp) → {column: value}` with insertion
order preserved; `getFeatureColumns()` excludes identifiers, raw prices,
`volume_ma_20d`, `true_range`, `sector_label`, `target_*` and non-log
`market_cap*`.

### 7.2 Indicator definitions

All rolling windows use `min_periods = window`; standard deviation is the sample
std (ddof = 1); EMAs use `adjust=false` (seeded with the first observation).

| Feature | Formula |
| --- | --- |
| `log_return_wd` | `ln(close_t / close_{t−w})`, w ∈ {1,2,5,10,20} |
| `ma_ratio_wd` | `close_t / mean(close_{t−w+1..t})`, w ∈ {5,10,20,50} |
| `volatility_20d` | `std(log_return_1d, 20)` |
| `volume_change_1d` | `volume_t / volume_{t−1} − 1` |
| `volume_ratio_20d` | `volume_t / mean(volume, 20)` |
| `rsi_14` | Wilder: `α = 1/14`; `avgGain_t = avgGain_{t−1} + α(gain_t − avgGain_{t−1})` (same for loss, seeded at 0); `RSI = 100 − 100/(1 + avgGain/avgLoss)`; **NaN when avgLoss = 0** |
| `macd_line` | `EMA_12(close) − EMA_26(close)` |
| `macd_signal` | `EMA_9(macd_line)` |
| `macd_histogram` | `macd_line − macd_signal` |
| `bb_middle/upper/lower` | `mean_20 ± 2·std_20` |
| `bb_width` | `(upper − lower) / middle` |
| `bb_position` | `(close − lower) / (upper − lower)`; NaN if band width = 0 |
| `true_range` | `max(high−low, |high−prev_close|, |low−prev_close|)` |
| `atr_14` | `mean(true_range, 14)`; `atr_14_ratio = atr_14 / close` |
| `target_return_hd` | `ln(close_{t+h} / close_t)`, h ∈ {1,5,20} |
| `target_price_hd` | `close_{t+h}` |
| `target_o2c_1d` | `ln(close_{t+1} / open_{t+1})` (next session open→close) |

Two Python-specific details the port preserves (and the parity test guards):

- `delta.where(delta > 0, 0)` makes the first delta **0**, so RSI's EWM is
  seeded at 0, not at the first gain.
- `pandas.rank(pct=True)` uses **average ties**: `rank = (less + (equal+1)/2)/n`.

### 7.3 Market and sector features

For each ETF `S ∈ {SPY, QQQ, IWM, XLK, XLF, …}`:

```text
market_S_log_return_wd = ln(close_S,t / close_S,t−w),  w ∈ {1,5,10,20}
vol_S_volatility_20d   = std(log_return_1d, 20)
sector_rel_X_1d/5d     = market_X_log_return_wd − market_SPY_log_return_wd
```

Market features are pivoted by timestamp and left-joined onto stock rows.

### 7.4 Residualization and the excess-return target

```text
resid_return_wd = log_return_wd − market_SPY_log_return_wd      (w ∈ {1,5,10,20})
target_return_hd -= spy_fwd_hd        where spy_fwd_hd = ln(SPY_{t+h}/SPY_t)
target_o2c_1d    -= spy_fwd_o2c_1d    where spy_fwd_o2c_1d = ln(SPY_close_{t+1}/SPY_open_{t+1})
```

`spy_fwd_*` are forward-looking and are **dropped** before any model sees the
frame. Predicting the stock-minus-SPY return removes market beta from the
target, which is why predictions are labelled *excess* return.

### 7.5 Sector dummies, cross-sectional ranks, execution delay

- **Sector dummies:** one-hot `sector_<label>` columns plus `sector_nan` (0 or 1
  on every row) from the sector map.
- **Cross-sectional ranks:** within each timestamp, `rank(pct=True)` of
  `log_return_20d`, `volatility_20d`, `rsi_14` → `*_rank`; when sectors are
  known, the same ranks computed within `(timestamp, sector)` → `*_sector_rank`.
- **Execution delay:** every feature column is shifted forward by
  `prediction_delay` (default 1) **within each symbol**; raw OHLCV and targets
  are never shifted. This guarantees the model at day `t` only sees information
  available at `t−1`.

### 7.6 Parity methodology

`GoldenParityTest` (in the same module) recomputes the whole golden window with
the Java engine and compares **every column** against the Python output with:

- max absolute difference **≤ 1e-9** per feature;
- **identical NaN pattern** (one side NaN and the other not is a failure).

Goldens come from `ml-export/generate_synthetic_golden.py`, which imports the
real `STOCKMARKETANALYSIS/features.py`, runs it on deterministic synthetic bars
and writes `features_golden.csv` (raw bars + Python features),
`market_golden.csv`, `sector_golden.csv` and `meta.properties` (engine settings
+ warm-up). The test skips itself when the files are absent, so the harness is
in place before real data exists. The current run: **73 columns × 780 rows,
within 1e-9**. `ml-export/export_models.py` produces the same files from real
cached data and trained models when the Python environment is available.

---

## 8. Prediction service

### 8.1 Model registry

```text
models/manifest.json            { "activeVersion": "2026-10-01T...", "versions": [...] }
models/<version>/metadata.json  { version, exportedAt,
                                  horizons: { "1": { horizonDays, target,
                                             pooledFeatureList, perStockFeatureList,
                                             artifact, metrics, perStock } } }
models/<version>/pooled_1d.json XGBoost JSON (per horizon)
models/<version>/per_stock/<SYM>_1d.json   optional per-stock models
```

Pooled models load eagerly at startup; per-stock models load lazily into a
bounded **Caffeine** cache (max 2 000) so API pods can carry none and batch pods
load all. Inference builds a one-row `DMatrix` (flattened `float[]`, missing =
`NaN`), is synchronized per booster (XGBoost inference is not re-entrant) and
disposes the matrix. Feature vectors follow `pooledFeatureList` exactly.

### 8.2 Pre-market batch algorithm

Runs at **08:30 ET on trading days** (`stockpredict.predictions.batch-cron`), or
once with `PREDICTION_RUN_BATCH_ONCE=true` (Kubernetes CronJob):

```text
1. asOf  = trading date of the latest bar
2. load bars for all symbols (history-days = 500) + market/sector ETFs
3. feature table = FeatureEngine.compute(stockBars, marketBars)     [§7]
4. keep the latest row per symbol; ETFs are excluded from the universe
5. for each horizon in the registry:
     x_pooled = features[pooledFeatureList]
     p_pooled = pooled.predict(x_pooled)
     if per-stock model exists for the symbol:
        x_stock = features[perStockFeatureList] with pooled_pred = p_pooled
        p_stock = perStock.predict(x_stock)
        p_ensemble = 0.5 · p_pooled + 0.5 · p_stock
     else:
        p_ensemble = p_pooled
     predicted_price = current_close · exp(p_ensemble)
6. upsert predictions (model_version, as_of, symbol, horizon)
7. activate model_registry rows for the version (deactivate older per horizon)
```

Inference is one `DMatrix` per horizon (all symbols in a single JNI call), and
only the final upsert/activate phase runs in a short write transaction — feature
computation, inference and JSON serialization happen outside any transaction.
`pUp` and `confidence` remain null in v1 (the Python intraday classifier is not
exported). Predictions are idempotent per `(model_version, as_of, symbol,
horizon)` via `ON CONFLICT … DO UPDATE`.

### 8.3 Read path

- Served only from Postgres (never inline inference).
- `GET /predictions/intraday` returns horizon 1 ranked by predicted return (or
  `p_up`/`symbol`); **FREE is capped server-side at 20** rows regardless of the
  requested `limit`.
- `stale = true` when `as_of` predates the previous trading day — a missed batch
  serves the last run flagged stale rather than failing.
- `GET /predictions/intraday/{symbol}` returns all horizons plus the stored
  feature snapshot (research transparency).
- `GET /predictions/model-info` returns the active version, export date and CV
  metrics per horizon.

---

## 9. Ranking service

### 9.1 Candidate selection

Nightly at **02:00 ET Tue–Sat** (`stockpredict.ranking.batch-cron`) or once with
`RANKING_RUN_BATCH_ONCE=true`:

```text
candidates = latest 20d predictions ORDER BY predicted_log_return DESC LIMIT 50
selected   = first 10 candidates (top-n)
```

### 9.2 Realized volatility

Annualized sample standard deviation of daily log returns over the trailing
window (default 20 trading days), from `bars_daily` up to the prediction
`as_of`:

```text
σ_annual = std(ln(close_t/close_{t−1}), ddof=1) · √252
```

### 9.3 Inverse-volatility sizing with caps

The sizing algorithm is deterministic and enforces three constraints:
position ≤ **10%**, sector ≤ **25%**, weights sum to **1** (dust < 1% dropped).

```text
w_i = (1/σ_i) / Σ_j (1/σ_j)          # missing/zero σ → median σ
normalize(w)

# 1) sector cap first (hard acceptance constraint), iterated to convergence:
repeat until no sector exceeds 25%:
    for each over-weight sector: scale its members by 25% / sector_total
    normalize(w)

# 2) position cap by water-filling, alternated with the sector cap:
repeat until neither step changes w:
    fix over-cap names at 10%; scale the free names to the remaining budget
    normalize(w); scale over-weight sectors; normalize(w)

# 3) drop dust positions (< 1%), then re-enforce both caps on the
#    survivors (a plain renormalize can push a remaining name/sector
#    back over its cap); repeat until stable
```

The water-filling step converges in at most `n` passes (each pass fixes at
least one name) instead of the slow clip-and-normalize oscillation.

**Infeasibility rule.** When the caps cannot all hold for the selected names
(e.g. 5 of 10 names share one sector: `0.25 + 5×0.10 = 0.75 < 1`), the
**sector cap is kept strictly and the position cap relaxes** to the smallest
feasible spread. Rationale: the acceptance criterion is "weights sum to 1, no
sector above 25%"; a sector breach would fail it, a relaxed position weight does
not.

### 9.4 History, rank change and rationale

Each run is stored per `(run_date, symbol)`; `rank_change` is computed against
the most recent **earlier** run (`previous rank − current rank`, positive =
improved, null for new entries), and `rationale` carries a subset of the
prediction's feature snapshot (`log_return_20d`, `resid_return_20d`,
`volatility_20d`, `rsi_14`, `log_return_20d_rank`). The API serves the latest run
(or a requested date) from this retained history and flags `stale: true` when
that run predates the previous trading day.

Sectors come from an optional JSON map (`stockpredict.ranking.sector-map-file`);
unknown symbols share an `Unknown` bucket so the cap still applies.

---

## 10. Paper trading service

### 10.1 Accounts and watchlist

- One virtual account per user, `$100 000` starting cash; opening is idempotent.
  Reset cancels working orders, clears positions, restores cash and realized P&L.
- Cash updates use **optimistic locking** (`paper_accounts.version`, retry once
  on conflict); cash can never go negative.
- Watchlist is server-enforced: FREE ≤ **10 symbols**, PREMIUM unlimited, with a
  **30-day swap lock** (`watchlist_locked_until`); an unchanged set is always
  allowed and the lock expires automatically. Symbols are deduped, uppercased
  and pattern-validated. **Positions already held remain sellable** even after
  the symbol leaves the watchlist.

### 10.2 Order lifecycle

```text
NEW ──► FILLED | PARTIALLY_FILLED ──► FILLED
  ├──► CANCELLED (client, only while working)
  ├──► REJECTED  (cash/position check at fill time)
  └──► EXPIRED   (DAY orders at 16:05 ET)
```

Partial fills do not exist in v1: every fill consumes the full remaining
quantity, so orders go straight from `NEW` to `FILLED` even though the contract
still advertises `PARTIALLY_FILLED`.

- Types `MARKET | LIMIT | STOP`; time-in-force `DAY | GTC`.
- Optional `Idempotency-Key` per account: a replay returns the original order.
- Validation: LIMIT requires `limitPrice`, STOP requires `stopPrice`, quantity
  > 0; BUY must be in the watchlist (SELL must be in the watchlist **or** a held
  position); BUY cash is checked against `quote × qty × (1 + slippage)`.
- MARKET orders are rejected with `MARKET_CLOSED` outside regular hours unless
  `allow-after-hours` is enabled; LIMIT/STOP are accepted as working orders
  anytime.

### 10.3 Matching engine

A poller (default every 5 s) evaluates working orders against quotes obtained
from the market-data service (`GET /stocks/{symbol}/quote`, short timeout) with
a `bars_daily` latest-close fallback (marked stale):

| Order | Trigger | Fill price |
| --- | --- | --- |
| MARKET BUY | immediate | `last · (1 + slippage_bps/10 000)` |
| MARKET SELL | immediate | `last · (1 − slippage_bps/10 000)` |
| LIMIT BUY | `ask ≤ limit` | `limit` (conservative) |
| LIMIT SELL | `bid ≥ limit` | `limit` |
| STOP BUY | `last ≥ stop` | market fill with slippage |
| STOP SELL | `last ≤ stop` | market fill with slippage |

Fills are **idempotent**: orders in a terminal state are never filled again, and
each evaluation re-reads the order status in the same transaction.

### 10.4 Fill application and P&L

One transaction per fill:

```text
insert fill(order, account, symbol, qty, price, slippage_bps, ts)
update order: filled_quantity += qty; avg_fill_price; status
BUY:  cash   -= qty · price
      avgEntry = (qty_old·avgEntry_old + qty·price) / (qty_old + qty)   # weighted
SELL: cash   += qty · price
      realized_pnl += qty · (price − avgEntry)
      quantity -= qty                    # rejected if it would go negative
      delete the position row when quantity = 0
```

No negative cash and no negative quantity: if the checks fail the order is
`REJECTED` (with reason) instead of partially applied. All ledger mutations per
account serialize through a `SELECT … FOR UPDATE` on the account row (so
multi-replica pollers cannot interleave position writes), with the optimistic
version update as a second guard. Quotes are sanity-checked at the market-data
boundary (`QuoteGuard`: positive bid/ask/last, uncrossed, bounded spread) and
again in the matching engine, so a zero quote can never fill at zero.

Derived values:

```text
unrealized_pnl = Σ qty · (market_price − avg_entry)
equity         = cash + Σ qty · market_price
```

End-of-day snapshots (`equity_snapshots`) are written at 16:05 ET on trading
days and feed the portfolio equity curve.

### 10.5 Reconciliation invariants

A property-based test (jqwik, 50 tries × up to 40 random actions) asserts after
every step of randomized market BUY/SELL sequences:

```text
cash ≥ 0                                  quantity ≥ 0
cash + Σ (qty · avg_entry) = starting_cash + realized_pnl
no duplicate fills, no zero/negative fills
```

These identities catch the classic matching-engine bugs (double fills, lost
cash, sign errors) without enumerating scenarios.

---

## 11. Trading calendar

`common-lib::TradingCalendar` computes the NYSE calendar from published rules —
no external data source:

| Holiday | Rule |
| --- | --- |
| New Year's Day | Jan 1; Sunday → Monday; **Saturday → not observed** (NYSE opens Dec 31) |
| MLK Day | 3rd Monday of January |
| Washington's Birthday | 3rd Monday of February |
| Good Friday | Easter Sunday − 2 (Meeus/Jones/Butcher algorithm) |
| Memorial Day | last Monday of May |
| Juneteenth | Jun 19 from 2022, observed |
| Independence Day | Jul 4, observed |
| Labor Day | 1st Monday of September |
| Thanksgiving | 4th Thursday of November |
| Christmas | Dec 25, observed |

Observed rule: Saturday → preceding Friday, Sunday → following Monday (except
New Year's). Early closes (13:00 ET): the day after Thanksgiving, July 3 and
December 24 when they are trading days. Sessions run 09:30–16:00 ET
(`America/New_York`); ad-hoc closures can be injected. `MarketClock` wraps an
injectable `java.time.Clock` so tests pin time instead of mocking statics.

---

## 12. Persistence schema

All tables ship as Flyway migrations in `db-migrations` (TimescaleDB
hypertables when the extension is present, plain Postgres otherwise).
`symbol_universe` (V6) holds the explicit ingestion/prediction universe; V7
adds the prediction symbol and verification-token indexes.

| Table | Purpose | Key constraints |
| --- | --- | --- |
| `users` | credentials, plan, roles, verification | unique `lower(email)` |
| `refresh_tokens` | hashed tokens, families | unique hash; `revoked_at`, `replaced_by` |
| `login_attempts` | lockout counters | index `(lower(email), attempted_at)` |
| `bars_daily` / `bars_5m` | OHLCV bar store | PK `(symbol, ts)` |
| `model_registry` | active model per horizon | unique active per `horizon_days` |
| `predictions` | precomputed multi-horizon predictions | unique `(version, as_of, symbol, horizon)` |
| `rankings` | nightly top-N history | unique `(run_date, symbol)` |
| `paper_accounts` | cash, realized P&L, version, lock | unique `user_id` |
| `watchlists` | user symbols | unique `(user_id, symbol)` |
| `orders` | full order lifecycle | unique `(account_id, idempotency_key)` |
| `fills` | executions | FK order/account |
| `positions` | quantity ≥ 0, weighted avg entry | unique `(account_id, symbol)` |
| `equity_snapshots` | EOD equity curve | unique `(account_id, snapshot_date)` |

---

## 13. Configuration reference

Everything is externalized (`application.yml` + environment variables); secrets
are never in the chart (External Secrets Operator).

| Property | Default | Meaning |
| --- | --- | --- |
| `stockpredict.jwt.issuer` / `.audience` | `https://auth.stockpredict.local` / `stockpredict-api` | JWT validation |
| `stockpredict.jwt.jwks-uri` | `http://localhost:8081/api/v1/.well-known/jwks.json` | key source |
| `stockpredict.auth.jwt.access-token-ttl` | `PT15M` | access token |
| `stockpredict.auth.jwt.refresh-token-ttl` | `P7D` | refresh token |
| `stockpredict.auth.jwt.login-max-failures` / `.login-window` | `5` / `PT15M` | lockout |
| `stockpredict.auth.jwt.allow-key-generation` | `true` | dev key fallback; set `false` in prod (fail fast) |
| `stockpredict.auth.retention-cron` | `0 30 3 * * *` ET | auth-table pruning |
| `stockpredict.auth.jwt.login-retention-days` / `.token-retention-days` | `30` / `30` | retention windows |
| `stockpredict.market-data.provider` | `auto` | `polygon` if key, else `synthetic` |
| `stockpredict.market-data.daily-backfill-years` | `5` | daily history |
| `stockpredict.market-data.intraday-days` | `30` | 5-minute rolling window |
| `stockpredict.market-data.guard.*` | see §6.3 | quality thresholds (incl. `max-quote-spread-ratio`) |
| `stockpredict.market-data.polygon-connect-timeout` / `.polygon-read-timeout` | `PT5S` / `PT30S` | provider HTTP timeouts |
| `stockpredict.market-data.stream-max-length` | `100000` | `bars.updated` XADD `MAXLEN ~` cap |
| `stockpredict.market-data.daily-scheduling-enabled` | `true` | in-pod nightly ingest; `false` when the CronJob owns it |
| `stockpredict.market-data.allow-on-demand-ingestion` | `true` | cache-miss ingestion limited to known symbols |
| `stockpredict.predictions.model-dir` | `models` | registry root |
| `stockpredict.predictions.free-plan-limit` | `20` | FREE prediction cap |
| `stockpredict.predictions.history-days` | `500` | feature lookback |
| `stockpredict.predictions.horizons` | `1,5,20` | training horizons (must match model metadata) |
| `stockpredict.predictions.batch-cron` | `0 30 8 * * MON-FRI` ET | pre-market batch |
| `stockpredict.predictions.scheduling-enabled` | `true` | in-pod batch; `false` when the CronJob owns it |
| `stockpredict.ranking.top-n` / `.candidate-pool` | `10` / `50` | selection |
| `stockpredict.ranking.max-position-weight` / `.max-sector-weight` | `0.10` / `0.25` | caps |
| `stockpredict.ranking.volatility-window` | `20` | realized vol window |
| `stockpredict.ranking.batch-cron` | `0 0 2 * * TUE-SAT` ET | nightly ranking |
| `stockpredict.ranking.scheduling-enabled` | `true` | in-pod ranking; `false` when the CronJob owns it |
| `stockpredict.paper.starting-cash` | `100000` | virtual account |
| `stockpredict.paper.free-plan-watchlist-limit` | `10` | FREE symbols |
| `stockpredict.paper.watchlist-swap-lock-days` | `30` | swap lock |
| `stockpredict.paper.slippage-bps` | `5` | simulated slippage |
| `stockpredict.paper.match-interval-ms` | `5000` | matching poller |
| `stockpredict.gateway.rate-limit-enabled` | `false` | per-plan Redis limits |
| `stockpredict.gateway.free/premium-requests-per-minute` | `120` / `1200` | rate limits |
| `stockpredict.gateway.trust-forwarded-for` | `false` | trust the first `X-Forwarded-For` entry |
| `stockpredict.gateway.allowed-origins` | `*` | CORS origins (comma list) |
| `stockpredict.security.actuator-public` | `true` | when `false`, `/actuator/prometheus` requires auth |

One-shot batch switches: `MARKET_DATA_RUN_INGEST_ONCE`,
`PREDICTION_RUN_BATCH_ONCE`, `RANKING_RUN_BATCH_ONCE`, `PAPER_RUN_MATCH_ONCE` —
each runs the job and exits 0/1 for Kubernetes CronJobs.

---

## 14. Build, run, test

```bash
./dev.sh build                 # compile + unit tests + JaCoCo (no host installs)
./dev.sh test                  # unit tests only
./dev.sh test-integration      # + Testcontainers integration tests (docker.sock)
./dev.sh up                    # Postgres 16+TimescaleDB, Redis 7, WireMock stubs
./dev.sh boot :services:auth-service:bootRun          # one service
./dev.sh boot <tasks...> --parallel                   # full stack
./dev.sh down                  # stop infra
```

`./gradlew` is committed for CI or machines with JDK 21 installed. Boot ports are
mapped to the host as `18080–18085` (gateway → auth → market-data → prediction →
ranking → paper) to avoid clashes with a host service on 8080.

Test layers:

```bash
./dev.sh :libs:feature-engine:test                       # + golden parity (≤1e-9)
./dev.sh test-integration                                # Testcontainers (needs docker.sock)
./dev.sh :tests:e2e:test -Pe2eBaseUrl=http://localhost:18080 -Pe2eToken=<jwt> \
    -Pe2eVerifyToken=<token-from-email-logs>             # self-sufficient register→verify→login journey
./dev.sh :tests:perf:run --args="--url=http://localhost:8080 --users=500 --seconds=30 --token=<jwt>"
./tests/security/zap-baseline.sh http://localhost:18080
```

The perf client now defaults to an authenticated read endpoint
(`/api/v1/predictions/intraday?limit=20`); without `--token` it warns that the
run measures the unauthenticated path.

---

## 15. Validation gates

| Gate | Scope | Result (local run) |
| --- | --- | --- |
| **G1** | every service green in isolation | Build + **204 tests** green, 7 skipped without external infra, 0 failures. Coverage: feature-engine **96.6%**; auth **85.9%**, market-data 69.8%, ranking 57.2%, prediction 53.4%, common-lib 46.0%, paper 32.6%, gateway 15.1% — **below the ≥80% target** for most modules |
| **G2** | staging e2e + ML parity | Local dry run: full stack healthy; **6/6 e2e journeys** through the gateway; **ML parity ≤1e-9** (73 columns × 780 rows). Real staging not available here |
| **G3** | hardening | Load: **500 users → 15.2k RPS, p95 6 ms, 0% errors** (target 50 RPS / p95 ≤ 300 ms). ZAP baseline: **0 FAIL, 1 WARN**. Trivy: CI (blocked locally by a Maven Central rate limit). Multi-cloud smoke: requires cloud credentials |
| **UAT** | 2 weeks internal testers | Not runnable here |

A post-review hardening pass addressed the findings in `GLM.review` (per-finding
status in Appendix D there): session-date convention, contract validation, Helm
CronJobs, quote sanity, ledger serialization, refresh-rotation race, incremental
ingestion, batched inference and the shared JWT core. G1 was re-measured after
that pass; the G2/G3 numbers are the earlier local dry run and still need a
re-run on real staging/load infrastructure.

Acceptance-criteria coverage: passwords Argon2id + lockout + token rejection
(§5); predictions present with model version and stale flag (§8); charts
gap-free and split-adjusted per provider (§6); top-10 deterministic with caps
(§9); FREE-plan 11th symbol rejected through every path and reconciliation
property tests (§10); disclaimer and data-source attribution on every response.

---

## 16. Infrastructure and CI

- **Helm** (`infra/helm/stockpredict`): one Deployment/Service per service, HPA +
  PDB for the gateway, one-shot CronJobs for the three batch jobs (ET timezone,
  model PVC, provider secret, per-job resources, `securityContext`), ExternalSecret
  for cloud secret managers, liveness/readiness probes on
  `/api/v1/actuator/health/*`. In-pod batch schedules are disabled in prod
  (`*_SCHEDULING_ENABLED=false`) so the CronJobs are the single execution model.
- **Terraform** (`infra/terraform/{gcp,aws,azure}`): GKE + Cloud SQL +
  Memorystore + GCS; EKS + RDS + ElastiCache + S3; AKS + Flexible Server +
  Azure Cache + Blob, each with secret-manager wiring.
- **Observability**: Micrometer/Prometheus on every service
  (`/actuator/prometheus`); OpenTelemetry agent injection is platform-level.
- **CI** (`.github/workflows/ci.yml`): `permissions: contents: read`; build +
  unit tests, Testcontainers integration, OpenAPI breaking-change check
  (`oasdiff/oasdiff-action`, pinned), Trivy (HIGH/CRITICAL), Jib image tars on
  `main`, and — when `E2E_BASE_URL` is configured — the e2e suite and an OWASP
  ZAP baseline scan.

---

## 17. Architecture decision records

`docs/adr/`: 0001 monorepo/Gradle, 0002 contract-first OpenAPI, 0003 Java
serves/Python trains, 0004 batch-precomputed predictions, 0005 in-house paper
trading, 0006 Argon2id + RS256 JWT, 0007 cloud-portable K8s/Terraform, 0008
Docker dev sandbox, 0009 shared JWT verification core, 0010 daily-bar timestamp
convention.

---

## 18. Known limitations & remaining work

**Code-level gaps**

- Market-data has Polygon + synthetic adapters; an Alpaca adapter is not
  implemented.
- The model registry reads the local filesystem; the object-storage
  (GCS/S3/Blob) interface is not wired.
- Prediction reads are not yet Redis-cached (`cache-ttl-seconds` is reserved).
- `pUp`/`confidence` are null — the Python intraday classifier is not exported.
- Ranking sectors need a `sectors.json` export from the Python fundamentals.
- `bars.updated` is trimmed but still has no consumer; it is published for the
  future marking/cache-invalidation use case.
- `predictedPrice`/`predictedPctChange` are excess-return values (a "SPY flat"
  assumption), documented in the contract; there is no absolute-price forecast.
- Contract tests (Spring Cloud Contract/Pact) and resilience tests
  (Toxiproxy, pod-kill) are not implemented; e2e/perf/security/oasdiff are.
- Test coverage is below the plan's 80% target for most modules; promoting
  `InMemoryPaperStore` to a Testcontainers fixture for multi-threaded ledger
  races is the highest-value next test investment.

**Operational / external**

- Real provider keys and a licensed market-data redistribution agreement.
- Training on real data, running `ml-export`, and publishing models + goldens.
- Staging integration on a real cluster (G2) and multi-cloud smoke (G3); the
  self-sufficient e2e journey needs a verification-token hook in staging.
- 30-minute soak and authenticated mixed load at the plan's targets.
- UAT (internal testers) and legal review (data licence, investment-advice).

---

*Research and educational project. Not financial advice.*

Released under the [MIT License](LICENSE).
