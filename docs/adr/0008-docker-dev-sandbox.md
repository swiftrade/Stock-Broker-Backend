# ADR 0008 — Docker dev sandbox (no host installs)

**Status:** accepted (Phase 0)

**Context.** The build must be reproducible on any workstation and in CI
without installing JDK/Gradle globally.

**Decision.** `dev.sh` runs Gradle inside the official
`gradle:8.10.2-jdk21` container with the repo bind-mounted and a named volume
for the dependency cache. `./gradlew` (wrapper) is committed so CI can build
without the sandbox. Local infrastructure (Postgres 16 + TimescaleDB, Redis 7,
WireMock stubs per service) runs via `docker compose`.

**Consequences.** `./dev.sh build`, `./dev.sh test`, `./dev.sh up` are the only
commands a developer needs. Testcontainers tests run via
`./dev.sh test-integration`, which mounts the Docker socket.
