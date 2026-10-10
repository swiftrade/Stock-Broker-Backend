# ADR 0007 — Cloud-portable by construction (K8s, Terraform per cloud)

**Status:** accepted (Phase 0, implemented in Phase 3 by A7)

**Context.** Launch target is one cloud, but the plan requires portability and
the option to move.

**Decision.** Services are containers on Kubernetes (GKE/EKS/AKS) built with
Jib; managed Postgres + Redis; Terraform modules per cloud; Helm charts per
service; secrets via External Secrets Operator; OpenTelemetry to
Prometheus/Grafana/Loki. No cloud-proprietary SDKs in service code; object
storage access goes through one interface.

**Consequences.** Multi-cloud smoke tests each release; higher upfront infra
work, no lock-in. GCP is assumed primary for launch.
