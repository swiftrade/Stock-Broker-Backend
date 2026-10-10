# ADR 0001 — Monorepo with Gradle multi-module (Java 21, Spring Boot 3.3)

**Status:** accepted (Phase 0, gate G0)

**Context.** Eight agents build services in parallel; they must not collide on
shared files and must be able to build/test their own module without waiting for
neighbours.

**Decision.** One Gradle (Kotlin DSL) monorepo with strict directory ownership:
`common-lib`, `db-migrations`, `libs/feature-engine`, `services/*`,
`contracts/`, `ml-export/`. Versions are centralized in
`gradle/libs.versions.toml`. Java toolchain is pinned to 21; Spring Boot 3.3.5.

**Consequences.** One `./gradlew build` validates everything; agents own one
directory each (coordination rule #1). Generated OpenAPI code lives under
`build/` and is never edited.
