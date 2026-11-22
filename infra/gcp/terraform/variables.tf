variable "project_id" {
  description = "Google Cloud project dedicated to Cobalto."
  type        = string
}

variable "region" {
  description = "Region shared by Cloud Run, Cloud SQL, Artifact Registry, and Cloud Storage."
  type        = string
  default     = "us-central1"
}

variable "github_repository" {
  description = "GitHub repository in owner/name format allowed to deploy."
  type        = string
}

variable "document_bucket_name" {
  description = "Globally unique private Cloud Storage bucket name; do not put PII in the name."
  type        = string
}

variable "sql_tier" {
  description = "Cloud SQL machine tier. A regional instance is billed in both zones."
  type        = string
  default     = "db-custom-1-3840"
}

variable "sql_disk_size_gb" {
  description = "Initial SSD size for PostgreSQL; autoscaling is enabled."
  type        = number
  default     = 20
}

variable "sql_disk_limit_gb" {
  description = "Maximum SSD size to constrain storage growth and cost."
  type        = number
  default     = 100
}

variable "api_hostname" {
  description = "Optional public API hostname for a Cloud Monitoring uptime check."
  type        = string
  default     = ""
}

variable "notification_channel_ids" {
  description = "Cloud Monitoring notification channel resource IDs for uptime alerts."
  type        = list(string)
  default     = []
}
