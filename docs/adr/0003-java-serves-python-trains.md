# ADR 0003 — Java serves, Python trains (v1)

**Status:** accepted (Phase 0)

**Context.** The existing Python pipeline trains ~1,164 XGBoost models and is
already validated; porting training to Java would delay launch and duplicate
research tooling.

**Decision.** Python remains the training environment. `ml-export` (A9) exports
pooled + per-stock models as XGBoost JSON with a metadata sidecar (feature list,
horizon, CV metrics, trained-at). Java loads them with XGBoost4J. Feature
engineering is ported to a pure-Java library (`libs/feature-engine`, A3) whose
output must match Python within 1e-9 on golden files.

**Consequences.** Train/serve skew is the top schedule risk and is gated in CI
by golden-file parity; A3 is on the critical path (A3 → A4 → A5). A future
Phase 4 may port training to Java.
