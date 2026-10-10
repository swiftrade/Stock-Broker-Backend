-- Explicit ingestion/prediction universe, decoupled from bars_daily.
-- Only scheduled/CronJob ingestion registers symbols here; on-demand
-- (user-requested) symbols are stored in bars_* but never registered.

CREATE TABLE symbol_universe (
    symbol   text PRIMARY KEY,
    source   text NOT NULL DEFAULT 'universe',
    added_at timestamptz NOT NULL DEFAULT now()
);
