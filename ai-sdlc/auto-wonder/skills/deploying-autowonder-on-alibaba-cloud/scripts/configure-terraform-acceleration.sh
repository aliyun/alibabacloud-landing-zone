#!/usr/bin/env bash
set -euo pipefail

config_dir=${AUTOWONDER_TERRAFORM_CONFIG_DIR:-${XDG_CONFIG_HOME:-${HOME:?HOME is required}/.config}/autowonder}
if [[ ${1:-} == --config-dir ]]; then
  [[ -n ${2:-} && $# == 2 ]] || { printf 'Usage: configure-terraform-acceleration.sh [--config-dir DIR]\n' >&2; exit 2; }
  config_dir=$2
elif (($#)); then
  printf 'Usage: configure-terraform-acceleration.sh [--config-dir DIR]\n' >&2
  exit 2
fi

mkdir -p -- "$config_dir"
config_file="$config_dir/terraform-init-acceleration.tfrc"
temporary="$config_file.tmp.$$"
trap 'rm -f -- "$temporary"' EXIT
cat >"$temporary" <<'EOF'
provider_installation {
  network_mirror {
    url = "https://mirrors.aliyun.com/terraform/"
    include = [
      "registry.terraform.io/aliyun/alicloud",
      "registry.terraform.io/hashicorp/alicloud",
    ]
  }
  direct {
    exclude = [
      "registry.terraform.io/aliyun/alicloud",
      "registry.terraform.io/hashicorp/alicloud",
    ]
  }
}
EOF
chmod 600 "$temporary"
mv -f -- "$temporary" "$config_file"
trap - EXIT
printf '%s\n' "$config_file"
