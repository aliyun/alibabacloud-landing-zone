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
