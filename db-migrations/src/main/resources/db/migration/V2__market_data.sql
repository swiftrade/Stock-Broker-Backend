-- Market data: daily and 5-minute bars.
-- TimescaleDB is used when the extension is available (timescale/timescaledb
-- image); plain Postgres 16 is supported for portability (hypertables skipped).

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb') THEN
        CREATE EXTENSION IF NOT EXISTS timescaledb;
    END IF;
END $$;

CREATE TABLE bars_daily (
    symbol      text NOT NULL,
    ts          timestamptz NOT NULL,
    open        numeric(20, 6) NOT NULL,
    high        numeric(20, 6) NOT NULL,
    low         numeric(20, 6) NOT NULL,
    close       numeric(20, 6) NOT NULL,
    volume      bigint NOT NULL DEFAULT 0,
    vwap        numeric(20, 6),
    trade_count bigint,
    source      text NOT NULL DEFAULT 'polygon',
    ingested_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, ts)
);

CREATE TABLE bars_5m (LIKE bars_daily INCLUDING ALL);

CREATE INDEX ix_bars_daily_ts ON bars_daily (ts DESC);
CREATE INDEX ix_bars_5m_ts ON bars_5m (ts DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('bars_daily', 'ts',
                                  chunk_time_interval => interval '1 year',
                                  if_not_exists => true, migrate_data => true);
        PERFORM create_hypertable('bars_5m', 'ts',
                                  chunk_time_interval => interval '7 days',
                                  if_not_exists => true, migrate_data => true);
    END IF;
END $$;
