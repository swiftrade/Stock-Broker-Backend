-- Long-term ranking domain: nightly top-N recommendations with full history.

CREATE TABLE rankings (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_date            date NOT NULL,
    model_version       text NOT NULL,
    symbol              text NOT NULL,
    rank                integer NOT NULL,
    rank_change         integer,
    predicted_20d_return double precision NOT NULL,
    volatility          double precision,
    sector              text,
    weight              double precision NOT NULL,
    rationale           jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_date, symbol)
);

CREATE INDEX ix_rankings_run ON rankings (run_date DESC, rank);
