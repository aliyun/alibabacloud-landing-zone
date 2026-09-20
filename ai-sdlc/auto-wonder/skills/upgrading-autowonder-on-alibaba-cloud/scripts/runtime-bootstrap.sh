#!/bin/sh
# Python/jq-free cold start. WSL deliberately follows Linux, not Windows.
set -eu
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd)
case $(uname -s) in
  MINGW*|MSYS*|CYGWIN*)
    command -v cygpath >/dev/null || { echo 'native Windows path conversion required' >&2; exit 1; }
    native_script=$(cygpath -w "$script_dir/windows/runtime-bootstrap.ps1")
    if [ "${1:-}" = --project-root ]; then
      [ "$#" -eq 2 ] || { echo 'invalid project root arguments' >&2; exit 2; }
      exec powershell.exe -NoProfile -File "$native_script" -ProjectRoot "$(cygpath -w "$2")"
    fi
    exec powershell.exe -NoProfile -File "$native_script" "$@" ;;
esac
fail() { printf '%s\n' "$*" >&2; exit 1; }
canonical_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd)
project_root=$canonical_root
while [ "$#" -gt 0 ]; do
  case "$1" in
    --project-root) [ "$#" -ge 2 ] || fail 'missing project root'; project_root=$2; shift 2 ;;
    --help|-h) printf 'Usage: runtime-bootstrap.sh [--project-root PATH]\n'; exit 0 ;;
    *) fail 'unknown runtime bootstrap argument' ;;
  esac
done
project_root=$(CDPATH= cd -P -- "$project_root" && pwd) || fail 'invalid project root'
[ "$project_root" = "$canonical_root" ] && [ -f "$project_root/pom.xml" ] || fail 'project root must match the canonical skill checkout'
case $(uname -s) in Darwin) system=darwin;; Linux) system=linux;; *) fail 'unsupported platform';; esac
case $(uname -m) in arm64|aarch64) arch=arm64;; x86_64|amd64) arch=x86_64;; *) fail 'unsupported architecture';; esac
case "$arch" in arm64) aliases='("arm64", "aarch64")';; *) aliases='("x86_64", "amd64")';; esac
selfcheck="import ssl,hashlib,zipfile,platform,struct,argparse,json,tarfile,urllib.request,subprocess,pathlib,ipaddress,shutil; assert platform.machine().lower() in $aliases; assert struct.calcsize('P') == 8; print(platform.python_version())"
version= url= digest= format= relative=
tab=$(printf '\t')
while IFS="$tab" read -r name row_os row_arch row_version row_url row_digest row_format row_relative rest; do
  case "$name" in ''|'#'*) continue;; esac
  if [ "$name" = python ] && [ "$row_os" = "$system" ] && [ "$row_arch" = "$arch" ]; then
    [ -z "$version" ] && [ -z "${rest:-}" ] || fail 'duplicate or invalid Python lock'
    version=$row_version url=$row_url digest=$row_digest format=$row_format relative=$row_relative
  fi
done < "$script_dir/runtime-lock.tsv"
[ -n "$version" ] || fail 'no locked Python asset for this platform'
case "$version" in ''|*[!0-9.]*) fail 'invalid locked Python version';; esac
case "$url" in https://*) ;; *) fail 'Python asset must use HTTPS';; esac
[ "${#digest}" -eq 64 ] || fail 'invalid checksum in runtime lock'
case "$digest" in *[!a-f0-9]*) fail 'invalid checksum in runtime lock';; esac
case "$relative" in ''|/*|*'..'*|*\\*|*:*) fail 'invalid archive executable';; esac
[ "$format" = tar.gz ] || fail 'unsupported POSIX archive format'
root="$project_root/skills/.autowonder-tools"
parent="$root/python/$version"
target="$parent/$system-$arch"
# Refuse existing symlinks at every cache boundary.
for directory in "$root" "$root/python" "$parent" "$target"; do
  [ ! -L "$directory" ] || fail 'tool cache cannot be a symlink'
done
umask 077
mkdir -p "$parent"
hash() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}';
  else fail 'SHA256 utility required'; fi
}
valid() {
  [ -f "$target/.verified" ] && [ -x "$target/$relative" ] || return 1
  expected=$(printf '%s\n%s' "$digest" "$(hash "$target/$relative")")
  [ "$(cat "$target/.verified")" = "$expected" ] || return 1
  [ "$("$target/$relative" -I -c "$selfcheck" 2>/dev/null)" = "$version" ]
}
if valid; then printf '%s\n' "$target/$relative"; exit 0; fi
lock="$target.lock"
mkdir "$lock" 2>/dev/null || fail 'tool installation locked; inspect interrupted installer before removing lock'
staging=
cleanup() { [ -z "$staging" ] || rm -rf -- "$staging"; rm -rf -- "$lock"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
printf '%s\n' "$$" > "$lock/pid"
if valid; then printf '%s\n' "$target/$relative"; exit 0; fi
staging=$(mktemp -d "$parent/.$system-$arch.XXXXXX")
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 --output "$staging/archive" "$url"
[ "$(hash "$staging/archive")" = "$digest" ] || fail 'Python checksum mismatch'
tar -tzf "$staging/archive" > "$staging/entries"
while IFS= read -r entry; do
  case "$entry" in /*|../*|*/../*|*/..) fail 'unsafe Python archive path';; esac
done < "$staging/entries"
mkdir "$staging/content"
tar -xzf "$staging/archive" -C "$staging/content"
[ -x "$staging/content/$relative" ] || fail 'Python executable missing from archive'
[ "$("$staging/content/$relative" -I -c "$selfcheck")" = "$version" ] || fail 'Python version/self-check failed'
printf '%s\n%s\n' "$digest" "$(hash "$staging/content/$relative")" > "$staging/content/.verified"
[ ! -e "$target" ] || rm -rf -- "$target"
mv "$staging/content" "$target"
printf '%s\n' "$target/$relative"
