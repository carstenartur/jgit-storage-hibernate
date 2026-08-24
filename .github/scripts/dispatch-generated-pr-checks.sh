#!/usr/bin/env bash
set -euo pipefail

# Explicit workflow_dispatch calls are required when a release workflow uses the
# repository GITHUB_TOKEN to create a pull request. Ordinary push events are
# intentionally suppressed by GitHub in that case, while workflow_dispatch runs
# are allowed and attach their checks to the generated branch head.

: "${GH_TOKEN:?GH_TOKEN is required to dispatch generated pull-request checks}"

fail() {
  echo "::error::$*"
  exit 1
}

trim() {
  local value=${1-}
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

branch=$(trim "${1:-}")
[[ -n "$branch" ]] || fail "generated pull-request branch is required"
case "$branch" in
  release/prepare-*|release/next-*) ;;
  *) fail "refusing to dispatch generated checks for unexpected branch $branch" ;;
esac

local_head=$(git rev-parse HEAD)
remote_head=$(git ls-remote --heads origin "refs/heads/$branch" | awk 'NR == 1 { print $1 }')
[[ -n "$remote_head" ]] || fail "generated branch $branch does not exist on origin"
[[ "$remote_head" == "$local_head" ]] \
  || fail "generated branch $branch points to $remote_head, but the prepared worktree is $local_head"

if [[ -n "${GENERATED_PR_CHECK_WORKFLOWS:-}" ]]; then
  read -r -a workflows <<< "$GENERATED_PR_CHECK_WORKFLOWS"
else
  workflows=(
    maven.yml
    bom-contract.yml
    jgit-compatibility.yml
    consumer-compatibility.yml
    server-image.yml
    server-image-publish-contract.yml
    performance.yml
  )
fi

((${#workflows[@]} > 0)) || fail "no generated pull-request workflows are configured"

for workflow in "${workflows[@]}"; do
  [[ "$workflow" =~ ^[A-Za-z0-9._-]+\.ya?ml$ ]] \
    || fail "invalid workflow file name $workflow"
  [[ -f ".github/workflows/$workflow" ]] \
    || fail "configured generated pull-request workflow does not exist: $workflow"
  echo "Dispatching $workflow for $branch at $remote_head"
  gh workflow run "$workflow" --ref "$branch"
done

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### Generated pull-request checks\n\n'
    printf 'Dispatched %d repository-owned workflows for `%s` at `%s`.\n' \
      "${#workflows[@]}" "$branch" "$remote_head"
  } >> "$GITHUB_STEP_SUMMARY"
fi
