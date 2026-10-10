# ADR 0006 — Auth: Argon2id + RS256 JWT, local verification

**Status:** accepted (Phase 0)

**Context.** The plan requires in-house auth; services must authorize requests
without a synchronous call to auth-service on every request.

**Decision.** Credentials are stored as Argon2id hashes
(`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8`). Access tokens are
RS256 JWTs (15 min) with `sub`, `plan`, `roles`; refresh tokens are opaque,
stored SHA-256-hashed, rotated on use with family-wide reuse detection. The
gateway pre-checks tokens and forwards `X-User-Id/Plan/Roles/Email` headers
(inbound copies stripped); services still verify tokens locally against the
JWKS endpoint (`common-lib` `JwtVerifier`) as defence in depth. Plan
entitlement is enforced server-side via `@RequiresPlan`.

**Consequences.** Stateless horizontal scaling; key rotation only requires
publishing a new JWK and retiring the old one. Breached-password checks use
HIBP k-anonymity and fail open if the API is unreachable.
