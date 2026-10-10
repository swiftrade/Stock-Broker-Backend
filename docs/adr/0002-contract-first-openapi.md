# ADR 0002 — Contract-first OpenAPI 3.1, frozen after G0

**Status:** accepted (Phase 0, gate G0)

**Context.** Parallel agents need stable interfaces before implementations
exist; WireMock stubs and contract tests are generated from the same source.

**Decision.** All service interfaces are defined in `/contracts/*.yaml`
(OpenAPI 3.1) and event payloads in `/contracts/events/*.json`. Java server
interfaces are generated with `openapi-generator` (`spring` generator,
`interfaceOnly`, Spring Boot 3/Jakarta). Errors are RFC 7807 with a stable
`code` field. Any change after G0 goes through a versioned PR owned by A0; CI
runs `oasdiff breaking` on every PR.

**Consequences.** No service can merge a breaking interface change silently.
Consumers build against WireMock stubs from day one.
