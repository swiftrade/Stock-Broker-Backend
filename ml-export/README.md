# ML bridge (A9)

Exports the trained Python models from `STOCKMARKETANALYSIS/` for Java serving,
and produces the golden datasets that guard against train/serve skew.

```bash
# from JAVAWEBSERVICE/ml-export, using the analysis project's venv
../../STOCKMARKETANALYSIS/.venv/bin/python export_models.py \
    --generate-golden --golden-symbols 20 --golden-days 500
```

Outputs:

| Path | Purpose |
| --- | --- |
| `models/<version>/pooled_<h>d.json` | XGBoost model JSON (loaded with XGBoost4J) |
| `models/<version>/metadata.json` | Feature list, horizon, CV metrics, trained-at |
| `models/manifest.json` | Active version pointer |
| `golden/features_golden.csv` | Raw OHLCV + Python feature values (parity ≤1e-9) |
| `golden/predictions_golden.csv` | Python predictions (parity ≤1e-6) |

The Java side reads `metadata.json` to order and name features; feature names are
**never** hard-coded in Java. The `GoldenParityTest` in `libs/feature-engine`
skips itself until `golden/features_golden.csv` exists.

Retraining: schedule `main.py` (Python) weekly, then run this export and publish
`models/<version>/` to object storage (GCS/S3/Blob) via the model registry
interface in A4.
