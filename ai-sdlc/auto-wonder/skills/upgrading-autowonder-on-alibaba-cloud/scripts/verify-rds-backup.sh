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
end_epoch=$(date -u +%s)
start_epoch=$((end_epoch - 604800))
start_time=$(jq -nr --argjson time "$start_epoch" '$time | strftime("%Y-%m-%dT%H:%MZ")')
end_time=$(jq -nr --argjson time "$end_epoch" '$time | strftime("%Y-%m-%dT%H:%MZ")')
page=1
backup=null
while :; do
  response=$(aliyun_cli rds DescribeBackups --region "$region" --RegionId "$region" \
    --DBInstanceId "$rds_id" --StartTime "$start_time" --EndTime "$end_time" \
    --PageSize 100 --PageNumber "$page") || die "cannot inspect RDS backups"
  entries=$(jq -c '.Items.Backup // .items.backup // .Backups.Backup // []' <<<"$response")
  backup=$(jq -nc --argjson previous "$backup" --argjson entries "$entries" \
    --argjson start "$start_epoch" --argjson end "$end_epoch" '
    [$previous, ($entries[] |
      select((.BackupStatus // .backupStatus) == "Success") |
      {backupId: ((.BackupId // .backupId // "") | tostring),
       completedEpoch: (try ((.BackupEndTime // .backupEndTime) | fromdateiso8601) catch null)} |
      select(.backupId != "" and .completedEpoch != null and .completedEpoch >= $start and .completedEpoch <= $end))] |
    map(select(. != null)) | sort_by(.completedEpoch) | last')
  total=$(jq -er '.TotalRecordCount // .totalRecordCount // 0 | numbers' <<<"$response") || die "invalid RDS backup pagination"
  (( page * 100 < total )) || break
  [[ $(jq 'length' <<<"$entries") != 0 ]] || die "RDS backup pagination ended before total count"
  page=$((page + 1))
done
[[ "$backup" != null ]] || die "no successful RDS backup was found in the last seven days"
backup_id=$(jq -r '.backupId' <<<"$backup")
backup_end=$(jq -r '.completedEpoch | todateiso8601' <<<"$backup")
plan=$(json_string "$manifest" '.upgrade.planFingerprint')
atomic_jq "$manifest" --arg instance "$rds_id" --arg backup "$backup_id" --arg completed "$backup_end" --arg plan "$plan" '
  .upgrade.databaseBackup={status:"verified",rdsInstanceId:$instance,backupId:$backup,completedAt:$completed,planFingerprint:$plan,verifiedAt:(now|todateiso8601),verifiedEpoch:now}
'
jq '{status:.upgrade.databaseBackup.status,rdsInstanceId:.upgrade.databaseBackup.rdsInstanceId,backupId:.upgrade.databaseBackup.backupId,completedAt:.upgrade.databaseBackup.completedAt,planFingerprint:.upgrade.databaseBackup.planFingerprint}' "$manifest"
