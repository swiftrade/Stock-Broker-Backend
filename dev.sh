#!/usr/bin/env bash
# Docker-based development sandbox for the Stock Prediction webservice.
#
# Nothing is installed on the host: JDK 21 + Gradle run inside the
# `gradle:8.10.2-jdk21` image, and the dependency cache lives in a named
# Docker volume (`stockpredict-gradle-cache`).
#
# Usage:
#   ./dev.sh build [gradle args...]     # gradle build
#   ./dev.sh test  [gradle args...]     # unit tests
#   ./dev.sh test-integration           # tests + Testcontainers (mounts docker.sock)
#   ./dev.sh <task> [args...]           # any gradle task, e.g. ./dev.sh :services:auth-service:bootRun
#   ./dev.sh shell                      # interactive shell in the sandbox
#   ./dev.sh up / down / logs / ps      # local infra (Postgres+TimescaleDB, Redis, WireMock)
#   ./dev.sh clean                      # stop infra and delete the Gradle cache volume
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="${GRADLE_IMAGE:-stockpredict-gradle-sandbox:8.10.2-jdk21}"
CACHE_VOLUME="${GRADLE_CACHE_VOLUME:-stockpredict-gradle-cache}"
UID_GID="$(id -u):$(id -g)"

ensure_image() {
  if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "[dev.sh] building sandbox image $IMAGE (JDK 21 + Gradle + libgomp)..."
    docker build -q -t "$IMAGE" "$ROOT/docker/dev" >/dev/null
  fi
}

ensure_cache_volume() {
  docker volume inspect "$CACHE_VOLUME" >/dev/null 2>&1 || docker volume create "$CACHE_VOLUME" >/dev/null
  if ! docker run --rm -v "$CACHE_VOLUME":/c alpine sh -c "[ \"\$(stat -c %u /c)\" = \"$(id -u)\" ]" >/dev/null 2>&1; then
    echo "[dev.sh] initializing Gradle cache volume (one-time)..."
    docker run --rm -v "$CACHE_VOLUME":/c alpine chown -R "$UID_GID" /c
  fi
}

gradle_run() {
  ensure_image
  ensure_cache_volume
  docker run --rm \
    --user "$UID_GID" \
    --add-host=host.docker.internal:host-gateway \
    -e GRADLE_USER_HOME=/gradle-cache \
    -e HOME=/tmp \
    -v "$ROOT":/workspace \
    -v "$CACHE_VOLUME":/gradle-cache \
    -w /workspace \
    "$IMAGE" \
    gradle --console=plain "$@"
}

gradle_run_with_docker() {
  ensure_image
  ensure_cache_volume
  docker run --rm \
    --user "$UID_GID" \
    --group-add "$(stat -c %g /var/run/docker.sock)" \
    -e GRADLE_USER_HOME=/gradle-cache \
    -e HOME=/tmp \
    -e DOCKER_HOST=unix:///var/run/docker.sock \
    -e DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}" \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    --add-host=host.docker.internal:host-gateway \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$ROOT":/workspace \
    -v "$CACHE_VOLUME":/gradle-cache \
    -w /workspace \
    "$IMAGE" \
    gradle --console=plain "$@"
}

cmd="${1:-help}"
shift || true

case "$cmd" in
  build)            gradle_run build "$@" ;;
  test)             gradle_run test "$@" ;;
  check)            gradle_run check "$@" ;;
  test-integration) gradle_run_with_docker test -PincludeIntegrationTests=true "$@" ;;
  boot)
    # Runs a Spring Boot service with host networking so it can reach the
    # docker-compose Postgres/Redis on localhost. Example:
    #   ./dev.sh boot :services:auth-service:bootRun
    # Set BOOT_NO_PORTS=true for one-shot batch runs while a stack is already
    # running (avoids host port conflicts).
    ensure_image
    ensure_cache_volume
    PORT_ARGS=(-p 18080-18085:8080-8085)
    if [ "${BOOT_NO_PORTS:-false}" = "true" ]; then
      PORT_ARGS=()
    fi
    docker run --rm \
      --user "$UID_GID" \
      --add-host=host.docker.internal:host-gateway \
      -e GRADLE_USER_HOME=/gradle-cache \
      -e HOME=/tmp \
      -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/stockpredict \
      -e SPRING_DATA_REDIS_HOST=host.docker.internal \
      -e PREDICTION_MODEL_DIR \
      -e PREDICTION_RUN_BATCH_ONCE \
      -e RANKING_RUN_BATCH_ONCE \
      -e RANKING_SECTOR_MAP \
      -e MARKET_DATA_RUN_INGEST_ONCE \
      -e STOCKPREDICT_JWT_JWKS_URI \
      "${PORT_ARGS[@]}" \
      -v "$ROOT":/workspace \
      -v "$CACHE_VOLUME":/gradle-cache \
      -w /workspace \
      "$IMAGE" \
      gradle --console=plain "$@"
    ;;
  gradle)           gradle_run "$@" ;;
  shell)            ensure_image; ensure_cache_volume; docker run --rm -it --user "$UID_GID" -e GRADLE_USER_HOME=/gradle-cache -e HOME=/tmp -v "$ROOT":/workspace -v "$CACHE_VOLUME":/gradle-cache -w /workspace "$IMAGE" bash ;;
  up)               docker compose -f "$ROOT/docker-compose.yml" up -d "$@" ;;
  down)             docker compose -f "$ROOT/docker-compose.yml" down "$@" ;;
  logs)             docker compose -f "$ROOT/docker-compose.yml" logs -f "$@" ;;
  ps)               docker compose -f "$ROOT/docker-compose.yml" ps ;;
  clean)
    docker compose -f "$ROOT/docker-compose.yml" down -v --remove-orphans || true
    docker volume rm "$CACHE_VOLUME" >/dev/null 2>&1 || true
    echo "[dev.sh] infra stopped, Gradle cache volume removed."
    ;;
  help)
    sed -n '2,20p' "$0"
    ;;
  *)
    gradle_run "$cmd" "$@"
    ;;
esac
