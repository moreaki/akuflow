#!/usr/bin/env bash
set -euo pipefail

SOURCE="${BASH_SOURCE[0]:-$0}"
PRJ_ROOT="$(cd "$(dirname -- "${SOURCE}")/.." && pwd -P)"

BASE_URL="${BASE_URL:-http://localhost:8081}"
BPMN_DIR="${BPMN_DIR:-$PRJ_ROOT/src/test/resources/bpmn}"

die() {
  echo "$*" >&2
  exit 1
}

info() {
  echo "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

extract_attr() {
  local file="$1"
  local pattern="$2"
  local attr="$3"
  sed -rn -- "/$pattern/s/^.*${attr}=\"([^\"]*)\".*$/\1/p" "$file" | head -n 1
}

json_payload() {
  local file="$1"
  local process_key="$2"
  jq -Rs --arg processKey "$process_key" '{processKey:$processKey, xml:.}' "$file"
}

deploy_file() {
  local file="$1"
  local filename process_key process_name version_tag modeler_version

  filename="$(basename "$file")"
  process_key="$(extract_attr "$file" "<bpmn:process" "id")"
  if [[ -z "$process_key" ]]; then
    process_key="${filename%.bpmn}"
  fi
  process_name="$(extract_attr "$file" "<bpmn:process" "name")"
  if [[ -z "$process_name" ]]; then
    process_name="$process_key"
  fi
  version_tag="$(extract_attr "$file" "<bpmn:process" "camunda:versionTag")"
  modeler_version="$(extract_attr "$file" "<bpmn:definitions" "modeler:executionPlatformVersion")"

  info "Deploying $file (processKey=$process_key version=${version_tag:-n/a} modeler=${modeler_version:-n/a})"
  json_payload "$file" "$process_key" | curl -sS -f -X POST "$BASE_URL/api/bpmn/deploy" \
    -H "Content-Type: application/json" \
    -d @- | jq .
}

require_cmd jq

if [[ ! -d "$BPMN_DIR" ]]; then
  die "BPMN directory not found: $BPMN_DIR"
fi

mapfile -t BPMN_FILES < <(find "$BPMN_DIR" -type f -name "*.bpmn20.xml" | sort)

if [[ ${#BPMN_FILES[@]} -eq 0 ]]; then
  info "No .bpmn files found in $BPMN_DIR"
  exit 0
fi

for file in "${BPMN_FILES[@]}"; do
  deploy_file "$file"
  echo
done
