#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: swapper.sh [option] [jsonParam]

Options:
  --tools --watch --outer-watch --trace --logs --decompile
  --find-subclasses --list-classloaders --list --eval --exec --change-body
  --change-result --replace-class --delete --reset

Set SWAPPER_URL to override http://127.0.0.1:8000/mcp.
Write options (--eval, --exec, --change-body, --change-result, --replace-class)
require SWAPPER_CONFIRM=YES after user confirmation.
EOF
}

if [[ $# -ne 2 ]]; then
  usage >&2
  exit 64
fi

option="$1"
arguments="$2"
case "$option" in
  --tools) method="tools/list"; tool="" ;;
  --watch) method="tools/call"; tool="watch" ;;
  --outer-watch) method="tools/call"; tool="outer_watch" ;;
  --trace) method="tools/call"; tool="trace" ;;
  --logs) method="tools/call"; tool="read_logs" ;;
  --decompile) method="tools/call"; tool="decompile" ;;
  --find-subclasses) method="tools/call"; tool="find_subclasses" ;;
  --list-classloaders) method="tools/call"; tool="list_classloaders" ;;
  --list) method="tools/call"; tool="list_transformers" ;;
  --eval) method="tools/call"; tool="eval" ;;
  --exec) method="tools/call"; tool="exec" ;;
  --change-body) method="tools/call"; tool="change_body" ;;
  --change-result) method="tools/call"; tool="change_result" ;;
  --replace-class) method="tools/call"; tool="replace_class" ;;
  --delete) method="tools/call"; tool="delete_transformer" ;;
  --reset) method="tools/call"; tool="reset" ;;
  *) usage >&2; exit 64 ;;
esac

case "$option" in
  --eval|--exec|--change-body|--change-result|--replace-class)
    if [[ "${SWAPPER_CONFIRM:-}" != "YES" ]]; then
      echo "${option} may execute or change code in the target JVM. Obtain user confirmation, then rerun with SWAPPER_CONFIRM=YES." >&2
      exit 77
    fi
    ;;
esac

command -v jq >/dev/null || { echo 'jq is required' >&2; exit 69; }
jq -e 'type == "object"' >/dev/null <<<"$arguments" || {
  echo 'jsonParam must be a JSON object' >&2
  exit 65
}

request_id="swapper-$(date +%s)-$$"
if [[ "$method" == "tools/list" ]]; then
  request=$(jq -cn --arg id "$request_id" '{jsonrpc:"2.0",id:$id,method:"tools/list"}')
else
  request=$(jq -cn --arg id "$request_id" --arg name "$tool" --argjson arguments "$arguments" \
    '{jsonrpc:"2.0",id:$id,method:"tools/call",params:{name:$name,arguments:$arguments}}')
fi

response=$(curl --fail-with-body -sS "${SWAPPER_URL:-http://127.0.0.1:8000/mcp}" \
  -H 'Content-Type: application/json' --data "$request")
printf '%s\n' "$response" | jq .
printf '%s' "$response" | jq -e '.error == null and (.result.isError // false) == false' >/dev/null
# End of Swapper POSIX wrapper.
