-- Paper trading domain: accounts, watchlists, orders, fills, positions,
-- end-of-day equity snapshots. No shorting in v1 (positions.quantity >= 0).

CREATE TABLE paper_accounts (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    cash             numeric(20, 4) NOT NULL,
    starting_cash    numeric(20, 4) NOT NULL DEFAULT 100000,
    realized_pnl     numeric(20, 4) NOT NULL DEFAULT 0,
    status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    watchlist_locked_until timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE watchlists (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    symbol   text NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, symbol)
);

CREATE TABLE orders (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       uuid NOT NULL REFERENCES paper_accounts (id) ON DELETE CASCADE,
    symbol           text NOT NULL,
    side             text NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type             text NOT NULL CHECK (type IN ('MARKET', 'LIMIT', 'STOP')),
    quantity         numeric(20, 6) NOT NULL CHECK (quantity > 0),
    limit_price      numeric(20, 6),
    stop_price       numeric(20, 6),
    time_in_force    text NOT NULL DEFAULT 'DAY' CHECK (time_in_force IN ('DAY', 'GTC')),
    status           text NOT NULL DEFAULT 'NEW'
                     CHECK (status IN ('NEW', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED')),
    filled_quantity  numeric(20, 6) NOT NULL DEFAULT 0,
    avg_fill_price   numeric(20, 6),
    reject_reason    text,
    idempotency_key  text,
    submitted_at     timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz,
    UNIQUE (account_id, idempotency_key)
);

CREATE INDEX ix_orders_account_time ON orders (account_id, submitted_at DESC);
CREATE INDEX ix_orders_status ON orders (status) WHERE status IN ('NEW', 'PARTIALLY_FILLED');

CREATE TABLE fills (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     uuid NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    account_id   uuid NOT NULL REFERENCES paper_accounts (id) ON DELETE CASCADE,
    symbol       text NOT NULL,
    quantity     numeric(20, 6) NOT NULL CHECK (quantity > 0),
    price        numeric(20, 6) NOT NULL,
    slippage_bps double precision NOT NULL DEFAULT 0,
    filled_at    timestamptz NOT NULL DEFAULT now(),
    quote_ts     timestamptz
);

CREATE INDEX ix_fills_order ON fills (order_id);

CREATE TABLE positions (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      uuid NOT NULL REFERENCES paper_accounts (id) ON DELETE CASCADE,
    symbol          text NOT NULL,
    quantity        numeric(20, 6) NOT NULL CHECK (quantity >= 0),
    avg_entry_price numeric(20, 6) NOT NULL,
    realized_pnl    numeric(20, 4) NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, symbol)
);

CREATE TABLE equity_snapshots (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      uuid NOT NULL REFERENCES paper_accounts (id) ON DELETE CASCADE,
    snapshot_date   date NOT NULL,
    equity          numeric(20, 4) NOT NULL,
    cash            numeric(20, 4) NOT NULL,
    positions_value numeric(20, 4) NOT NULL,
    unrealized_pnl  numeric(20, 4) NOT NULL DEFAULT 0,
    realized_pnl    numeric(20, 4) NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, snapshot_date)
);
