#!/usr/bin/env bash
# OWASP ZAP baseline scan against a running stack (A8 security layer).
#
#   ./tests/security/zap-baseline.sh http://localhost:8080
#
# The same scan runs in CI (see .github/workflows/ci.yml) against the staging
# URL. Reports are written to tests/security/reports/.
set -euo pipefail

TARGET="${1:-http://localhost:8080}"
REPORT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reports"
mkdir -p "$REPORT_DIR"

docker run --rm --network host \
  -v "$REPORT_DIR":/zap/wrk:rw \
  ghcr.io/zaproxy/zaproxy:stable \
  zap-baseline.py -t "$TARGET" -m 3 -r zap-baseline.html -J zap-baseline.json -I

echo "ZAP baseline complete: $REPORT_DIR/zap-baseline.html"
