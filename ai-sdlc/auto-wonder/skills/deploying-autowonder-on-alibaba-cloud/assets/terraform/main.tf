terraform {
  required_version = ">= 1.5.0"

  required_providers {
    alicloud = {
      source  = "aliyun/alicloud"
      version = "1.287.0"
    }
  }
}

provider "alicloud" {
  region = var.region
}

variable "region" {
  description = "Alibaba Cloud region preset."
  type        = string
  validation {
    condition = contains([
      "cn-zhangjiakou",
      "cn-hangzhou",
      "cn-shanghai",
      "cn-beijing",
    ], var.region)
    error_message = "region must be a supported preset."
  }
}

variable "environment" {
  description = "Environment label used in resource names and tags."
  type        = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment))
    error_message = "environment must be 2-16 lowercase letters, digits, or hyphens."
  }
}

variable "deployment_id" {
  description = "Globally unique, non-secret deployment identifier."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{5,31}$", var.deployment_id))
    error_message = "deployment_id must be 6-32 lowercase letters, digits, or hyphens."
  }
}

variable "zone_a_id" {
  description = "Primary availability zone in the selected region."
  type        = string
}

variable "zone_b_id" {
  description = "Secondary availability zone in the selected region."
  type        = string
}

variable "public_source_cidrs" {
  description = "Public client CIDRs allowed to reach AutoWonder through ALB."
  type        = list(string)
  validation {
    condition = length(var.public_source_cidrs) > 0 && alltrue([
      for cidr in var.public_source_cidrs : can(cidrnetmask(cidr)) && cidr != "0.0.0.0/0"
    ])
    error_message = "public_source_cidrs must contain valid restricted CIDRs; 0.0.0.0/0 is forbidden."
  }
}

variable "common_tags" {
  description = "Optional custom tags. System tags override matching keys."
  type        = map(string)
  default     = {}
}

variable "lifecycle_mode" {
  description = "persistent enables service protections; temporary permits cleanup."
  type        = string
  default     = "persistent"
  validation {
    condition     = contains(["persistent", "temporary"], var.lifecycle_mode)
    error_message = "lifecycle_mode must be persistent or temporary."
  }
}

variable "billing_strategy" {
  description = "Fixed subscription-first billing policy for new deployments."
  type        = string
  validation {
    condition     = var.billing_strategy == "subscription-first"
    error_message = "billing_strategy must be subscription-first."
  }
}

variable "purchase_period_months" {
  description = "Initial subscription purchase period."
  type        = number
  validation {
    condition     = var.purchase_period_months == 1
    error_message = "Initial subscription purchase must be exactly one month."
  }
}

variable "auto_renew" {
  description = "Continuous automatic renewal for subscription resources."
  type        = bool
  validation {
    condition     = var.auto_renew
    error_message = "Automatic renewal must be enabled."
  }
}

variable "auto_renew_period_months" {
  description = "Months purchased by each automatic renewal."
  type        = number
  validation {
    condition     = var.auto_renew_period_months == 1
    error_message = "Each automatic renewal must purchase exactly one month."
  }
}

variable "vpc_cidr" {
  description = "Private VPC CIDR."
  type        = string
  default     = "10.0.0.0/16"
}

variable "zone_a_cidr" {
  description = "Private subnet CIDR for zone A."
  type        = string
  default     = "10.0.1.0/24"
}

variable "zone_b_cidr" {
  description = "Private subnet CIDR for zone B."
  type        = string
  default     = "10.0.2.0/24"
}

variable "ecs_image_id" {
  description = "Verified Alibaba Cloud Linux x86_64 image ID for the selected region."
  type        = string
}

variable "ecs_instance_type" {
  description = "Verified x86_64 ECS instance type with exactly 2 vCPU and 4 GiB available in both zones; prefer ecs.c8a.large."
  type        = string
  default     = "ecs.c8a.large"
}

variable "ecs_password" {
  description = "ECS operating-system password supplied through TF_VAR_ecs_password."
  type        = string
  sensitive   = true
  validation {
    condition     = length(var.ecs_password) >= 8 && length(var.ecs_password) <= 30 && can(regex("[A-Z]", var.ecs_password)) && can(regex("[a-z]", var.ecs_password)) && can(regex("[0-9]", var.ecs_password)) && can(regex("[^A-Za-z0-9]", var.ecs_password))
    error_message = "ecs_password must be 8-30 characters and include uppercase, lowercase, numeric, and special characters."
  }
}

variable "rds_instance_type" {
  description = "Region-supported MySQL 8 high-availability instance class."
  type        = string
  default     = "mysql.n2.medium.2c"
}

variable "rds_category" {
  description = "RDS topology; Basic is intentionally unsupported."
  type        = string
  default     = "HighAvailability"
  validation {
    condition     = contains(["HighAvailability", "cluster"], var.rds_category)
    error_message = "rds_category must be HighAvailability or cluster."
  }
}

variable "rds_storage_type" {
  description = "Region-supported RDS storage type accepted by provider 1.287.0."
  type        = string
  default     = "cloud_essd"
}

variable "rds_storage_gb" {
  description = "RDS storage capacity in GiB."
  type        = number
  default     = 100
}

variable "rds_password" {
  description = "RDS application account password supplied through TF_VAR_rds_password."
  type        = string
  sensitive   = true
  validation {
    condition     = length(var.rds_password) >= 8 && length(var.rds_password) <= 32 && can(regex("[A-Z]", var.rds_password)) && can(regex("[a-z]", var.rds_password)) && can(regex("[0-9]", var.rds_password)) && can(regex("[^A-Za-z0-9]", var.rds_password))
    error_message = "rds_password must be 8-32 characters and include uppercase, lowercase, numeric, and special characters."
  }
}

variable "redis_instance_class" {
  description = "Region-supported Redis 7 primary/replica class."
  type        = string
  default     = "redis.shard.small.ce"
  validation {
    condition     = can(regex("^redis\\.(shard|master)", var.redis_instance_class))
    error_message = "redis_instance_class must be a primary/replica Redis class."
  }
}

variable "redis_password" {
  description = "Redis password supplied through TF_VAR_redis_password."
  type        = string
  sensitive   = true
  validation {
    condition     = length(var.redis_password) >= 8 && length(var.redis_password) <= 32 && can(regex("[A-Z]", var.redis_password)) && can(regex("[a-z]", var.redis_password)) && can(regex("[0-9]", var.redis_password)) && can(regex("[^A-Za-z0-9]", var.redis_password))
    error_message = "redis_password must be 8-32 characters and include uppercase, lowercase, numeric, and special characters."
  }
}

locals {
  name_prefix       = "aw-${var.environment}-${var.deployment_id}"
  persistent        = var.lifecycle_mode == "persistent"
  server_group_port = 7001
  system_tags = {
    Project      = "AutoWonder"
    Environment  = var.environment
    DeploymentId = var.deployment_id
    ManagedBy    = "Terraform"
    Topology     = "multi-az-ha"
  }
  tags = merge(var.common_tags, local.system_tags)
  ecs_nodes = {
    zone_a = {
      zone       = var.zone_a_id
      vswitch_id = alicloud_vswitch.zone_a.id
    }
    zone_b = {
      zone       = var.zone_b_id
      vswitch_id = alicloud_vswitch.zone_b.id
    }
  }
  logstores = {
    system   = alicloud_log_store.system.logstore_name
    business = alicloud_log_store.business.logstore_name
    metrics  = alicloud_log_store.metrics.logstore_name
  }
}

resource "alicloud_vpc" "main" {
  vpc_name   = "${local.name_prefix}-vpc"
  cidr_block = var.vpc_cidr
  tags       = local.tags

  lifecycle {
    precondition {
      condition     = var.zone_a_id != var.zone_b_id
      error_message = "True HA requires two distinct availability zones."
    }
  }
}

resource "alicloud_vswitch" "zone_a" {
  vpc_id       = alicloud_vpc.main.id
  cidr_block   = var.zone_a_cidr
  zone_id      = var.zone_a_id
  vswitch_name = "${local.name_prefix}-vsw-a"
  tags         = local.tags
}

resource "alicloud_vswitch" "zone_b" {
  vpc_id       = alicloud_vpc.main.id
  cidr_block   = var.zone_b_cidr
  zone_id      = var.zone_b_id
  vswitch_name = "${local.name_prefix}-vsw-b"
  tags         = local.tags
}

resource "alicloud_security_group" "app" {
  security_group_name = "${local.name_prefix}-sg"
  vpc_id              = alicloud_vpc.main.id
  tags                = local.tags
}

resource "alicloud_security_group_rule" "vpc_internal" {
  type              = "ingress"
  ip_protocol       = "all"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "-1/-1"
  priority          = 10
  security_group_id = alicloud_security_group.app.id
  cidr_ip           = var.vpc_cidr
}

resource "alicloud_security_group_rule" "alb_service" {
  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "7001/7001"
  priority          = 10
  security_group_id = alicloud_security_group.app.id
  cidr_ip           = "100.64.0.0/10"
}

resource "alicloud_instance" "app" {
  for_each                   = local.ecs_nodes
  instance_name              = "${local.name_prefix}-ecs-${each.key}"
  host_name                  = "autowonder-${replace(each.key, "_", "-")}"
  image_id                   = var.ecs_image_id
  instance_type              = var.ecs_instance_type
  security_groups            = [alicloud_security_group.app.id]
  vswitch_id                 = each.value.vswitch_id
  system_disk_category       = "cloud_essd"
  system_disk_size           = 60
  instance_charge_type       = "PrePaid"
  period                     = var.purchase_period_months
  period_unit                = "Month"
  renewal_status             = var.auto_renew ? "AutoRenewal" : "Normal"
  auto_renew_period          = var.auto_renew_period_months
  internet_max_bandwidth_out = 0
  password                   = var.ecs_password
  tags                       = local.tags
}

resource "alicloud_alb_load_balancer" "app" {
  load_balancer_name     = "${local.name_prefix}-alb"
  load_balancer_edition  = "Basic"
  address_type           = "Internet"
  address_allocated_mode = "Fixed"
  vpc_id                 = alicloud_vpc.main.id
  tags                   = local.tags

  load_balancer_billing_config {
    pay_type = "PayAsYouGo"
  }

  zone_mappings {
    zone_id    = var.zone_a_id
    vswitch_id = alicloud_vswitch.zone_a.id
  }
  zone_mappings {
    zone_id    = var.zone_b_id
    vswitch_id = alicloud_vswitch.zone_b.id
  }
}

resource "alicloud_alb_server_group" "app" {
  server_group_name = "${local.name_prefix}-backend"
  vpc_id            = alicloud_vpc.main.id
  protocol          = "HTTP"
  tags              = local.tags

  sticky_session_config {
    sticky_session_enabled = false
  }

  health_check_config {
    health_check_enabled      = true
    health_check_connect_port = local.server_group_port
    health_check_protocol     = "HTTP"
    health_check_method       = "GET"
    health_check_path         = "/checkpreload.htm"
    health_check_codes        = ["http_2xx"]
    health_check_http_version = "HTTP1.1"
    healthy_threshold         = 2
    unhealthy_threshold       = 2
    health_check_interval     = 10
    health_check_timeout      = 5
  }

  dynamic "servers" {
    for_each = alicloud_instance.app
    content {
      server_type = "Ecs"
      server_id   = servers.value.id
      port        = local.server_group_port
      weight      = 100
    }
  }
}

resource "alicloud_alb_listener" "application" {
  load_balancer_id     = alicloud_alb_load_balancer.app.id
  listener_protocol    = "HTTP"
  listener_port        = 80
  listener_description = "${local.name_prefix}-http-80"
  idle_timeout         = 600
  request_timeout      = 600
  tags                 = local.tags

  default_actions {
    type = "ForwardGroup"
    forward_group_config {
      server_group_tuples {
        server_group_id = alicloud_alb_server_group.app.id
      }
    }
  }
}

resource "alicloud_alb_acl" "public_sources" {
  acl_name = "${local.name_prefix}-public-sources"
  tags     = local.tags
}

resource "alicloud_alb_acl_entry_attachment" "public_sources" {
  for_each    = toset(var.public_source_cidrs)
  acl_id      = alicloud_alb_acl.public_sources.id
  entry       = each.value
  description = "AutoWonder-client-source"
}

resource "alicloud_alb_listener_acl_attachment" "public_sources" {
  acl_id      = alicloud_alb_acl.public_sources.id
  listener_id = alicloud_alb_listener.application.id
  acl_type    = "White"

  depends_on = [alicloud_alb_acl_entry_attachment.public_sources]
}

resource "alicloud_db_instance" "main" {
  engine                   = "MySQL"
  engine_version           = "8.0"
  instance_type            = var.rds_instance_type
  instance_storage         = var.rds_storage_gb
  db_instance_storage_type = var.rds_storage_type
  category                 = var.rds_category
  instance_charge_type     = "Prepaid"
  period                   = var.purchase_period_months
  auto_renew               = var.auto_renew
  auto_renew_period        = var.auto_renew_period_months
  instance_name            = "${local.name_prefix}-rds"
  vswitch_id               = join(",", [alicloud_vswitch.zone_a.id, alicloud_vswitch.zone_b.id])
  zone_id                  = var.zone_a_id
  zone_id_slave_a          = var.zone_b_id
  security_ips             = [var.vpc_cidr]
  deletion_protection      = local.persistent
  tags                     = local.tags
}

resource "alicloud_db_backup_policy" "main" {
  instance_id                 = alicloud_db_instance.main.id
  preferred_backup_period     = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
  preferred_backup_time       = "02:00Z-03:00Z"
  backup_retention_period     = local.persistent ? 30 : 7
  enable_backup_log           = true
  log_backup_retention_period = local.persistent ? 30 : 7
  released_keep_policy        = local.persistent ? "All" : "None"
}

resource "alicloud_rds_account" "app" {
  db_instance_id      = alicloud_db_instance.main.id
  account_name        = "autowonder"
  account_password    = var.rds_password
  account_type        = "Normal"
  account_description = "AutoWonder application account"
}

resource "alicloud_db_database" "app" {
  instance_id    = alicloud_db_instance.main.id
  data_base_name = "autowonder"
  character_set  = "utf8mb4"
}

resource "alicloud_db_account_privilege" "app" {
  instance_id  = alicloud_db_instance.main.id
  account_name = alicloud_rds_account.app.account_name
  privilege    = "ReadWrite"
  db_names     = [alicloud_db_database.app.data_base_name]
}

resource "alicloud_kvstore_instance" "main" {
  db_instance_name            = "${local.name_prefix}-redis"
  instance_class              = var.redis_instance_class
  instance_type               = "Redis"
  engine_version              = "7.0"
  vswitch_id                  = alicloud_vswitch.zone_a.id
  zone_id                     = var.zone_a_id
  secondary_zone_id           = var.zone_b_id
  payment_type                = "PrePaid"
  period                      = tostring(var.purchase_period_months)
  auto_renew                  = var.auto_renew
  auto_renew_period           = var.auto_renew_period_months
  password                    = var.redis_password
  security_ips                = [var.vpc_cidr]
  instance_release_protection = local.persistent
  backup_period               = ["Friday", "Monday", "Saturday", "Sunday", "Thursday", "Tuesday", "Wednesday"]
  backup_time                 = "03:00Z-04:00Z"
  tags                        = local.tags
}

resource "alicloud_oss_bucket" "package" {
  bucket        = "${local.name_prefix}-packages"
  acl           = "private"
  storage_class = "Standard"
  force_destroy = !local.persistent
  tags          = local.tags

  versioning {
    status = local.persistent ? "Enabled" : "Suspended"
  }
}

resource "alicloud_oss_bucket" "artifact" {
  bucket        = "${local.name_prefix}-artifacts"
  acl           = "private"
  storage_class = "Standard"
  force_destroy = !local.persistent
  tags          = local.tags

  versioning {
    status = local.persistent ? "Enabled" : "Suspended"
  }
}

resource "alicloud_log_project" "main" {
  project_name = "${local.name_prefix}-logs"
  description  = "AutoWonder ${var.environment} logs"
  tags         = local.tags
}

resource "alicloud_log_store" "system" {
  project_name          = alicloud_log_project.main.project_name
  logstore_name         = "system"
  shard_count           = 2
  auto_split            = true
  max_split_shard_count = 8
  retention_period      = local.persistent ? 30 : 7
}

resource "alicloud_log_store" "business" {
  project_name          = alicloud_log_project.main.project_name
  logstore_name         = "business"
  shard_count           = 2
  auto_split            = true
  max_split_shard_count = 8
  retention_period      = local.persistent ? 30 : 7
}

resource "alicloud_log_store" "metrics" {
  project_name          = alicloud_log_project.main.project_name
  logstore_name         = "metrics"
  telemetry_type        = "Metrics"
  shard_count           = 2
  auto_split            = true
  max_split_shard_count = 8
  retention_period      = local.persistent ? 30 : 7
}

resource "alicloud_log_store_index" "system" {
  project  = alicloud_log_project.main.project_name
  logstore = alicloud_log_store.system.logstore_name
  full_text {
    case_sensitive  = false
    include_chinese = false
    token           = ", '\";=()[]{}?@&<>/:\n\t\r"
  }
}

resource "alicloud_log_store_index" "business" {
  project  = alicloud_log_project.main.project_name
  logstore = alicloud_log_store.business.logstore_name
  full_text {
    case_sensitive  = false
    include_chinese = false
    token           = ", '\";=()[]{}?@&<>/:\n\t\r"
  }
}

resource "alicloud_ram_user" "app" {
  name         = "${local.name_prefix}-app"
  display_name = "AutoWonder ${var.environment} application"
}

resource "alicloud_ram_policy" "app" {
  policy_name = "${local.name_prefix}-app"
  description = "Least-privilege OSS and SLS access for AutoWonder"
  tags        = local.tags
  policy_document = jsonencode({
    Version = "1"
    Statement = [
      {
        Effect = "Allow"
        Action = ["oss:ListObjects"]
        Resource = [
          "acs:oss:*:*:${alicloud_oss_bucket.package.bucket}",
          "acs:oss:*:*:${alicloud_oss_bucket.artifact.bucket}",
        ]
      },
      {
        Effect = "Allow"
        Action = ["oss:GetObject", "oss:PutObject", "oss:DeleteObject"]
        Resource = [
          "acs:oss:*:*:${alicloud_oss_bucket.package.bucket}/*",
          "acs:oss:*:*:${alicloud_oss_bucket.artifact.bucket}/*",
        ]
      },
      {
        Effect = "Allow"
        Action = ["log:GetProject", "log:GetLogStore"]
        Resource = concat(
          ["acs:log:${var.region}:*:project/${alicloud_log_project.main.project_name}"],
          [for name in values(local.logstores) : "acs:log:${var.region}:*:project/${alicloud_log_project.main.project_name}/logstore/${name}"]
        )
      },
      {
        Effect = "Allow"
        Action = [
          "log:PostLogStoreLogs",
          "log:GetLogStoreLogs",
          "log:GetLogs",
          "log:GetHistograms",
          "log:GetCursorOrData",
          "log:PullLogs",
        ]
        Resource = flatten([
          for name in values(local.logstores) : [
            "acs:log:${var.region}:*:project/${alicloud_log_project.main.project_name}/logstore/${name}",
            "acs:log:${var.region}:*:project/${alicloud_log_project.main.project_name}/logstore/${name}/*",
          ]
        ])
      },
    ]
  })
}

resource "alicloud_ram_user_policy_attachment" "app" {
  user_name   = alicloud_ram_user.app.name
  policy_name = alicloud_ram_policy.app.policy_name
  policy_type = "Custom"
}

resource "alicloud_ram_access_key" "app" {
  user_name = alicloud_ram_user.app.name
}

output "region" {
  value = var.region
}

output "vpc_id" {
  value = alicloud_vpc.main.id
}

output "vswitch_ids" {
  value = [alicloud_vswitch.zone_a.id, alicloud_vswitch.zone_b.id]
}

output "ecs_instance_ids" {
  value = { for zone, instance in alicloud_instance.app : zone => instance.id }
}

output "ecs_private_ips" {
  value = { for zone, instance in alicloud_instance.app : zone => instance.private_ip }
}

output "load_balancer_id" {
  value = alicloud_alb_load_balancer.app.id
}

output "load_balancer_address" {
  value = alicloud_alb_load_balancer.app.dns_name
}

output "rds" {
  value = {
    instance_id = alicloud_db_instance.main.id
    connection  = alicloud_db_instance.main.connection_string
    port        = alicloud_db_instance.main.port
    database    = alicloud_db_database.app.data_base_name
    account     = alicloud_rds_account.app.account_name
  }
}

output "redis" {
  value = {
    instance_id = alicloud_kvstore_instance.main.id
    connection  = alicloud_kvstore_instance.main.connection_domain
    port        = 6379
  }
}

output "oss" {
  value = {
    package_bucket   = alicloud_oss_bucket.package.bucket
    artifact_bucket  = alicloud_oss_bucket.artifact.bucket
    control_endpoint = "oss-${var.region}.aliyuncs.com"
    runtime_endpoint = "oss-${var.region}-internal.aliyuncs.com"
  }
}

output "sls" {
  value = {
    project          = alicloud_log_project.main.project_name
    stores           = local.logstores
    control_endpoint = "${var.region}.log.aliyuncs.com"
    runtime_endpoint = "${var.region}-intranet.log.aliyuncs.com"
  }
}

output "application_access_key_id" {
  value     = alicloud_ram_access_key.app.id
  sensitive = true
}

output "application_access_key_secret" {
  value     = alicloud_ram_access_key.app.secret
  sensitive = true
}

output "expected_tags" {
  value = local.tags
}
