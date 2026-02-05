#!/usr/bin/env bash
set -euo pipefail

SOURCE="${BASH_SOURCE[0]:-$0}"
PRJ_ROOT="$(cd "$(dirname -- "${SOURCE}")/.." && pwd -P)"

BASE_URL="${BASE_URL:-http://localhost:8081}"

die() {
  echo "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage:
  ./bin/akucli.sh list [--base-url=URL] [--json]
  ./bin/akucli.sh deploy --model=PATH [--processKey=KEY] [--base-url=URL]
  ./bin/akucli.sh run --processKey=KEY --version=N [--vars=JSON] [--vars-file=PATH] [--base-url=URL]

Examples:
  ./bin/akucli.sh list
  ./bin/akucli.sh deploy --model=./src/test/resources/bpmn/TestProcess.bpmn20.xml
  ./bin/akucli.sh run --processKey=CaseIdLogger --version=2 --vars='{"caseId":"123"}'
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

print_body() {
  local body="$1"
  if jq -e . >/dev/null 2>&1 <<<"$body"; then
    jq . <<<"$body"
  else
    echo "$body"
  fi
}

request_json() {
  local method="$1"
  local url="$2"
  local data="${3:-}"
  local response status body

  if [[ -n "$data" ]]; then
    response="$(curl -sS -H "Accept: application/json" -H "Content-Type: application/json" \
      -X "$method" -d "$data" -w "\n%{http_code}" "$url")"
  else
    response="$(curl -sS -H "Accept: application/json" \
      -X "$method" -w "\n%{http_code}" "$url")"
  fi

  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

  echo "$status"$'\n'"$body"
}

extract_attr() {
  local file="$1"
  local pattern="$2"
  local attr="$3"
  sed -rn -- "/$pattern/s/^.*${attr}=\"([^\"]*)\".*$/\\1/p" "$file" | head -n 1
}

json_payload() {
  local file="$1"
  local process_key="$2"
  jq -Rs --arg processKey "$process_key" '{processKey:$processKey, xml:.}' "$file"
}

list_defs() {
  local base_url="$BASE_URL"
  local json=false

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --base-url=*) base_url="${1#*=}" ;;
      --json) json=true ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown option: $1" ;;
    esac
    shift
  done

  local status body
  read -r status body < <(request_json "GET" "$base_url/api/bpmn/definitions")

  if [[ "$status" != "200" ]]; then
    echo "Request failed with status $status" >&2
    print_body "$body" >&2
    exit 1
  fi

  if ! jq -e 'type=="array"' >/dev/null 2>&1 <<<"$body"; then
    echo "Unexpected response from server:" >&2
    print_body "$body" >&2
    exit 1
  fi

  if [[ "$json" == "true" ]]; then
    print_body "$body"
    return
  fi

  printf "processKey\tversion\tactive\tprocessName\tversionTag\tmodelerVersion\tinitialVars\n"
  jq -r '
    .[] | [
      .processKey,
      .version,
      .active,
      (.processName // "n/a"),
      (.versionTag // "n/a"),
      (.modelerVersion // "n/a"),
      (.initialVars | tojson)
    ] | @tsv
  ' <<<"$body"
}

deploy_model() {
  local base_url="$BASE_URL"
  local model=""
  local process_key=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --base-url=*) base_url="${1#*=}" ;;
      --model=*) model="${1#*=}" ;;
      --processKey=*) process_key="${1#*=}" ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown option: $1" ;;
    esac
    shift
  done

  [[ -n "$model" ]] || die "Missing --model=PATH"
  [[ -f "$model" ]] || die "Model file not found: $model"

  if [[ -z "$process_key" ]]; then
    process_key="$(extract_attr "$model" "<bpmn:process" "id")"
    if [[ -z "$process_key" ]]; then
      process_key="$(basename "$model")"
      process_key="${process_key%.*}"
    fi
  fi

  local payload status body
  payload="$(json_payload "$model" "$process_key")"
  read -r status body < <(request_json "POST" "$base_url/api/bpmn/deploy" "$payload")

  if [[ "$status" =~ ^2 ]]; then
    print_body "$body"
  else
    echo "Deploy failed with status $status" >&2
    print_body "$body" >&2
    exit 1
  fi
}

run_case() {
  local base_url="$BASE_URL"
  local process_key=""
  local version=""
  local vars="{}"
  local vars_file=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --base-url=*) base_url="${1#*=}" ;;
      --processKey=*) process_key="${1#*=}" ;;
      --version=*) version="${1#*=}" ;;
      --vars=*) vars="${1#*=}" ;;
      --vars-file=*) vars_file="${1#*=}" ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown option: $1" ;;
    esac
    shift
  done

  [[ -n "$process_key" ]] || die "Missing --processKey=KEY"
  [[ -n "$version" ]] || die "Missing --version=N"

  if [[ -n "$vars_file" ]]; then
    [[ -f "$vars_file" ]] || die "Vars file not found: $vars_file"
    vars="$(cat "$vars_file")"
  fi

  if ! jq -e . >/dev/null 2>&1 <<<"$vars"; then
    die "Invalid JSON for --vars or --vars-file"
  fi

  local payload status body
  payload="$(jq -n --arg processKey "$process_key" --arg version "$version" --argjson initialVars "$vars" \
    '{processKey:$processKey, version:($version|tonumber), initialVars:$initialVars}')"
  read -r status body < <(request_json "POST" "$base_url/api/cases" "$payload")

  if [[ "$status" =~ ^2 ]]; then
    print_body "$body"
  else
    echo "Run failed with status $status (processKey=$process_key version=$version)" >&2
    print_body "$body" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd jq

cmd="${1:-}"
shift || true

case "$cmd" in
  list) list_defs "$@" ;;
  deploy) deploy_model "$@" ;;
  run) run_case "$@" ;;
  -h|--help|"") usage ;;
  *) die "Unknown command: $cmd" ;;
esac
