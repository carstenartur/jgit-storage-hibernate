#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"

trim() {
  local value=${1-}
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

fail() {
  echo "::error::$*"
  exit 1
}

boolean() {
  [[ "${!1}" == true || "${!1}" == false ]] || fail "$1 must be true or false"
}

RELEASE_ACTION=$(trim "${RELEASE_ACTION:-prepare}")
RELEASE_VERSION=$(trim "$RELEASE_VERSION")
NEXT_VERSION_INPUT=$(trim "${NEXT_VERSION_INPUT:-}")
REQUEST_BRANCH=$(trim "${REQUEST_BRANCH:-}")
RELEASE_PR_BRANCH=$(trim "${RELEASE_PR_BRANCH:-}")
RELEASE_AUTOMATION_TOKEN=$(trim "${RELEASE_AUTOMATION_TOKEN:-}")
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
TAG_NAME="v${RELEASE_VERSION}"
RELEASE_BRANCH="release/prepare-${RELEASE_VERSION}"
RELEASE_CANDIDATE_FILE=.github/release-candidate.json
DOCUMENTED_RELEASE_VERSION_FILE=docs/current-release-version.txt
PUBLIC_REPOSITORY_BRANCH=${PUBLIC_REPOSITORY_BRANCH:-maven-repository}
PUBLIC_REPOSITORY_URL=${PUBLIC_REPOSITORY_URL:-https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/${PUBLIC_REPOSITORY_BRANCH}/}
PUBLIC_REPOSITORY_ATTEMPTS=${PUBLIC_REPOSITORY_ATTEMPTS:-12}
PUBLIC_REPOSITORY_RETRY_SECONDS=${PUBLIC_REPOSITORY_RETRY_SECONDS:-10}
PUBLIC_REPOSITORY_STAGE=${PUBLIC_REPOSITORY_STAGE:-$PWD/target/public-maven-repository}
PUBLIC_REPOSITORY_EVIDENCE=${PUBLIC_REPOSITORY_EVIDENCE:-$PWD/target/public-repository-evidence/manifest.json}
RELEASE_ARTIFACTS=${RELEASE_ARTIFACTS:-$PWD/target/release-artifacts}
WORKTREE=

cleanup() {
  if [[ -n "$WORKTREE" ]]; then
    git worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

boolean SKIP_TESTS
boolean DRY_RUN
[[ "$RELEASE_ACTION" == prepare || "$RELEASE_ACTION" == finalize ]] \
  || fail "RELEASE_ACTION must be prepare or finalize"
[[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "release version must use X.Y.Z"
[[ "$SKIP_TESTS" != true || "$DRY_RUN" == true ]] \
  || fail "Real releases must run the complete test suite"
[[ "$RELEASE_ACTION" != finalize || "$DRY_RUN" == false ]] \
  || fail "Release finalization cannot be a dry run"

if [[ -n "$NEXT_VERSION_INPUT" ]]; then
  NEXT_VERSION=$NEXT_VERSION_INPUT
else
  IFS=. read -r major minor patch <<< "$RELEASE_VERSION"
  NEXT_VERSION="$major.$minor.$((patch + 1))-SNAPSHOT"
fi
[[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]] \
  || fail "next development version must use X.Y.Z-SNAPSHOT"

python3 - "$RELEASE_VERSION" "$NEXT_VERSION" <<'PY'
import sys
release = tuple(map(int, sys.argv[1].split(".")))
next_version = tuple(map(int, sys.argv[2].removesuffix("-SNAPSHOT").split(".")))
if next_version <= release:
    raise SystemExit(
        f"::error::Next development version {sys.argv[2]} must be newer than {sys.argv[1]}"
    )
PY

configure_git() {
  git config user.name 'github-actions[bot]'
  git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
}

project_version() {
  mvn -q -DforceStdout help:evaluate -Dexpression=project.version
}

verify_repository_contract() {
  python3 .github/scripts/verify-release-consistency.py
  python3 .github/scripts/verify-public-repository-publishing.py
  git diff --check
}

verify_no_snapshot_poms() {
  if grep -R "SNAPSHOT" --include='pom.xml' --exclude-dir=target --exclude-dir=.git .; then
    fail "SNAPSHOT POM reference remains in release candidate"
  fi
}

run_complete_build() {
  docker info >/dev/null
  if [[ "$SKIP_TESTS" == true ]]; then
    mvn -B -DskipTests verify
  else
    mvn -B verify
  fi
}

remote_branch_exists() {
  git ls-remote --exit-code --heads origin "refs/heads/$1" >/dev/null 2>&1
}

push_replaceable_branch() {
  local branch=$1
  if remote_branch_exists "$branch"; then
    git fetch origin "$branch"
  fi
  git push --force-with-lease origin "HEAD:refs/heads/$branch"
}

append_summary() {
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"
  fi
}

open_pull_request_when_configured() {
  local branch=$1 title=$2 body_file=$3
  if [[ -z "$RELEASE_AUTOMATION_TOKEN" ]]; then
    echo "No RELEASE_GITHUB_TOKEN is configured; branch $branch is ready for a protected pull request."
    append_summary "Branch \`$branch\` is ready. Open its protected pull request to continue."
    return 0
  fi

  local existing
  existing=$(GH_TOKEN="$RELEASE_AUTOMATION_TOKEN" gh pr list \
    --head "$branch" --base main --state open --json number --jq '.[0].number // empty')
  if [[ -n "$existing" ]]; then
    echo "Pull request #$existing already exists for $branch"
  else
    GH_TOKEN="$RELEASE_AUTOMATION_TOKEN" gh pr create \
      --base main \
      --head "$branch" \
      --title "$title" \
      --body-file "$body_file"
  fi
}

write_release_candidate() {
  SOURCE_COMMIT=$(git rev-parse HEAD) \
  RELEASE_VERSION="$RELEASE_VERSION" \
  NEXT_VERSION="$NEXT_VERSION" \
  REQUEST_BRANCH="$REQUEST_BRANCH" \
  RELEASE_CANDIDATE_FILE="$RELEASE_CANDIDATE_FILE" \
  python3 - <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

candidate = {
    "schema_version": 1,
    "release_version": os.environ["RELEASE_VERSION"],
    "next_development_version": os.environ["NEXT_VERSION"],
    "request_branch": os.environ["REQUEST_BRANCH"],
    "source_commit": os.environ["SOURCE_COMMIT"],
    "prepared_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
}
Path(os.environ["RELEASE_CANDIDATE_FILE"]).write_text(
    json.dumps(candidate, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
}

validate_release_candidate() {
  RELEASE_VERSION="$RELEASE_VERSION" \
  NEXT_VERSION="$NEXT_VERSION" \
  RELEASE_CANDIDATE_FILE="$RELEASE_CANDIDATE_FILE" \
  python3 - <<'PY'
import json
import os
from pathlib import Path

path = Path(os.environ["RELEASE_CANDIDATE_FILE"])
if not path.is_file():
    raise SystemExit(f"::error::Missing merged release candidate {path}")
data = json.loads(path.read_text(encoding="utf-8"))
expected = {
    "release_version": os.environ["RELEASE_VERSION"],
    "next_development_version": os.environ["NEXT_VERSION"],
}
for key, value in expected.items():
    if data.get(key) != value:
        raise SystemExit(
            f"::error::Release candidate {key}={data.get(key)!r}; expected {value!r}"
        )
PY
}

prepare_release() {
  local current documented pr_body
  current=$(project_version)
  [[ "$current" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]] \
    || fail "Current Maven version must use X.Y.Z-SNAPSHOT, found $current"
  [[ "${current%-SNAPSHOT}" == "$RELEASE_VERSION" ]] \
    || fail "Requested $RELEASE_VERSION from reactor version $current"

  documented=$(tr -d '[:space:]' < "$DOCUMENTED_RELEASE_VERSION_FILE")
  [[ "$documented" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || fail "Invalid documented release version $documented"

  configure_git
  verify_repository_contract
  git fetch origin --tags --force
  if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
    fail "Tag $TAG_NAME already exists; a published release cannot be prepared again"
  fi

  mvn -B versions:set \
    -DnewVersion="$RELEASE_VERSION" \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false
  python3 .github/scripts/update-release-metadata.py "$RELEASE_VERSION" --release
  write_release_candidate
  verify_repository_contract
  verify_no_snapshot_poms
  run_complete_build

  if [[ "$DRY_RUN" == true ]]; then
    echo "Dry run completed after release preparation and complete verification."
    return 0
  fi

  git switch -C "$RELEASE_BRANCH"
  git add -A
  git commit -m "Prepare release $RELEASE_VERSION"
  push_replaceable_branch "$RELEASE_BRANCH"

  pr_body=$(mktemp)
  cat > "$pr_body" <<EOF
## Release $RELEASE_VERSION

This protected release pull request was generated from the repository-owned release request.

- every Maven module uses the single reactor version \`$RELEASE_VERSION\`;
- release metadata and public dependency examples use \`$RELEASE_VERSION\`;
- the complete Maven verification ran before this branch was pushed;
- immutable publication, tag creation and the GitHub release happen only after this PR is merged;
- the next protected PR will restore the reactor to \`$NEXT_VERSION\`.
EOF
  open_pull_request_when_configured \
    "$RELEASE_BRANCH" \
    "Release $RELEASE_VERSION" \
    "$pr_body"
  rm -f "$pr_body"
  append_summary "Prepared release branch \`$RELEASE_BRANCH\` for version \`$RELEASE_VERSION\`."
}

remove_volatile_version_files() {
  local root=$1
  [[ -d "$root/io/github/carstenartur" ]] || return 0
  find "$root/io/github/carstenartur" -type f -path "*/$RELEASE_VERSION/*" \
    \( -name '*.md5' -o -name '*.lastUpdated' -o -name '_remote.repositories' \) \
    -delete
}

stage_public_repository() {
  rm -rf "$PUBLIC_REPOSITORY_STAGE" "$(dirname "$PUBLIC_REPOSITORY_EVIDENCE")"
  mkdir -p "$PUBLIC_REPOSITORY_STAGE"
  mvn -B -Ppublic-repository-release \
    -DskipTests \
    -Dpublic.repository.directory="$PUBLIC_REPOSITORY_STAGE" \
    deploy
  python3 .github/scripts/prepare-public-repository.py \
    "$RELEASE_VERSION" \
    "$PUBLIC_REPOSITORY_STAGE" \
    "$PUBLIC_REPOSITORY_EVIDENCE"
  MAVEN_COMMAND=mvn PUBLIC_REPOSITORY_ATTEMPTS=1 \
    .github/scripts/verify-public-repository-consumption.sh \
    "$RELEASE_VERSION" \
    "file://$PUBLIC_REPOSITORY_STAGE"
}

publish_repository() {
  local exists=false file rel dest manifest existing_version_files=false
  remote_branch_exists "$PUBLIC_REPOSITORY_BRANCH" && exists=true
  WORKTREE=$(mktemp -d)
  rmdir "$WORKTREE"

  if [[ "$exists" == true ]]; then
    git fetch origin "$PUBLIC_REPOSITORY_BRANCH"
    git worktree add --detach "$WORKTREE" "origin/$PUBLIC_REPOSITORY_BRANCH"
  else
    git worktree add --detach "$WORKTREE" HEAD
    git -C "$WORKTREE" switch --orphan "$PUBLIC_REPOSITORY_BRANCH"
    git -C "$WORKTREE" rm -rf . >/dev/null 2>&1 || true
  fi

  git -C "$WORKTREE" config user.name 'github-actions[bot]'
  git -C "$WORKTREE" config user.email '41898282+github-actions[bot]@users.noreply.github.com'
  remove_volatile_version_files "$WORKTREE"

  if find "$WORKTREE/io/github/carstenartur" -type f -path "*/$RELEASE_VERSION/*" \
      -print -quit 2>/dev/null | grep -q .; then
    existing_version_files=true
  fi
  manifest="$WORKTREE/releases/$RELEASE_VERSION.json"
  if [[ "$existing_version_files" == true && ! -f "$manifest" ]]; then
    fail "Published files for $RELEASE_VERSION exist without their immutable manifest"
  fi
  if [[ -f "$manifest" ]] && ! cmp -s "$PUBLIC_REPOSITORY_EVIDENCE" "$manifest"; then
    fail "Immutable release manifest differs for $RELEASE_VERSION"
  fi

  while IFS= read -r -d '' file; do
    rel=${file#"$PUBLIC_REPOSITORY_STAGE/"}
    case "$rel" in
      */"$RELEASE_VERSION"/*)
        dest="$WORKTREE/$rel"
        if [[ -e "$dest" ]] && ! cmp -s "$file" "$dest"; then
          fail "Immutable release file differs: $rel"
        fi
        ;;
    esac
  done < <(find "$PUBLIC_REPOSITORY_STAGE" -type f -print0)

  rsync -a "$PUBLIC_REPOSITORY_STAGE/" "$WORKTREE/"
  mkdir -p "$WORKTREE/releases"
  cp "$PUBLIC_REPOSITORY_EVIDENCE" "$manifest"
  cat > "$WORKTREE/README.md" <<EOF
# jgit-storage-hibernate Maven repository

Anonymous release repository for \`io.github.carstenartur\` artifacts.

Maven URL: \`$PUBLIC_REPOSITORY_URL\`

Published release: \`$RELEASE_VERSION\`
EOF
  cat > "$WORKTREE/index.html" <<EOF
<!doctype html><meta charset="utf-8"><title>jgit-storage-hibernate Maven repository</title><h1>jgit-storage-hibernate Maven repository</h1><p>Anonymous Maven URL: <code>$PUBLIC_REPOSITORY_URL</code></p><p>Latest published release: <strong>$RELEASE_VERSION</strong></p>
EOF
  touch "$WORKTREE/.nojekyll"

  git -C "$WORKTREE" add -A
  if ! git -C "$WORKTREE" diff --cached --quiet; then
    git -C "$WORKTREE" commit -m "Publish Maven repository version $RELEASE_VERSION"
    git -C "$WORKTREE" push origin "HEAD:refs/heads/$PUBLIC_REPOSITORY_BRANCH"
  else
    echo "Immutable Maven repository already contains byte-identical $RELEASE_VERSION artifacts."
  fi
}

ensure_release_tag() {
  local head existing
  head=$(git rev-parse HEAD)
  git fetch origin --tags --force
  if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
    existing=$(git rev-parse "${TAG_NAME}^{commit}")
    [[ "$existing" == "$head" ]] \
      || fail "Tag $TAG_NAME points to $existing instead of merged release commit $head"
    echo "Tag $TAG_NAME already points to the merged release commit."
  else
    git tag -a "$TAG_NAME" -m "Release version $RELEASE_VERSION"
    git push origin "refs/tags/$TAG_NAME"
  fi
}

create_github_release() {
  rm -rf "$RELEASE_ARTIFACTS"
  mkdir -p "$RELEASE_ARTIFACTS"
  find . \
    -path "$RELEASE_ARTIFACTS" -prune -o \
    -path '*/target/*.jar' -type f ! -name 'original-*' \
    -exec cp {} "$RELEASE_ARTIFACTS/" \;
  cp CITATION.cff CITATION.md .zenodo.json codemeta.json \
    "$PUBLIC_REPOSITORY_EVIDENCE" "$RELEASE_ARTIFACTS/"

  if gh release view "$TAG_NAME" >/dev/null 2>&1; then
    echo "GitHub release $TAG_NAME already exists."
  else
    gh release create "$TAG_NAME" "$RELEASE_ARTIFACTS"/* \
      --title "jgit-storage-hibernate $RELEASE_VERSION" \
      --verify-tag \
      --fail-on-no-commits \
      --generate-notes
  fi
}

prepare_next_development() {
  local release_commit next_branch pr_body
  release_commit=$(git rev-parse HEAD)
  next_branch="release/next-${NEXT_VERSION%-SNAPSHOT}"
  git switch -C "$next_branch" "$release_commit"
  mvn -B versions:set \
    -DnewVersion="$NEXT_VERSION" \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false
  python3 .github/scripts/update-release-metadata.py "$NEXT_VERSION"
  rm -f "$RELEASE_CANDIDATE_FILE"
  verify_repository_contract
  git add -A
  git commit -m "Prepare next development version $NEXT_VERSION"
  push_replaceable_branch "$next_branch"

  pr_body=$(mktemp)
  cat > "$pr_body" <<EOF
## Continue development after $RELEASE_VERSION

- every Maven module moves together to \`$NEXT_VERSION\`;
- public documentation remains pinned to the immutable release \`$RELEASE_VERSION\`;
- release-candidate state is removed from \`main\` through this protected PR.
EOF
  open_pull_request_when_configured \
    "$next_branch" \
    "Prepare development $NEXT_VERSION" \
    "$pr_body"
  rm -f "$pr_body"
  append_summary "Published \`$TAG_NAME\`; next development branch is \`$next_branch\`."
}

finalize_release() {
  local current
  current=$(project_version)
  [[ "$current" == "$RELEASE_VERSION" ]] \
    || fail "Merged reactor version $current does not match release $RELEASE_VERSION"
  if [[ -n "$RELEASE_PR_BRANCH" ]]; then
    [[ "$RELEASE_PR_BRANCH" == "$RELEASE_BRANCH" ]] \
      || fail "Finalization branch $RELEASE_PR_BRANCH does not match $RELEASE_BRANCH"
  fi

  configure_git
  validate_release_candidate
  verify_repository_contract
  verify_no_snapshot_poms
  run_complete_build
  stage_public_repository
  publish_repository
  PUBLIC_REPOSITORY_ATTEMPTS="$PUBLIC_REPOSITORY_ATTEMPTS" \
  PUBLIC_REPOSITORY_RETRY_SECONDS="$PUBLIC_REPOSITORY_RETRY_SECONDS" \
    .github/scripts/verify-public-repository-consumption.sh \
    "$RELEASE_VERSION" \
    "$PUBLIC_REPOSITORY_URL"

  ensure_release_tag
  create_github_release
  prepare_next_development

  if [[ -n "$REQUEST_BRANCH" ]]; then
    git push origin --delete "$REQUEST_BRANCH" || true
  fi
}

echo "Release action $RELEASE_ACTION for $RELEASE_VERSION; next $NEXT_VERSION; dry run $DRY_RUN"
case "$RELEASE_ACTION" in
  prepare) prepare_release ;;
  finalize) finalize_release ;;
esac
