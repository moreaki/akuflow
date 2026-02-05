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
  ./bin/akucli.sh lifecycle --workflowId=ID [--base-url=URL] [--json]
  ./bin/akucli.sh lifecycle --processKey=KEY --version=N [--base-url=URL] [--json]
  ./bin/akucli.sh runtime --workflowId=ID [--base-url=URL] [--json]
  ./bin/akucli.sh runtime --processKey=KEY --version=N [--base-url=URL] [--json]
  ./bin/akucli.sh inspect --workflowId=ID [--base-url=URL] [--json]
  ./bin/akucli.sh inspect --processKey=KEY --version=N [--base-url=URL] [--json]
  ./bin/akucli.sh terminate --workflowId=ID [--reason=TEXT] [--base-url=URL] [--json]
  ./bin/akucli.sh terminate --processKey=KEY --version=N [--reason=TEXT] [--base-url=URL] [--json]
  ./bin/akucli.sh terminate --all-running [--reason=TEXT] [--base-url=URL] [--json]

Examples:
  ./bin/akucli.sh list
  ./bin/akucli.sh deploy --model=./src/test/resources/bpmn/TestProcess.bpmn20.xml
  ./bin/akucli.sh run --processKey=CaseIdLogger --version=2 --vars='{"caseId":"123"}'
  ./bin/akucli.sh lifecycle --workflowId=CaseIdLogger-2-acde1234...
  ./bin/akucli.sh lifecycle --processKey=CaseIdLogger --version=2
  ./bin/akucli.sh runtime --workflowId=CaseIdLogger-2-acde1234...
  ./bin/akucli.sh runtime --processKey=CaseIdLogger --version=2
  ./bin/akucli.sh inspect --processKey=CaseIdLogger --version=2
  ./bin/akucli.sh terminate --workflowId=CaseIdLogger-2-acde1234... --reason="cleanup"
  ./bin/akucli.sh terminate --processKey=CaseIdLogger --version=2 --reason="cleanup"
  ./bin/akucli.sh terminate --all-running --reason="cleanup"
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

lifecycle_case() {
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
      echo "Lifecycle failed with status $status (workflowId=$workflow_id)" >&2
    else
      echo "Lifecycle failed with status $status (processKey=$process_key version=$version)" >&2
    fi
    print_body "$body" >&2
    exit 1
  fi
}

runtime_case() {
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
    url="$base_url/api/cases/$workflow_id/state"
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

    local state_json="$body"
    if [[ -z "$workflow_id" ]]; then
      local wid
      wid="$(jq -r '.workflowId' <<<"$body")"
      url="$base_url/api/cases/$wid/state"
      response="$(request_json "GET" "$url")" \
        || die "Request failed (could not reach server): $url"
      status="${response##*$'\n'}"
      state_json="${response%$'\n'*}"
    fi

    printf "workflowId\tstatus\tcurrentNodeId\tpendingUserTaskId\tpendingSignals\tpendingMessages\n"
    jq -r '
      [
        .workflowId,
        .state.status,
        (.state.currentNodeId // "n/a"),
        (.state.pendingUserTaskId // "n/a"),
        (.state.pendingSignals | tostring),
        (.state.pendingMessages | tostring)
      ] | @tsv
    ' <<<"$state_json"
  else
    if [[ -n "$workflow_id" ]]; then
      echo "Runtime failed with status $status (workflowId=$workflow_id)" >&2
    else
      echo "Runtime failed with status $status (processKey=$process_key version=$version)" >&2
    fi
    print_body "$body" >&2
    exit 1
  fi
}

inspect_case() {
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

  local status_url state_url response status body
  if [[ -n "$workflow_id" ]]; then
    status_url="$base_url/api/cases/$workflow_id"
    state_url="$base_url/api/cases/$workflow_id/state"
  else
    [[ -n "$process_key" ]] || die "Missing --processKey=KEY"
    [[ -n "$version" ]] || die "Missing --version=N"
    status_url="$base_url/api/cases/find?processKey=$process_key&version=$version"
    response="$(request_json "GET" "$status_url")" \
      || die "Request failed (could not reach server): $status_url"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [[ ! "$status" =~ ^2 ]]; then
      echo "Inspect failed with status $status (processKey=$process_key version=$version)" >&2
      print_body "$body" >&2
      exit 1
    fi
    workflow_id="$(jq -r '.workflowId' <<<"$body")"
    status_url="$base_url/api/cases/$workflow_id"
    state_url="$base_url/api/cases/$workflow_id/state"
  fi

  local status_resp status_body state_resp state_body
  status_resp="$(request_json "GET" "$status_url")" \
    || die "Request failed (could not reach server): $status_url"
  status="${status_resp##*$'\n'}"
  status_body="${status_resp%$'\n'*}"
  if [[ ! "$status" =~ ^2 ]]; then
    echo "Inspect failed with status $status (workflowId=$workflow_id)" >&2
    print_body "$status_body" >&2
    exit 1
  fi

  state_resp="$(request_json "GET" "$state_url")" \
    || die "Request failed (could not reach server): $state_url"
  status="${state_resp##*$'\n'}"
  state_body="${state_resp%$'\n'*}"
  if [[ ! "$status" =~ ^2 ]]; then
    echo "Inspect failed with status $status (workflowId=$workflow_id state)" >&2
    print_body "$state_body" >&2
    exit 1
  fi

  if [[ "$json" == "true" ]]; then
    jq -n --argjson lifecycle "$status_body" --argjson runtime "$state_body" \
      '{lifecycle:$lifecycle, runtime:$runtime}'
    return
  fi

  echo "Lifecycle"
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
  ' <<<"$status_body"

  echo
  echo "Runtime"
  printf "workflowId\tstatus\tcurrentNodeId\tpendingUserTaskId\tpendingSignals\tpendingMessages\n"
  jq -r '
    [
      .workflowId,
      .state.status,
      (.state.currentNodeId // "n/a"),
      (.state.pendingUserTaskId // "n/a"),
      (.state.pendingSignals | tostring),
      (.state.pendingMessages | tostring)
    ] | @tsv
  ' <<<"$state_body"
}

terminate_case() {
  local base_url="$BASE_URL"
  local workflow_id=""
  local process_key=""
  local version=""
  local all_running=false
  local reason=""
  local json=false

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --base-url=*) base_url="${1#*=}" ;;
      --workflowId=*) workflow_id="${1#*=}" ;;
      --processKey=*) process_key="${1#*=}" ;;
      --version=*) version="${1#*=}" ;;
      --all-running) all_running=true ;;
      --reason=*) reason="${1#*=}" ;;
      --json) json=true ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown option: $1" ;;
    esac
    shift
  done

  if [[ "$all_running" == "true" ]]; then
    if [[ -n "$workflow_id" || -n "$process_key" ]]; then
      die "--all-running cannot be combined with --workflowId or --processKey"
    fi
  elif [[ -z "$workflow_id" && -z "$process_key" ]]; then
    die "Provide --workflowId or --processKey or --all-running"
  fi
  if [[ -n "$process_key" && -z "$version" ]]; then
    die "Missing --version=N when using --processKey"
  fi

  local payload
  if [[ "$all_running" == "true" ]]; then
    payload="$(jq -n --arg reason "$reason" \
      '{allRunning:true, reason: ($reason | select(length>0))}')"
  elif [[ -n "$workflow_id" ]]; then
    payload="$(jq -n --arg workflowId "$workflow_id" --arg reason "$reason" \
      '{workflowId:$workflowId, reason: ($reason | select(length>0))}')"
  else
    payload="$(jq -n --arg processKey "$process_key" --arg version "$version" --arg reason "$reason" \
      '{processKey:$processKey, version:($version|tonumber), reason: ($reason | select(length>0))}')"
  fi

  local response status body
  response="$(request_json "POST" "$base_url/api/cases/terminate" "$payload")" \
    || die "Request failed (could not reach server): $base_url/api/cases/terminate"
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"

  if [[ "$status" =~ ^2 ]]; then
    if [[ "$json" == "true" ]]; then
      print_body "$body"
      return
    fi
    printf "workflowId\trunId\tstatus\treason\n"
    jq -r '
      .[] | [
        .workflowId,
        (.runId // "n/a"),
        .status,
        (.reason // "n/a")
      ] | @tsv
    ' <<<"$body"
  else
    if [[ -n "$workflow_id" ]]; then
      echo "Terminate failed with status $status (workflowId=$workflow_id)" >&2
    else
      echo "Terminate failed with status $status (processKey=$process_key version=$version)" >&2
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
  lifecycle) lifecycle_case "$@" ;;
  runtime) runtime_case "$@" ;;
  inspect) inspect_case "$@" ;;
  terminate) terminate_case "$@" ;;
  -h|--help|"") usage ;;
  *) die "Unknown command: $cmd" ;;
esac
