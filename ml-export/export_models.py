#!/usr/bin/env python3
"""A9 ML bridge: export the Python XGBoost models for Java serving.

This script runs inside the existing Python pipeline environment
(``STOCKMARKETANALYSIS/.venv``) and produces:

1. ``models/<version>/pooled_<h>d.json`` (+ optional per-stock JSON)
2. ``models/<version>/metadata.json`` - feature list, horizons, CV metrics,
   trained-at timestamp, artifact URIs (the Java model registry reads this).
3. ``models/<version>/manifest.json`` - all versions/horizons in one place.
4. With ``--generate-golden``: ``golden/features_golden.csv`` and
   ``golden/predictions_golden.csv`` used by the Java parity tests
   (max abs diff <= 1e-9 features, <= 1e-6 predictions).

Example (from the JAVAWEBSERVICE/ml-export directory):

    ../../STOCKMARKETANALYSIS/.venv/bin/python export_models.py \
        --generate-golden --golden-symbols 20 --golden-days 500
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

DEFAULT_ANALYSIS_DIR = Path(__file__).resolve().parents[2] / "STOCKMARKETANALYSIS"
DEFAULT_MODELS = DEFAULT_ANALYSIS_DIR / "models" / "sp500_models_v2.joblib"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models-path", type=Path, default=DEFAULT_MODELS,
                        help="Saved joblib models from STOCKMARKETANALYSIS (main.py).")
    parser.add_argument("--analysis-dir", type=Path, default=DEFAULT_ANALYSIS_DIR,
                        help="STOCKMARKETANALYSIS project root (for data + feature code).")
    parser.add_argument("--output-dir", type=Path, default=Path("models"),
                        help="Directory for exported JSON + metadata.")
    parser.add_argument("--version", type=str, default=None,
                        help="Model version label (default: UTC timestamp).")
    parser.add_argument("--include-per-stock", action="store_true",
                        help="Also export the ~1500 per-stock models (slow, many files).")
    parser.add_argument("--generate-golden", action="store_true",
                        help="Produce the golden feature/prediction files for Java parity tests.")
    parser.add_argument("--golden-dir", type=Path, default=Path("golden"))
    parser.add_argument("--golden-symbols", type=int, default=20)
    parser.add_argument("--golden-days", type=int, default=500)
    parser.add_argument("--golden-warmup", type=int, default=60,
                        help="Extra history rows so rolling windows match at the window start.")
    return parser.parse_args()


def export_models(models_path: Path, output_dir: Path, version: str,
                  include_per_stock: bool) -> dict:
    if not models_path.exists():
        raise SystemExit(
            f"Model file not found: {models_path}\n"
            "Train the Python pipeline first: cd STOCKMARKETANALYSIS && .venv/bin/python main.py")

    all_models = joblib.load(models_path)
    version_dir = output_dir / version
    version_dir.mkdir(parents=True, exist_ok=True)

    metadata: dict = {
        "version": version,
        "exportedAt": datetime.now(timezone.utc).isoformat(),
        "sourceModels": str(models_path),
        "horizons": {},
    }

    for horizon, bundle in all_models.items():
        target_col = bundle["target_col"]
        feature_cols = list(bundle["feature_cols"])
        pooled_feature_list = [c for c in feature_cols if c != "pooled_pred"]
        metrics_df = bundle.get("metrics_df")
        metrics = {}
        if metrics_df is not None and not metrics_df.empty:
            metrics = {
                "directionAccuracy": float(metrics_df["direction_accuracy"].mean()),
                "rmse": float(metrics_df["rmse"].mean()),
                "perStockModels": int(len(metrics_df)),
            }

        pooled_path = version_dir / f"pooled_{horizon}d.json"
        bundle["pooled_model"].get_booster().save_model(str(pooled_path))

        entry = {
            "horizonDays": int(horizon),
            "target": target_col,
            "pooledFeatureList": pooled_feature_list,
            "perStockFeatureList": feature_cols,
            "artifact": pooled_path.name,
            "metrics": metrics,
            "perStock": {},
        }

        if include_per_stock:
            per_stock_dir = version_dir / "per_stock"
            per_stock_dir.mkdir(exist_ok=True)
            for symbol, model in bundle.get("per_stock_models", {}).items():
                safe = symbol.replace(".", "_")
                path = per_stock_dir / f"{safe}_{horizon}d.json"
                model.get_booster().save_model(str(path))
                entry["perStock"][symbol] = str(path.relative_to(version_dir))

        metadata["horizons"][str(horizon)] = entry
        print(f"Exported horizon {horizon}d -> {pooled_path} "
              f"({len(pooled_feature_list)} pooled features, {len(entry['perStock'])} per-stock)")

    metadata_path = version_dir / "metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2))

    # Preserve previously exported versions instead of replacing the manifest
    # with a single entry (L15).
    manifest_path = output_dir / "manifest.json"
    versions: list[str] = []
    if manifest_path.exists():
        try:
            existing = json.loads(manifest_path.read_text())
            versions = [v for v in existing.get("versions", []) if v != version]
        except (json.JSONDecodeError, OSError):
            versions = []
    versions.append(version)
    manifest = {"activeVersion": version, "versions": versions}
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"Wrote {metadata_path} and {manifest_path} ({len(versions)} versions)")
    return metadata


def generate_golden(analysis_dir: Path, golden_dir: Path, symbols: int, days: int,
                    warmup: int = 60) -> None:
    """Build golden features + predictions from cached Python data.

    Writes ``days + warmup`` rows per symbol: the Java parity test recomputes
    the whole window and compares only rows after the warm-up (rolling windows
    need history before the comparison starts).
    """
    sys.path.insert(0, str(analysis_dir))
    from config import get_config  # noqa: E402
    from data_loader import load_market_data, load_raw_data, load_fundamentals  # noqa: E402
    from features import build_feature_dataset, get_feature_columns_for_horizon  # noqa: E402

    config = get_config()
    stock_df = load_raw_data(config)
    market_df = load_market_data(config)
    try:
        fundamentals = load_fundamentals(config)
    except FileNotFoundError:
        fundamentals = pd.DataFrame()

    features = build_feature_dataset(stock_df, market_df, fundamentals, config)
    features["timestamp"] = pd.to_datetime(features["timestamp"], utc=True)

    counts = features.groupby("symbol")["timestamp"].count().sort_values(ascending=False)
    chosen = counts.head(symbols).index.tolist()
    golden_dir.mkdir(parents=True, exist_ok=True)

    span = days + warmup
    frames = []
    for symbol in chosen:
        symbol_df = features[features["symbol"] == symbol].sort_values("timestamp").tail(span)
        frames.append(symbol_df)
    golden = pd.concat(frames, ignore_index=True)
    golden["timestamp"] = golden["timestamp"].dt.strftime("%Y-%m-%dT%H:%M:%SZ")

    feature_cols = get_feature_columns_for_horizon(features, config.horizons[0])
    raw_cols = ["symbol", "timestamp", "open", "high", "low", "close", "volume"]
    available_raw = [c for c in raw_cols if c in golden.columns]
    available_features = [c for c in feature_cols if c in golden.columns]
    features_path = golden_dir / "features_golden.csv"
    golden[available_raw + available_features].to_csv(features_path, index=False)
    print(f"Wrote {features_path} ({len(golden)} rows, {len(available_features)} features)")

    # Market bars over the same window (for market/sector features).
    market = market_df.copy()
    market["timestamp"] = pd.to_datetime(market["timestamp"], utc=True)
    window_start = pd.to_datetime(golden["timestamp"], utc=True).min()
    window_end = pd.to_datetime(golden["timestamp"], utc=True).max()
    market = market[(market["timestamp"] >= window_start) & (market["timestamp"] <= window_end)].copy()
    market["timestamp"] = market["timestamp"].dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    market_path = golden_dir / "market_golden.csv"
    market[["symbol", "timestamp", "open", "high", "low", "close", "volume"]].to_csv(market_path, index=False)
    print(f"Wrote {market_path} ({len(market)} rows)")

    # Sector labels (for sector dummies / sector ranks).
    if not fundamentals.empty and "sector" in fundamentals.columns:
        sectors = fundamentals[["symbol", "sector"]].dropna()
        sector_path = golden_dir / "sector_golden.csv"
        sectors.to_csv(sector_path, index=False)
        print(f"Wrote {sector_path} ({len(sectors)} rows)")

    # Engine settings so the Java side recomputes with identical semantics.
    meta_lines = [
        f"warmup={warmup}",
        f"predictionDelay={config.prediction_delay}",
        "horizons=" + ",".join(str(h) for h in config.horizons),
        f"residualizeFeatures={str(config.residualize_features).lower()}",
        f"useExcessReturnTarget={str(config.use_excess_return_target).lower()}",
        "marketSymbols=" + ",".join(config.market_symbols),
        "sectorEtfs=" + ",".join(config.sector_etfs),
    ]
    (golden_dir / "meta.properties").write_text("\n".join(meta_lines) + "\n")
    print(f"Wrote {golden_dir / 'meta.properties'}")

    # Predictions golden: requires the saved models.
    if DEFAULT_MODELS.exists():
        all_models = joblib.load(DEFAULT_MODELS)
        records = []
        for horizon, bundle in all_models.items():
            cols = [c for c in bundle["feature_cols"] if c in golden.columns and c != "pooled_pred"]
            if not cols:
                continue
            data = golden.dropna(subset=cols).copy()
            pooled_pred = bundle["pooled_model"].predict(data[cols].to_numpy())
            for i, (_, row) in enumerate(data.iterrows()):
                records.append({
                    "symbol": row["symbol"],
                    "timestamp": row["timestamp"],
                    "horizon_days": int(horizon),
                    "pooled_pred": float(pooled_pred[i]),
                    "close": float(row["close"]),
                })
        pred_path = golden_dir / "predictions_golden.csv"
        pd.DataFrame(records).to_csv(pred_path, index=False)
        print(f"Wrote {pred_path} ({len(records)} rows)")
    else:
        print("Skipping predictions golden: no saved models found.")


def main() -> None:
    args = parse_args()
    version = args.version or time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    export_models(args.models_path, args.output_dir, version, args.include_per_stock)
    if args.generate_golden:
        generate_golden(args.analysis_dir, args.golden_dir, args.golden_symbols,
                        args.golden_days, args.golden_warmup)


if __name__ == "__main__":
    main()
