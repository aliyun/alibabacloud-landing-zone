#!/usr/bin/env bash
# Source this helper to configure only the invoking process and its children.
# Resolve this file's own dir across bash, zsh and other shells: BASH_SOURCE is
# unset under zsh, where ${(%):-%x} names the sourced file; $0 is the fallback.
if [ -n "${BASH_SOURCE:-}" ]; then
  _autowonder_runtime_self="${BASH_SOURCE[0]}"
elif [ -n "${ZSH_VERSION:-}" ]; then
  _autowonder_runtime_self="${(%):-%x}"
else
  _autowonder_runtime_self="$0"
fi
AUTOWONDER_RUNTIME_SCRIPTS=$(cd -- "$(dirname -- "$_autowonder_runtime_self")" && pwd -P)
unset _autowonder_runtime_self

autowonder_runtime_environment() {
  local selected assignment project_root
  project_root=$(cd -- "$AUTOWONDER_RUNTIME_SCRIPTS/../../.." && pwd -P)
  AUTOWONDER_PYTHON=$(sh "$AUTOWONDER_RUNTIME_SCRIPTS/runtime-bootstrap.sh" --project-root "$project_root") || return
  export AUTOWONDER_PYTHON PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1
  selected=$("$AUTOWONDER_PYTHON" "$AUTOWONDER_RUNTIME_SCRIPTS/tool_runtime.py" --emit-env --project-root "$project_root") || return
  # ponytail: process substitution keeps exports in the caller; needs bash/zsh,
  # not POSIX sh. Switch to eval of shell-quoted exports if sourced into dash.
  while IFS= read -r -d '' assignment; do
    export "$assignment"
  done < <(printf '%s' "$selected" | "$AUTOWONDER_PYTHON" -c '
import json,sys
env=json.load(sys.stdin)["env"]
for name in ("AUTOWONDER_PYTHON", "JAVA_HOME", "PATH"):
    sys.stdout.buffer.write((name+"="+env[name]).encode("utf-8")+b"\0")
')
}

# Independent public phases inherit no environment from the bootstrap child.
# Reuse only verified local assets for commands absent from the caller's PATH;
# never download, authenticate, or replace installed/mocked commands here.
autowonder_restore_runtime_environment() {
  local system arch root name row_os row_arch version url digest format relative target binary command expected parent safe
  case $(uname -s) in Darwin) system=darwin;; Linux) system=linux;; *) return 0;; esac
  case $(uname -m) in arm64|aarch64) arch=arm64;; x86_64|amd64) arch=x86_64;; *) return 0;; esac
  root=$(cd -- "$AUTOWONDER_RUNTIME_SCRIPTS/../../.." && pwd -P)/skills/.autowonder-tools
  [[ -f "$AUTOWONDER_RUNTIME_SCRIPTS/runtime-lock.tsv" && -d "$root" ]] || return 0
  while IFS=$'\t' read -r name row_os row_arch version url digest format relative; do
    [[ "$row_os" == "$system" && "$row_arch" == "$arch" ]] || continue
    case "$name" in python) command=python3;; jdk) command=java;; maven) command=mvn;; *) command=$name;; esac
    if command -v "$command" >/dev/null 2>&1; then
      # Caller wrappers/mocks must not run before the phase safety gates.
      # Only probe standard system Python locations for the known old-macOS case.
      [[ "$name" == python ]] || continue
      case $(command -v "$command") in /usr/bin/python3|/usr/local/bin/python3|/opt/homebrew/bin/python3) ;;
        *) continue;; esac
      "$command" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' >/dev/null 2>&1 && continue
    fi
    target="$root/$name/$version/$system-$arch"
    binary="$target/$relative"
    [[ -x "$binary" && -f "$target/.verified" ]] || continue
    safe=true; parent=${binary%/*}
    while [[ "$parent" == "$root"* ]]; do
      [[ ! -L "$parent" ]] || safe=false
      parent=${parent%/*}
    done
    [[ "$safe" == true && ! -L "$binary" ]] || continue
    if command -v sha256sum >/dev/null 2>&1; then expected=$(sha256sum "$binary" | awk '{print $1}');
    elif command -v shasum >/dev/null 2>&1; then expected=$(shasum -a 256 "$binary" | awk '{print $1}');
    else continue; fi
    [[ $(cat "$target/.verified") == "$digest"$'\n'"$expected" ]] || continue
    if [[ "$name" == python ]]; then export PATH="${binary%/*}${PATH:+:$PATH}";
    else export PATH="${PATH:+$PATH:}${binary%/*}"; fi
    if [[ "$name" == python ]]; then export AUTOWONDER_PYTHON="$binary" PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1; fi
    if [[ "$name" == jdk ]]; then export JAVA_HOME="${binary%/bin/java}"; fi
  done < "$AUTOWONDER_RUNTIME_SCRIPTS/runtime-lock.tsv"
}
