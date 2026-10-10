terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }
  backend "s3" {
    # bucket = "your-tf-state-bucket"
    # key    = "stockpredict/aws/terraform.tfstate"
    # region = "us-east-1"
  }
}

provider "aws" { region = var.region }

variable "region" { type = string, default = "us-east-1" }
variable "name" { type = string, default = "stockpredict" }
variable "db_instance_class" { type = string, default = "db.r6g.large" }
variable "node_instance_type" { type = string, default = "m6i.xlarge" }

# --- Kubernetes (EKS) ---
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name    = "${var.name}-eks"
  cluster_version = "1.30"
  vpc_id          = module.vpc.vpc_id
  subnet_ids      = module.vpc.private_subnets

  eks_managed_node_groups = {
    apps = {
      instance_types = [var.node_instance_type]
      min_size       = 2
      max_size       = 8
      desired_size   = 3
    }
  }
  enable_irsa = true
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.8"
  name    = "${var.name}-vpc"
  cidr    = "10.42.0.0/16"
  azs             = ["${var.region}a", "${var.region}b", "${var.region}c"]
  private_subnets = ["10.42.1.0/24", "10.42.2.0/24", "10.42.3.0/24"]
  public_subnets  = ["10.42.101.0/24", "10.42.102.0/24", "10.42.103.0/24"]
  enable_nat_gateway = true
  single_nat_gateway = false
}

# --- Postgres (RDS) ---
resource "aws_db_instance" "postgres" {
  identifier             = "${var.name}-pg"
  engine                 = "postgres"
  engine_version         = "16"
  instance_class         = var.db_instance_class
  allocated_storage      = 100
  storage_encrypted      = true
  multi_az               = true
  db_name                = "stockpredict"
  username               = "stockpredict"
  manage_master_user_password = true
  skip_final_snapshot    = false
}

# --- Redis (ElastiCache) ---
resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = "${var.name}-redis"
  description                = "Stock prediction cache"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.r6g.large"
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  at_rest_encryption_enabled = true
}

# --- Object storage for models ---
resource "aws_s3_bucket" "models" {
  bucket = "${var.name}-models-${data.aws_caller_identity.current.account_id}"
}

data "aws_caller_identity" "current" {}

output "eks_cluster" { value = module.eks.cluster_name }
output "db_endpoint" { value = aws_db_instance.postgres.address }
output "redis_endpoint" { value = aws_elasticache_replication_group.cache.primary_endpoint_address }
output "models_bucket" { value = aws_s3_bucket.models.bucket }
