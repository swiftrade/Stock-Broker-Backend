#!/usr/bin/env python3
"""Generate golden feature files from the REAL Python feature pipeline.

Unlike ``export_models.py`` (which reads cached Alpaca data and trained
models), this script needs no market-data cache: it generates deterministic
synthetic bars, runs ``STOCKMARKETANALYSIS/features.py`` on them and writes the
golden files consumed by the Java ``GoldenParityTest``. It is the train/serve
skew gate for environments where the real data cache is unavailable.

Requires: pandas, numpy (no xgboost).

    python generate_synthetic_golden.py --output ../libs/feature-engine/src/test/resources/golden
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

DEFAULT_ANALYSIS_DIR = Path(__file__).resolve().parents[2] / "STOCKMARKETANALYSIS"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "libs" / "feature-engine" / "src" / "test" / "resources" / "golden"

STOCKS = [("AAA", 100.0, 0.30, "Information Technology"),
          ("BBB", 50.0, -0.10, "Health Care"),
          ("CCC", 200.0, 0.05, "Financials")]
MARKET = [("SPY", 400.0, 0.20), ("QQQ", 350.0, 0.25), ("IWM", 200.0, 0.15)]
SECTORS = [("XLK", 180.0, 0.22), ("XLF", 40.0, 0.10), ("XLV", 140.0, 0.12)]

DAYS = 260
WARMUP = 60


def synthetic_bars(symbol: str, base: float, drift: float, days: int) -> pd.DataFrame:
    """Deterministic OHLCV bars with per-symbol phase (no randomness)."""
    phase = (sum(ord(c) for c in symbol) % 17) / 5.0
    rows = []
    start = pd.Timestamp("2025-01-02 21:00:00", tz="UTC")
    for i in range(days):
        close = base + drift * i + 0.9 * np.sin(i / 3.0 + phase) + 0.4 * np.cos(i / 7.0 + phase)
        open_ = close - 0.3 * np.sin(i / 2.0 + phase)
        high = max(open_, close) + 0.4 + 0.1 * np.cos(i)
        low = min(open_, close) - 0.4 - 0.1 * np.sin(i)
        volume = 1_000_000 + 25_000 * ((i * 7 + int(phase * 10)) % 13) + i * 500
        rows.append({
            "symbol": symbol,
            "timestamp": start + pd.Timedelta(days=i),
            "open": round(open_, 6),
            "high": round(high, 6),
            "low": round(low, 6),
            "close": round(close, 6),
            "volume": int(volume),
        })
    return pd.DataFrame(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--analysis-dir", type=Path, default=DEFAULT_ANALYSIS_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--days", type=int, default=DAYS)
    parser.add_argument("--warmup", type=int, default=WARMUP)
    args = parser.parse_args()

    sys.path.insert(0, str(args.analysis_dir))
    from config import get_config  # noqa: E402
    from features import build_feature_dataset, get_feature_columns_for_horizon  # noqa: E402

    config = get_config()
    config.horizons = [1, 5, 20]
    config.prediction_delay = 1
    config.residualize_features = True
    config.use_excess_return_target = True
    config.market_symbols = ["SPY", "QQQ", "IWM"]
    config.sector_etfs = [s for s, _, _ in SECTORS]

    stock_df = pd.concat([synthetic_bars(s, b, d, args.days) for s, b, d, _ in STOCKS],
                         ignore_index=True)
    market_df = pd.concat([synthetic_bars(s, b, d, args.days)
                           for s, b, d in MARKET + SECTORS], ignore_index=True)
    fundamentals = pd.DataFrame([{"symbol": s, "sector": sector} for s, _, _, sector in STOCKS])

    features = build_feature_dataset(stock_df, market_df, fundamentals, config)
    features["timestamp"] = pd.to_datetime(features["timestamp"], utc=True)

    span = args.days
    frames = []
    for symbol, _, _, _ in STOCKS:
        frames.append(features[features["symbol"] == symbol].sort_values("timestamp").tail(span))
    golden = pd.concat(frames, ignore_index=True)
    golden["timestamp"] = golden["timestamp"].dt.strftime("%Y-%m-%dT%H:%M:%SZ")

    feature_cols = get_feature_columns_for_horizon(features, config.horizons[0])
    raw_cols = ["symbol", "timestamp", "open", "high", "low", "close", "volume"]
    available_raw = [c for c in raw_cols if c in golden.columns]
    available_features = [c for c in feature_cols if c in golden.columns]

    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    golden[available_raw + available_features].to_csv(output / "features_golden.csv", index=False)

    window_start = pd.to_datetime(golden["timestamp"], utc=True).min()
    window_end = pd.to_datetime(golden["timestamp"], utc=True).max()
    market = market_df.copy()
    market["timestamp"] = pd.to_datetime(market["timestamp"], utc=True)
    market = market[(market["timestamp"] >= window_start) & (market["timestamp"] <= window_end)].copy()
    market["timestamp"] = market["timestamp"].dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    market[["symbol", "timestamp", "open", "high", "low", "close", "volume"]].to_csv(
        output / "market_golden.csv", index=False)

    fundamentals.to_csv(output / "sector_golden.csv", index=False)

    meta_lines = [
        f"warmup={args.warmup}",
        f"predictionDelay={config.prediction_delay}",
        "horizons=" + ",".join(str(h) for h in config.horizons),
        f"residualizeFeatures={str(config.residualize_features).lower()}",
        f"useExcessReturnTarget={str(config.use_excess_return_target).lower()}",
        "marketSymbols=" + ",".join(config.market_symbols),
        "sectorEtfs=" + ",".join(config.sector_etfs),
    ]
    (output / "meta.properties").write_text("\n".join(meta_lines) + "\n")

    print(f"Wrote golden files to {output}")
    print(f"  rows={len(golden)} features={len(available_features)} "
          f"warmup={args.warmup} days={span}")


if __name__ == "__main__":
    main()
