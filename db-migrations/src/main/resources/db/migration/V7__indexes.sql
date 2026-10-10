-- M13: indexes for hot lookup paths missing from V1/V3.

CREATE INDEX ix_predictions_symbol_asof ON predictions (symbol, as_of DESC);

CREATE INDEX ix_users_verification_token ON users (verification_token_hash) WHERE verification_token_hash IS NOT NULL;
