# Infrastructure (A7)

Cloud-portable by construction: the same service containers run on GKE, EKS or
AKS; only the Terraform module changes.

## Helm

```bash
helm upgrade --install stockpredict infra/helm/stockpredict \
  --namespace stockpredict --create-namespace \
  --set global.imageTag=0.1.0-SNAPSHOT \
  --set global.imageRegistry=ghcr.io/your-org/stockpredict
```

The chart renders one Deployment/Service per entry in `values.services`, an
HPA + PDB for the gateway, and one-shot CronJobs for the prediction, ranking
and market-data batch jobs (they exit when the batch completes). Secrets come
from External Secrets Operator (`ExternalSecret` -> cloud secret manager);
nothing sensitive is stored in the chart.

## Terraform

| Cloud | Cluster | Postgres | Redis | Object storage | Secrets |
| --- | --- | --- | --- | --- | --- |
| GCP (`gcp/`) | GKE | Cloud SQL 16 | Memorystore | GCS | Secret Manager |
| AWS (`aws/`) | EKS | RDS 16 | ElastiCache | S3 | Secrets Manager |
| Azure (`azure/`) | AKS | Flexible Server 16 | Azure Cache | Blob | Key Vault |

```bash
cd infra/terraform/gcp && terraform init && terraform plan
```

State backends are declared but commented out — configure a remote bucket per
environment before applying.

## Observability

All services expose Micrometer metrics on `/actuator/prometheus` and health on
`/actuator/health/{liveness,readiness}`. Ship metrics to Prometheus (or Google
Managed Prometheus / Amazon Managed Prometheus / Azure Monitor), logs via the
container runtime, and traces with the OpenTelemetry Java agent injected by the
platform (no code changes).
