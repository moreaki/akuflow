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
  ./bin/akucli.sh status --workflowId=ID [--base-url=URL] [--json]
  ./bin/akucli.sh status --processKey=KEY --version=N [--base-url=URL] [--json]

Examples:
  ./bin/akucli.sh list
  ./bin/akucli.sh deploy --model=./src/test/resources/bpmn/TestProcess.bpmn20.xml
  ./bin/akucli.sh run --processKey=CaseIdLogger --version=2 --vars='{"caseId":"123"}'
  ./bin/akucli.sh status --workflowId=CaseIdLogger-2-acde1234...
  ./bin/akucli.sh status --processKey=CaseIdLogger --version=2
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
  local response

  if [[ -n "$data" ]]; then
    response="$(curl -sS -H "Accept: application/json" -H "Content-Type: application/json" \
      -X "$method" -d "$data" -w "\n%{http_code}" "$url")" || return 1
  else
    response="$(curl -sS -H "Accept: application/json" \
      -X "$method" -w "\n%{http_code}" "$url")" || return 1
  fi

  printf '%s' "$response"
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

  local response status body
  response="$(request_json "GET" "$base_url/api/bpmn/definitions")" \
    || die "Request failed (could not reach server): $base_url/api/bpmn/definitions"
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

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

  local payload response status body
  payload="$(json_payload "$model" "$process_key")"
  response="$(request_json "POST" "$base_url/api/bpmn/deploy" "$payload")" \
    || die "Request failed (could not reach server): $base_url/api/bpmn/deploy"
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

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

  local payload response status body
  payload="$(jq -n --arg processKey "$process_key" --arg version "$version" --argjson initialVars "$vars" \
    '{processKey:$processKey, version:($version|tonumber), initialVars:$initialVars}')"
  response="$(request_json "POST" "$base_url/api/cases" "$payload")" \
    || die "Request failed (could not reach server): $base_url/api/cases"
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

  if [[ "$status" =~ ^2 ]]; then
    print_body "$body"
  else
    echo "Run failed with status $status (processKey=$process_key version=$version)" >&2
    print_body "$body" >&2
    exit 1
  fi
}

status_case() {
  local base_url="$BASE_URL"
  local workflow_id=""
  local json=false
  local process_key=""
  local version=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --base-url=*) base_url="${1#*=}" ;;
      --workflowId=*) workflow_id="${1#*=}" ;;
      --processKey=*) process_key="${1#*=}" ;;
      --version=*) version="${1#*=}" ;;
      --json) json=true ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown option: $1" ;;
    esac
    shift
  done

  local url
  if [[ -n "$workflow_id" ]]; then
    url="$base_url/api/cases/$workflow_id"
  else
    [[ -n "$process_key" ]] || die "Missing --processKey=KEY"
    [[ -n "$version" ]] || die "Missing --version=N"
    url="$base_url/api/cases/find?processKey=$process_key&version=$version"
  fi

  local response status body
  response="$(request_json "GET" "$url")" \
    || die "Request failed (could not reach server): $url"
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

  if [[ "$status" =~ ^2 ]]; then
    if [[ "$json" == "true" ]]; then
      print_body "$body"
      return
    fi
    printf "workflowId\tstatus\trunId\tworkflowType\ttaskQueue\tstartTime\texecutionTime\tcloseTime\n"
    jq -r '
      [
        .workflowId,
        .status,
        (.runId // "n/a"),
        (.workflowType // "n/a"),
        (.taskQueue // "n/a"),
        (.startTime // "n/a"),
        (.executionTime // "n/a"),
        (.closeTime // "n/a")
      ] | @tsv
    ' <<<"$body"
  else
    if [[ -n "$workflow_id" ]]; then
      echo "Status failed with status $status (workflowId=$workflow_id)" >&2
    else
      echo "Status failed with status $status (processKey=$process_key version=$version)" >&2
    fi
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
  status) status_case "$@" ;;
  -h|--help|"") usage ;;
  *) die "Unknown command: $cmd" ;;
esac
