# Frozen contracts (gate G0)

These files are the single source of truth for all inter-service communication.
They are **frozen after gate G0**: changes require a versioned PR through the
architect (A0) and consumers' contract tests must pass before merge.

| Contract | Owner service | Consumers |
| --- | --- | --- |
| `auth-service.yaml` | A1 auth-service | gateway, all services (JWKS) |
| `prediction-service.yaml` | A4 prediction-service | gateway, frontend |
| `market-data-service.yaml` | A2 market-data-service | gateway, A6 paper trading (quotes) |
| `ranking-service.yaml` | A5 ranking-service | gateway, frontend |
| `paper-trading-service.yaml` | A6 paper-trading-service | gateway, frontend |
| `events/bars.updated.json` | A2 market-data-service | A4 prediction-service, A5 ranking-service |

Rules:

1. OpenAPI 3.1. `operationId` is stable and drives the generated Java API name.
2. Errors use RFC 7807 (`application/problem+json`) with a stable `code` field.
3. `plan` entitlement is a JWT claim (`FREE` | `PREMIUM`); plan limits are
   enforced server-side, never in the client.
4. Server interfaces and clients are generated with `openapi-generator`
   (`spring` generator, `interfaceOnly`, Spring Boot 3 / Jakarta). Generated
   code is never edited; the build regenerates it from these specs.
