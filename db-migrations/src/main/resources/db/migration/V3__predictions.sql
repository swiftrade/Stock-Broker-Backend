-- Prediction domain: model registry and precomputed predictions.

CREATE TABLE model_registry (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version               text NOT NULL UNIQUE,
    horizon_days          integer NOT NULL,
    trained_at            timestamptz NOT NULL,
    cv_direction_accuracy double precision,
    cv_rmse               double precision,
    cv_folds              integer,
    feature_list          jsonb NOT NULL DEFAULT '[]'::jsonb,
    artifact_uri          text NOT NULL,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    active                boolean NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL DEFAULT now()
);

-- At most one active model per horizon.
CREATE UNIQUE INDEX ux_model_registry_active_horizon
    ON model_registry (horizon_days) WHERE active;

CREATE TABLE predictions (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_version       text NOT NULL,
    as_of               date NOT NULL,
    symbol              text NOT NULL,
    horizon_days        integer NOT NULL,
    predicted_log_return double precision NOT NULL,
    p_up                double precision,
    confidence          double precision,
    current_close       numeric(20, 6),
    predicted_price     numeric(20, 6),
    features            jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (model_version, as_of, symbol, horizon_days)
);

CREATE INDEX ix_predictions_asof
    ON predictions (as_of DESC, horizon_days, predicted_log_return DESC);
