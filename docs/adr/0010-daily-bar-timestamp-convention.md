# ADR 0010 — Daily-bar timestamp convention (00:00Z of the session date)

**Status:** accepted (post-review hardening)

**Context.** Daily bars were stored at `00:00Z` of the session date (Polygon
day aggregates; synthetic provider), but consumers interpreted that timestamp
differently: market-data read it as a UTC date, while prediction/ranking
converted it to `America/New_York`, yielding the previous calendar day. The
prediction `as_of` therefore landed on non-trading days and the `stale` flag was
permanently true in production timing.

**Decision.** One convention, centralized in
`common-lib::BarTimestamps`: a daily bar for session date `D` is stored at
`D 00:00Z`; `sessionDateOfDailyBar` reads it back as a UTC date; trailing
windows end at `(asOf + 1) 00:00Z` (exclusive). `bars_5m` keeps real ET
instants and is interpreted in `MARKET_ZONE`. Polygon daily bars are normalized
to the convention at ingestion. `TradingCalendar.sessionDate(Instant)` (ET
interpretation) was removed to prevent reuse.

**Consequences.** `as_of` equals the session date for all providers; staleness
and the ranking volatility window align with the features actually used. Any
new consumer must go through `BarTimestamps` rather than ad-hoc date conversion.
