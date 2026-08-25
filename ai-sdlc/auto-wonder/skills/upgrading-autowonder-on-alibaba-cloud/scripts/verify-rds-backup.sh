#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/upgrade-lib.sh"

manifest=
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --help|-h) printf 'Usage: verify-rds-backup.sh --manifest FILE\n'; exit 0 ;;
    *) die "unknown argument" ;;
  esac
done
require_file "$manifest"; require_command jq; require_command aliyun
json_validate "$manifest"; reject_secret_keys "$manifest"
refresh_target_verification "$manifest"
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region')
rds_id=$(jq -er '.resources.rds_instance_id // .resources.rds.instance_id // empty' "$manifest") || die "RDS instance ID is missing"
if date -u -v-7d +%Y-%m-%dT%H:%MZ >/dev/null 2>&1; then
  start_time=$(date -u -v-7d +%Y-%m-%dT%H:%MZ)
else
  start_time=$(date -u -d '7 days ago' +%Y-%m-%dT%H:%MZ)
fi
end_time=$(date -u +%Y-%m-%dT%H:%MZ)
response=$(aliyun_cli rds DescribeBackups --region "$region" --RegionId "$region" \
  --DBInstanceId "$rds_id" --StartTime "$start_time" --EndTime "$end_time") || die "cannot inspect RDS backups"
backup=$(jq -ce '
  [(.Items.Backup // .items.backup // .Backups.Backup // [])[] |
   select((.BackupStatus // .backupStatus // "") == "Success")] |
  sort_by(.BackupEndTime // .backupEndTime // "") | last
' <<<"$response") || die "no successful RDS backup was found in the last seven days"
backup_id=$(jq -er '.BackupId // .backupId // empty' <<<"$backup") || die "RDS backup response has no backup ID"
backup_end=$(jq -er '.BackupEndTime // .backupEndTime // empty' <<<"$backup") || die "RDS backup response has no completion time"
fingerprint=$(sha256_text "$(json_string "$manifest" '.deploymentId'):$rds_id:$backup_id:$backup_end")
atomic_jq "$manifest" --arg instance "$rds_id" --arg backup "$backup_id" --arg completed "$backup_end" --arg fingerprint "$fingerprint" '
  .upgrade.databaseBackup={status:"verified",rdsInstanceId:$instance,backupId:$backup,completedAt:$completed,fingerprint:$fingerprint,verifiedAt:(now|todateiso8601),verifiedEpoch:now}
'
jq '{status:.upgrade.databaseBackup.status,rdsInstanceId:.upgrade.databaseBackup.rdsInstanceId,backupId:.upgrade.databaseBackup.backupId,completedAt:.upgrade.databaseBackup.completedAt,fingerprint:.upgrade.databaseBackup.fingerprint}' "$manifest"
