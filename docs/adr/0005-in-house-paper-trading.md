# ADR 0005 — In-house paper-trading engine (no per-user broker accounts)

**Status:** accepted (Phase 0)

**Context.** Freemium paper trading must isolate users and avoid broker
onboarding, KYC and per-account API limits.

**Decision.** Simulated fills live in our database: `paper_accounts`,
`watchlists`, `orders`, `fills`, `positions`, `equity_snapshots`. Market orders
fill at the latest quote ± configurable slippage; limit/stop orders are
evaluated by a scheduled quote poller; fills are idempotent. Cash updates use
optimistic locking (`paper_accounts.version`); no negative cash; no shorting in
v1. The watchlist gate (FREE = 10 symbols, 30-day swap lock) is enforced
server-side on every path.

**Consequences.** Full control over the UX and limits; we own matching-engine
correctness, enforced by property-based reconciliation tests (1,000 randomized
order sequences).
