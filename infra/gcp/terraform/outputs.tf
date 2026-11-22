output "cloud_sql_connection_name" {
  value       = google_sql_database_instance.postgres.connection_name
  description = "Set this as the CLOUD_SQL_INSTANCE GitHub Actions variable."
}

output "api_repository" {
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.api.repository_id}"
  description = "Artifact Registry repository for the API image."
}

output "runtime_service_account" {
  value       = google_service_account.runtime.email
  description = "Cloud Run service identity."
}

output "migrator_service_account" {
  value       = google_service_account.migrator.email
  description = "Cloud Run Job identity with database-migration-only credentials."
}

output "github_deployer_service_account" {
  value       = google_service_account.deployer.email
  description = "Set this as the GCP_DEPLOYER_SERVICE_ACCOUNT GitHub Actions variable."
}

output "github_workload_identity_provider" {
  value       = google_iam_workload_identity_pool_provider.github.name
  description = "Set this as the GCP_WIF_PROVIDER GitHub Actions variable."
}

output "document_bucket" {
  value       = google_storage_bucket.documents.name
  description = "Private bucket; application IAM access is intentionally not granted until KYC controls exist."
}
