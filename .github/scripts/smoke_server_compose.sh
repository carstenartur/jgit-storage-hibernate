#!/usr/bin/env bash
set -euo pipefail

: "${JSH_ADMIN_USERNAME:?JSH_ADMIN_USERNAME is required}"
: "${JSH_ADMIN_PASSWORD:?JSH_ADMIN_PASSWORD is required}"

base_url="${JSH_BASE_URL:-http://localhost:8080}"
repository="${SMOKE_REPOSITORY:-smoke}"
if [[ ! "$repository" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || [[ ${#repository} -gt 255 ]] \
    || [[ "$repository" == *.git ]] \
    || [[ "$repository" == *..* ]]; then
  printf 'Invalid SMOKE_REPOSITORY %s: use 1-255 letters, digits, dot, underscore or hyphen; do not end in .git or contain two consecutive dots.\n' \
    "$repository" >&2
  exit 1
fi

persist_publication_cleanup_environment() {
  [[ "${GITHUB_ACTIONS:-}" == true ]] || return 0
  [[ -n "${GITHUB_WORKSPACE:-}" ]] || return 0
  [[ -n "${GITHUB_ENV:-}" ]] || return 0
  [[ -d "$GITHUB_WORKSPACE/publication-tooling" ]] || return 0

  local publication_root script_root variable value
  publication_root="$(
    cd -- "$GITHUB_WORKSPACE/publication-tooling"
    pwd -P
  )"
  script_root="$(
    cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
    pwd -P
  )"
  [[ "$script_root" == "$publication_root" ]] || return 0

  for variable in \
      JSH_ADMIN_USERNAME \
      JSH_ADMIN_PASSWORD \
      JSH_DATABASE_PASSWORD \
      JSH_SERVER_IMAGE; do
    value=${!variable-}
    if [[ -z "$value" ]]; then
      printf 'Publication cleanup environment %s must not be empty.\n' \
        "$variable" >&2
      return 1
    fi
    if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
      printf 'Publication cleanup environment %s must be one line.\n' \
        "$variable" >&2
      return 1
    fi
    printf '%s=%s\n' "$variable" "$value" >> "$GITHUB_ENV"
  done
}

# The publication workflow executes the protocol smoke from the outer workspace
# while the script itself lives in a nested checkout. Identify that checkout by
# the script's canonical repository root, then persist its synthetic Compose
# inputs for later log collection and `docker compose down`. Local callers and
# the normal source/released-image jobs resolve to a different script root.
persist_publication_cleanup_environment

work="$(mktemp -d)"
clone_root="$(mktemp -d)"
auth_header_file="$(mktemp)"
cleanup() {
  rm -rf "$work" "$clone_root" "$auth_header_file"
}
trap cleanup EXIT

basic_auth="$(printf '%s' "$JSH_ADMIN_USERNAME:$JSH_ADMIN_PASSWORD" | base64 -w0)"
printf 'Authorization: Basic %s\n' "$basic_auth" > "$auth_header_file"
chmod 600 "$auth_header_file"

curl_authenticated() {
  curl --header "@$auth_header_file" "$@"
}

wait_until_ready() {
  local ready=false
  for attempt in $(seq 1 60); do
    if curl --fail --silent "$base_url/actuator/health/readiness" >/dev/null; then
      ready=true
      break
    fi
    sleep 2
  done
  if [[ "$ready" != true ]]; then
    printf 'Server did not become ready at %s.\n' "$base_url" >&2
    docker compose ps >&2 || true
    docker compose logs postgres git-server >&2 || true
    return 1
  fi
}

wait_until_ready

create_response="$(mktemp)"
create_status="$(curl_authenticated --silent --show-error \
  --output "$create_response" \
  --write-out '%{http_code}' \
  --request POST "$base_url/api/repositories/$repository")"
if [[ "$create_status" -lt 200 || "$create_status" -ge 300 ]]; then
  printf 'Repository creation failed with HTTP %s:\n' "$create_status" >&2
  cat "$create_response" >&2
  printf '\n' >&2
  rm -f "$create_response"
  exit 1
fi
rm -f "$create_response"

git -C "$work" init -b main
git -C "$work" config user.name 'Server Smoke'
git -C "$work" config user.email 'smoke@example.invalid'
printf '%s\n' 'transactional database-backed Git' > "$work/README.md"
git -C "$work" add README.md
git -C "$work" commit -m 'Add smoke history'

export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=http.extraHeader
export GIT_CONFIG_VALUE_0="Authorization: Basic $basic_auth"
remote="$base_url/git/$repository.git"
git -C "$work" push "$remote" main

projection_ready=false
projection_status=''
for attempt in $(seq 1 60); do
  projection_status="$(curl_authenticated --fail-with-body --silent --show-error \
    "$base_url/api/repositories/$repository/index-status")"
  if grep -q '"state":"FAILED"' <<< "$projection_status"; then
    printf 'Projection rebuild failed: %s\n' "$projection_status" >&2
    exit 1
  fi
  if grep -q '"state":"COMPLETED"' <<< "$projection_status" \
      && grep -Eq '"indexedCommits":[1-9][0-9]*' <<< "$projection_status"; then
    projection_ready=true
    break
  fi
  sleep 1
done
if [[ "$projection_ready" != true ]]; then
  printf 'Projection did not include the pushed commit: %s\n' \
    "$projection_status" >&2
  exit 1
fi

curl_authenticated --fail-with-body --silent --show-error \
  "$base_url/api/repositories/$repository/changes?path=README.md&pathMode=exact" \
  | grep -q 'Add smoke history'

rows="$(docker compose exec -T postgres \
  psql -U jgit -d jgit -Atc \
  "select count(*) from jsh_inspection.commit_change where repository_name='$repository' and path='README.md'")"
if [[ "$rows" != 1 ]]; then
  printf 'Expected one inspection row for %s/README.md, found %s.\n' \
    "$repository" "$rows" >&2
  exit 1
fi

docker compose restart git-server
wait_until_ready

git ls-remote --symref "$remote" HEAD \
  | grep -Eq '^ref: refs/heads/main[[:space:]]+HEAD$'
git clone "$remote" "$clone_root/repository"
if [[ "$(git -C "$clone_root/repository" symbolic-ref --short HEAD)" != main ]]; then
  printf 'Cloned repository did not select main as HEAD.\n' >&2
  exit 1
fi
if [[ "$(git -C "$clone_root/repository" log -1 --format=%s)" != 'Add smoke history' ]]; then
  printf 'Cloned repository did not retain the expected commit.\n' >&2
  exit 1
fi
