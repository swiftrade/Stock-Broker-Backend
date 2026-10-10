terraform {
  required_version = ">= 1.6"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = "~> 3.110" }
  }
  backend "azurerm" {
    # resource_group_name  = "tfstate"
    # storage_account_name = "yourstateaccount"
    # container_name       = "tfstate"
    # key                  = "stockpredict/azure/terraform.tfstate"
  }
}

provider "azurerm" { features {} }

variable "location" { type = string, default = "westeurope" }
variable "name" { type = string, default = "stockpredict" }
variable "db_sku" { type = string, default = "GP_Standard_D2ds_v5" }
variable "node_vm_size" { type = string, default = "Standard_D4s_v5" }

resource "azurerm_resource_group" "rg" {
  name     = "${var.name}-rg"
  location = var.location
}

# --- Kubernetes (AKS) ---
resource "azurerm_kubernetes_cluster" "aks" {
  name                = "${var.name}-aks"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  dns_prefix          = var.name
  default_node_pool {
    name                = "apps"
    vm_size             = var.node_vm_size
    enable_auto_scaling = true
    min_count           = 2
    max_count           = 8
  }
  identity { type = "SystemAssigned" }
  oidc_issuer_enabled = true
}

# --- Postgres (Flexible Server) ---
resource "azurerm_postgresql_flexible_server" "postgres" {
  name                   = "${var.name}-pg"
  resource_group_name    = azurerm_resource_group.rg.name
  location               = azurerm_resource_group.rg.location
  version                = "16"
  sku_name               = var.db_sku
  storage_mb             = 131072
  zone                   = "1"
  high_availability { mode = "ZoneRedundant" }
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = "stockpredict"
  server_id = azurerm_postgresql_flexible_server.postgres.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# --- Redis (Azure Cache) ---
resource "azurerm_redis_cache" "cache" {
  name                = "${var.name}-redis"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  capacity            = 2
  family              = "C"
  sku_name            = "Standard"
  enable_non_ssl_port = false
  minimum_tls_version = "1.2"
}

# --- Object storage for models ---
resource "azurerm_storage_account" "models" {
  name                     = replace("${var.name}models", "-", "")
  resource_group_name      = azurerm_resource_group.rg.name
  location                 = azurerm_resource_group.rg.location
  account_tier             = "Standard"
  account_replication_type = "ZRS"
  min_tls_version          = "TLS1_2"
}

resource "azurerm_storage_container" "models" {
  name                  = "models"
  storage_account_name  = azurerm_storage_account.models.name
  container_access_type = "private"
}

output "aks_cluster" { value = azurerm_kubernetes_cluster.aks.name }
output "db_host" { value = azurerm_postgresql_flexible_server.postgres.fqdn }
output "redis_host" { value = azurerm_redis_cache.cache.hostname }
output "models_container" { value = azurerm_storage_container.models.name }
