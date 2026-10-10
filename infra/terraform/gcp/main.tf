terraform {
  required_version = ">= 1.6"
  required_providers {
    google = { source = "hashicorp/google", version = "~> 5.40" }
  }
  backend "gcs" {
    # bucket = "your-tf-state-bucket"
    # prefix = "stockpredict/gcp"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

variable "project_id" { type = string }
variable "region" { type = string, default = "us-central1" }
variable "name" { type = string, default = "stockpredict" }
variable "db_tier" { type = string, default = "db-custom-2-7680" }
variable "redis_tier" { type = string, default = "STANDARD_HA" }

# --- Kubernetes (GKE) ---
resource "google_container_cluster" "primary" {
  name                     = "${var.name}-gke"
  location                 = var.region
  deletion_protection      = false
  remove_default_node_pool = true
  initial_node_count       = 1
  workload_identity_config { workload_pool = "${var.project_id}.svc.id.goog" }
}

resource "google_container_node_pool" "apps" {
  name       = "apps"
  cluster    = google_container_cluster.primary.name
  location   = var.region
  node_count = 3
  node_config {
    machine_type = "e2-standard-4"
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }
  autoscaling { min_node_count = 2, max_node_count = 8 }
}

# --- Postgres (Cloud SQL) ---
resource "google_sql_database_instance" "postgres" {
  name             = "${var.name}-pg"
  database_version = "POSTGRES_16"
  region           = var.region
  settings {
    tier              = var.db_tier
    availability_type = "REGIONAL"
    backup_configuration { enabled = true }
    ip_configuration { ipv4_enabled = false, private_network = "default" }
  }
}

resource "google_sql_database" "app" {
  name     = "stockpredict"
  instance = google_sql_database_instance.postgres.name
}

# --- Redis (Memorystore) ---
resource "google_redis_instance" "cache" {
  name           = "${var.name}-redis"
  tier           = var.redis_tier
  memory_size_gb = 2
  region         = var.region
}

# --- Object storage for models + golden files ---
resource "google_storage_bucket" "models" {
  name                        = "${var.project_id}-${var.name}-models"
  location                    = var.region
  uniform_bucket_level_access = true
  versioning { enabled = true }
}

# --- Secrets ---
resource "google_secret_manager_secret" "db_password" {
  secret_id = "${var.name}-db-password"
  replication { auto {} }
}

output "gke_cluster" { value = google_container_cluster.primary.name }
output "db_connection" { value = google_sql_database_instance.postgres.connection_name }
output "redis_host" { value = google_redis_instance.cache.host }
output "models_bucket" { value = google_storage_bucket.models.name }
