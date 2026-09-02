#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage:
  terraform-backend.sh metadata --manifest FILE [--project-root DIR]
  terraform-backend.sh prepare --manifest FILE [--project-root DIR]
  terraform-backend.sh destroy --manifest FILE [--project-root DIR]
The state bucket, key, and backend path are derived; users never supply them.
EOF
}

[[ ${1:-} == --help || ${1:-} == -h ]] && { usage; exit 0; }
command=${1:-}; [[ -n "$command" ]] || { usage >&2; exit 2; }; shift
manifest=
project_root=${AUTOWONDER_PROJECT_ROOT:-$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)}
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --project-root) project_root=${2:-}; shift 2;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; require_command jq; require_command sha256sum
json_validate "$manifest"; reject_secret_keys "$manifest"
[[ -d "$project_root" ]] || die "project root is unavailable"
project_root=$(cd -- "$project_root" && pwd -P)

region=$(json_string "$manifest" '.region')
deployment_id=$(json_string "$manifest" '.deploymentId')
account_uid=$(json_string "$manifest" '.accountUid')
[[ $(json_string "$manifest" '.environment') == auto-wonder-prod ]] || die "environment must be auto-wonder-prod"
[[ $(json_string "$manifest" '.stateMode') == remote ]] || die "remote state is mandatory"
[[ $deployment_id =~ ^[a-z0-9][a-z0-9-]{5,31}$ ]] || die "invalid deployment ID"
[[ $account_uid =~ ^[0-9]{15,20}$ ]] || die "invalid account UID"
case "$region" in cn-zhangjiakou|cn-hangzhou|cn-shanghai|cn-beijing) ;; *) die "unsupported region";; esac

hash=$(printf '%s' "$account_uid|$region|$deployment_id" | sha256sum | cut -c1-12)
bucket="aw-tfstate-${deployment_id}-${hash}"
[[ ${#bucket} -le 63 && $bucket =~ ^[a-z0-9][a-z0-9-]+[a-z0-9]$ ]] || die "derived bucket name is invalid"
state_key="states/${deployment_id}/terraform.tfstate"
deployment_root="$project_root/deployments/$deployment_id"
backend_dir="$deployment_root/terraform"
backend_file="$backend_dir/backend.hcl"
endpoint="oss-${region}.aliyuncs.com"

record_metadata() {
  atomic_jq "$manifest" --arg dir "$backend_dir" --arg file "$backend_file" \
    --arg bucket "$bucket" --arg key "$state_key" '
    .terraform.backendDirectory=$dir |
    .terraform.stateReference=$file |
    .terraform.stateBucket=$bucket |
    .terraform.stateKey=$key |
    .terraform.backendStatus=(.terraform.backendStatus // "pending")'
}

case "$command" in
  metadata)
    record_metadata
    jq '{stateBucket:.terraform.stateBucket,stateKey:.terraform.stateKey,stateReference:.terraform.stateReference}' "$manifest"
    ;;
  prepare)
    require_command aliyun; require_command ossutil
    configure_cloud_profile "$manifest"
    ossutil_preflight "$region"
    actual_uid=$(aliyun_cli sts GetCallerIdentity --region "$region" | jq -er '.AccountId')
    [[ "$actual_uid" == "$account_uid" ]] || die "Alibaba Cloud account identity mismatch"
    record_metadata
    mkdir -p -- "$backend_dir"; chmod 700 "$deployment_root" "$backend_dir"
    if ! ossutil_cli stat "oss://$bucket" --region "$region" --endpoint "$endpoint" >/dev/null 2>&1; then
      ossutil_cli mb "oss://$bucket" --region "$region" --endpoint "$endpoint"
    fi
    if [[ "$OSSUTIL_CONTRACT" == v2 ]]; then
      ossutil_cli api put-bucket-acl --bucket "$bucket" --acl private --region "$region" --endpoint "$endpoint" >/dev/null
      ossutil_cli api put-bucket-versioning --bucket "$bucket" --versioning-configuration '{"Status":"Enabled"}' --region "$region" --endpoint "$endpoint" >/dev/null
      tags=$(jq -cn --arg deployment "$deployment_id" '{TagSet:{Tag:[{Key:"Project",Value:"AutoWonder"},{Key:"Environment",Value:"auto-wonder-prod"},{Key:"DeploymentId",Value:$deployment},{Key:"ManagedBy",Value:"AutoWonderBackend"},{Key:"Topology",Value:"multi-az-ha"}]}}')
      ossutil_cli api put-bucket-tags --bucket "$bucket" --tagging "$tags" --region "$region" --endpoint "$endpoint" >/dev/null
    else
      ossutil_cli set-acl "oss://$bucket" private -b -e "$endpoint" >/dev/null
      ossutil_cli bucket-versioning --method put "oss://$bucket" enabled -e "$endpoint" >/dev/null
      ossutil_cli bucket-tagging --method put "oss://$bucket" \
        'Project#AutoWonder' 'Environment#auto-wonder-prod' "DeploymentId#$deployment_id" \
        'ManagedBy#AutoWonderBackend' 'Topology#multi-az-ha' -e "$endpoint" >/dev/null
    fi
    printf 'bucket = "%s"\nkey = "%s"\nregion = "%s"\nendpoint = "%s"\nacl = "private"\nencrypt = true\n' \
      "$bucket" "$state_key" "$region" "$endpoint" >"$backend_file"
    chmod 600 "$backend_file"
    atomic_jq "$manifest" '.terraform.backendStatus="ready"'
    printf '{"phase":"terraform-backend","status":"ready"}\n'
    ;;
  destroy)
    [[ $(jq -r '.terraform.mainDestroyVerified // false' "$manifest") == true ]] || die "main Terraform destroy is not verified"
    [[ $(jq -r '.terraform.stateBucket // empty' "$manifest") == "$bucket" ]] || die "state bucket metadata mismatch"
    require_command ossutil
    ossutil_preflight "$region"
    ossutil_cli rm "oss://$bucket" --all-versions -r -f --region "$region" --endpoint "$endpoint"
    ossutil_cli rm "oss://$bucket" -m -r -f --region "$region" --endpoint "$endpoint"
    if ossutil_cli rb --help >/dev/null 2>&1; then
      ossutil_cli rb "oss://$bucket" --force --region "$region" --endpoint "$endpoint"
    else
      ossutil_cli rm "oss://$bucket" -b -f --region "$region" --endpoint "$endpoint"
    fi
    rm -f -- "$backend_file"
    rm -rf -- "$backend_dir/.terraform" "$backend_dir/work"
    atomic_jq "$manifest" '.terraform.backendStatus="destroyed"'
    printf '{"phase":"terraform-backend","status":"destroyed"}\n'
    ;;
  *) die "unsupported backend command";;
esac
