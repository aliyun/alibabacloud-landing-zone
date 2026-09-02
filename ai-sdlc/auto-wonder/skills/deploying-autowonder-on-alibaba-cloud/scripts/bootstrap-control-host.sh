#!/usr/bin/env bash
set -euo pipefail
install_macos_dependency() {
  local command_name=$1 package_name=$2
  command -v "$command_name" >/dev/null || brew install "$package_name"
}
if [[ $(uname -s) == Darwin ]]; then
  install_macos_dependency git git
  install_macos_dependency jq jq
  install_macos_dependency terraform terraform
  install_macos_dependency aliyun aliyun-cli
  install_macos_dependency ossutil ossutil
  install_macos_dependency openssl openssl@3
  install_macos_dependency curl curl
  install_macos_dependency python3 python@3.13
  install_macos_dependency gtar gnu-tar
  install_macos_dependency java openjdk@21
  install_macos_dependency mvn maven
fi
for tool in bash git jq terraform aliyun ossutil openssl curl python3 java mvn; do
  command -v "$tool" >/dev/null || { printf 'missing dependency: %s\n' "$tool" >&2; exit 1; }
done
printf '{"platform":"posix","validated":true}\n'
