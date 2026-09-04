#!/usr/bin/env bash
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

repository="$GITHUB_REPOSITORY"
owner="${repository%%/*}"
infrastructure_pr=342
request_title='Request full provider-restart evidence matrix'
request_path='.github/repository-aging-restart-evidence-request.json'
request_branch='request/repository-aging-restart-evidence-20260904-final'
request_id='initial-provider-restart-evidence-2026-09-04'
issue_number=165

log() {
  printf '[restart-evidence] %s\n' "$*"
}

current_main() {
  gh api "repos/$repository/branches/main" --jq '.commit.sha'
}

pull_json() {
  gh api "repos/$repository/pulls/$1"
}

try_merge() {
  local number="$1"
  gh pr merge "$number" --repo "$repository" --squash --delete-branch \
    >/dev/null 2>&1 || true
}

await_merged_pull() {
  local number="$1" attempts="${2:-120}" pull merged_at merge_sha
  for attempt in $(seq 1 "$attempts"); do
    pull="$(pull_json "$number")"
    merged_at="$(jq -r '.merged_at // empty' <<< "$pull")"
    if [[ -n "$merged_at" ]]; then
      merge_sha="$(jq -r '.merge_commit_sha' <<< "$pull")"
      if [[ "$merge_sha" =~ ^[0-9a-f]{40}$ ]]; then
        printf '%s|%s\n' "$merge_sha" "$merged_at"
        return 0
      fi
    fi
    try_merge "$number"
    sleep 10
  done
  return 1
}

pull_files() {
  gh api --paginate "repos/$repository/pulls/$1/files?per_page=100" | jq -s 'add'
}

request_at_commit() {
  gh api -H 'Accept: application/vnd.github.raw+json' \
    "repos/$repository/contents/$request_path?ref=$1"
}

validate_request_payload() {
  local payload_file="$1" expected_source="$2"
  EXPECTED_SOURCE="$expected_source" REQUEST_ID="$request_id" \
    python3 - "$payload_file" <<'PY'
import json
import os
from pathlib import Path
import sys

request = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_keys = {"enabled", "requestId", "reason", "sourceCommit"}
if set(request) != expected_keys:
    raise SystemExit(f"Unexpected request keys: {sorted(request)}")
if request["enabled"] is not True:
    raise SystemExit("enabled must be true")
if request["requestId"] != os.environ["REQUEST_ID"]:
    raise SystemExit("requestId does not match the audited request")
if request["sourceCommit"] != os.environ["EXPECTED_SOURCE"]:
    raise SystemExit("sourceCommit does not match the reviewed base commit")
for key, maximum in (("requestId", 96), ("reason", 240)):
    value = request[key]
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise SystemExit(f"{key} must contain 1-{maximum} characters")
    if "\n" in value or "\r" in value:
        raise SystemExit(f"{key} must be one line")
PY
}

validate_open_request() {
  local number="$1" head_sha="$2" expected_source="$3" files
  files="$(pull_files "$number")"
  if [[ "$(jq 'length' <<< "$files")" -ne 1 \
      || "$(jq -r '.[0].filename // empty' <<< "$files")" != "$request_path" ]]; then
    return 1
  fi
  request_at_commit "$head_sha" > request.json
  validate_request_payload request.json "$expected_source"
}

validate_merged_request() {
  local number="$1" merge_sha="$2" head_sha="$3" files parent
  files="$(pull_files "$number")"
  if [[ "$(jq 'length' <<< "$files")" -ne 1 \
      || "$(jq -r '.[0].filename // empty' <<< "$files")" != "$request_path" ]]; then
    return 1
  fi
  parent="$(gh api "repos/$repository/commits/$merge_sha" --jq '.parents[0].sha')"
  request_at_commit "$head_sha" > request.json
  validate_request_payload request.json "$parent"
}

create_request_commit() {
  local base_sha="$1" base_tree payload blob_sha tree_sha commit_sha
  base_tree="$(gh api "repos/$repository/git/commits/$base_sha" --jq '.tree.sha')"
  payload="$(BASE_SHA="$base_sha" python3 - <<'PY'
import json
import os

request = {
    "enabled": True,
    "requestId": "initial-provider-restart-evidence-2026-09-04",
    "reason": (
        "Capture the initial full repeated PostgreSQL and SQL Server "
        "cold/warm provider-restart evidence matrix."
    ),
    "sourceCommit": os.environ["BASE_SHA"],
}
print(json.dumps(request, indent=2))
PY
  )"
  blob_sha="$(gh api -X POST "repos/$repository/git/blobs" \
    -f content="$payload" -f encoding=utf-8 --jq '.sha')"
  tree_sha="$(jq -n \
      --arg base "$base_tree" \
      --arg path "$request_path" \
      --arg blob "$blob_sha" \
      '{base_tree:$base,tree:[{path:$path,mode:"100644",type:"blob",sha:$blob}]}' \
    | gh api -X POST "repos/$repository/git/trees" --input - --jq '.sha')"
  commit_sha="$(jq -n \
      --arg message 'Request full provider-restart evidence matrix' \
      --arg tree "$tree_sha" \
      --arg parent "$base_sha" \
      '{message:$message,tree:$tree,parents:[$parent]}' \
    | gh api -X POST "repos/$repository/git/commits" --input - --jq '.sha')"
  if gh api "repos/$repository/git/ref/heads/$request_branch" \
      >/dev/null 2>&1; then
    gh api -X PATCH "repos/$repository/git/refs/heads/$request_branch" \
      -f sha="$commit_sha" -F force=true >/dev/null
  else
    gh api -X POST "repos/$repository/git/refs" \
      -f ref="refs/heads/$request_branch" -f sha="$commit_sha" >/dev/null
  fi
  printf '%s\n' "$commit_sha"
}

open_request_pull() {
  local pulls count pull
  pulls="$(gh api -X GET "repos/$repository/pulls" \
    -f state=open -f base=main -f head="$owner:$request_branch" -F per_page=10)"
  count="$(jq 'length' <<< "$pulls")"
  if [[ "$count" -eq 1 ]]; then
    jq -r '.[0].number' <<< "$pulls"
    return 0
  fi
  if [[ "$count" -ne 0 ]]; then
    printf 'Expected at most one open request PR, found %s.\n' "$count" >&2
    return 1
  fi
  pull="$(gh api -X POST "repos/$repository/pulls" \
    -f title="$request_title" \
    -f head="$request_branch" \
    -f base=main \
    -f body='Run the protected repeated PostgreSQL and SQL Server cold/warm provider-restart evidence matrix. This pull request changes only the audited request JSON and does not enable automatic maintenance.')"
  jq -r '.number' <<< "$pull"
}

find_existing_merged_request() {
  gh api --paginate -X GET "repos/$repository/pulls" \
      -f state=closed -f base=main -F per_page=100 \
    | jq -s -c --arg title "$request_title" '
        add
        | map(select(.title == $title and .merged_at != null))
        | sort_by(.merged_at)
        | reverse
        | .[]
      '
}

issue_has_launch_marker() {
  gh api --paginate "repos/$repository/issues/$issue_number/comments?per_page=100" \
    | jq -s -e 'add | any(.[]; (.body // "") | contains("<!-- restart-evidence-request:"))' \
      >/dev/null
}

cleanup_branch_if_unreferenced() {
  local branch="$1" pulls
  pulls="$(gh api -X GET "repos/$repository/pulls" \
    -f state=open -f head="$owner:$branch" -F per_page=10 2>/dev/null || printf '[]')"
  if [[ "$(jq 'length' <<< "$pulls")" -eq 0 ]]; then
    gh api -X DELETE "repos/$repository/git/refs/heads/$branch" \
      >/dev/null 2>&1 || true
  fi
}

log 'Requiring infrastructure PR #342 under protected main rules.'
infrastructure="$(pull_json "$infrastructure_pr")"
if [[ -z "$(jq -r '.merged_at // empty' <<< "$infrastructure")" ]]; then
  try_merge "$infrastructure_pr"
  infrastructure_result="$(await_merged_pull "$infrastructure_pr" 120)" || {
    printf 'Infrastructure PR #%s did not merge.\n' "$infrastructure_pr" >&2
    exit 1
  }
  infrastructure_merge="${infrastructure_result%%|*}"
else
  infrastructure_merge="$(jq -r '.merge_commit_sha' <<< "$infrastructure")"
fi
main_sha="$(current_main)"
comparison_status="$(gh api \
  "repos/$repository/compare/$infrastructure_merge...$main_sha" --jq '.status')"
if [[ "$comparison_status" != identical && "$comparison_status" != ahead ]]; then
  printf 'Protected main does not contain infrastructure merge %s.\n' \
    "$infrastructure_merge" >&2
  exit 1
fi

request_number=''
request_merge=''
request_merged_at=''
log 'Looking for a previously merged audited request to avoid duplicate evidence runs.'
while IFS= read -r candidate; do
  [[ -n "$candidate" ]] || continue
  number="$(jq -r '.number' <<< "$candidate")"
  merge_sha="$(jq -r '.merge_commit_sha' <<< "$candidate")"
  head_sha="$(jq -r '.head.sha' <<< "$candidate")"
  if validate_merged_request "$number" "$merge_sha" "$head_sha"; then
    request_number="$number"
    request_merge="$merge_sha"
    request_merged_at="$(jq -r '.merged_at' <<< "$candidate")"
    log "Reusing merged audited request PR #$number."
    break
  fi
done < <(find_existing_merged_request)

if [[ -z "$request_merge" ]]; then
  log 'Creating or refreshing the exact one-file request from current protected main.'
  for generation in 1 2 3; do
    base_sha="$(current_main)"
    head_sha="$(create_request_commit "$base_sha")"
    request_number="$(open_request_pull)"
    validate_open_request "$request_number" "$head_sha" "$base_sha"
    try_merge "$request_number"
    rebased=false
    for attempt in $(seq 1 120); do
      pull="$(pull_json "$request_number")"
      request_merged_at="$(jq -r '.merged_at // empty' <<< "$pull")"
      if [[ -n "$request_merged_at" ]]; then
        request_merge="$(jq -r '.merge_commit_sha' <<< "$pull")"
        break 2
      fi
      latest_main="$(current_main)"
      if [[ "$latest_main" != "$base_sha" ]]; then
        log "main advanced from $base_sha to $latest_main; rebinding request."
        rebased=true
        break
      fi
      try_merge "$request_number"
      sleep 10
    done
    if [[ "$rebased" != true ]]; then
      printf 'Request PR #%s did not merge under repository protection.\n' \
        "$request_number" >&2
      exit 1
    fi
  done
fi

if [[ ! "$request_merge" =~ ^[0-9a-f]{40}$ || -z "$request_merged_at" ]]; then
  printf 'No merged audited request was established.\n' >&2
  exit 1
fi

log "Awaiting the full workflow dispatched for request merge $request_merge."
evidence_run=''
for attempt in $(seq 1 120); do
  runs="$(gh api "repos/$repository/actions/runs?head_sha=$request_merge&per_page=100")"
  evidence_run="$(jq -c --arg merged "$request_merged_at" '
      [.workflow_runs[]
        | select(.created_at >= $merged)
        | select(.event == "workflow_dispatch")
        | select(
            ((.name // "") + " " + (.display_title // "") + " " + (.path // ""))
            | ascii_downcase
            | test("restart|repository-aging|repository aging")
          )]
      | sort_by(.created_at)
      | reverse
      | .[0] // empty
    ' <<< "$runs")"
  [[ -z "$evidence_run" ]] || break
  sleep 10
done
if [[ -z "$evidence_run" ]]; then
  printf 'No full restart evidence workflow appeared for %s.\n' "$request_merge" >&2
  exit 1
fi
run_id="$(jq -r '.id' <<< "$evidence_run")"
run_url="$(jq -r '.html_url' <<< "$evidence_run")"
run_name="$(jq -r '.name' <<< "$evidence_run")"
run_status="$(jq -r '.status' <<< "$evidence_run")"

log "Requiring the expected expanded matrix on run $run_id."
matrix_jobs=0
for attempt in $(seq 1 90); do
  jobs="$(gh api --paginate \
    "repos/$repository/actions/runs/$run_id/jobs?per_page=100" | jq -s 'add.jobs')"
  matrix_jobs="$(jq 'length' <<< "$jobs")"
  if [[ "$matrix_jobs" -ge 12 ]]; then
    break
  fi
  sleep 10
done
if [[ "$matrix_jobs" -lt 12 ]]; then
  printf 'Evidence run %s exposed only %s jobs; expected at least 12.\n' \
    "$run_id" "$matrix_jobs" >&2
  exit 1
fi

if ! issue_has_launch_marker; then
  marker="<!-- restart-evidence-request:$request_merge -->"
  body="$(cat <<EOF
$marker
Protected request PR #$request_number merged as \`$request_merge\`.

The repository-owned request path launched **$run_name** (run $run_id, current status \`$run_status\`) and exposed $matrix_jobs jobs for the repeated PostgreSQL/SQL Server cold/warm matrix: $run_url

This starts evidence collection only. Automatic maintenance remains disabled; issue #165 stays open until the repeated results, restart evidence and concurrency measurements support measured breakpoints.
EOF
  )"
  gh api -X POST "repos/$repository/issues/$issue_number/comments" \
    -f body="$body" >/dev/null
fi

log 'Cleaning obsolete one-shot branches after the launch is proven.'
for branch in \
  request/repository-aging-restart-evidence-20260904-v2 \
  automation/merge-restart-evidence-request-20260904 \
  automation/await-restart-evidence-request-20260904 \
  automation/record-restart-evidence-launch-20260904 \
  automation/reconcile-restart-evidence-20260904-v2; do
  cleanup_branch_if_unreferenced "$branch"
done

log "Launch proven: request PR #$request_number, run $run_id, jobs $matrix_jobs."
