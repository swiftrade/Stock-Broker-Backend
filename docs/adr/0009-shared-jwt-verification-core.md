# ADR 0009 — Shared framework-free JWT verification core

**Status:** accepted (post-review hardening)

**Context.** `common-lib::JwtVerifier` (servlet services) and
`gateway::GatewayJwtVerifier` (reactive edge) duplicated RS256 parsing,
signature and time/issuer/audience validation. The copies had already drifted:
the gateway ignored the configured clock skew, collapsed `TOKEN_EXPIRED` into
`TOKEN_INVALID`, and did not check `iat` in the future.

**Decision.** Extract `libs/jwt-core` with `JwtVerificationCore`: nimbus-based,
no Spring or servlet types, one implementation of the verification rules with a
stable error code (`TOKEN_INVALID` / `TOKEN_EXPIRED`). `JwtVerifier` and
`GatewayJwtVerifier` become thin wrappers that map the verified `JWTClaimsSet`
to their respective claim types and exceptions.

**Consequences.** The edge and services can no longer diverge on verification
semantics; security fixes land once. `libs/jwt-core` is a plain
`java-library` depending only on nimbus-jose-jwt, so both the servlet and the
reactive stacks can consume it.
