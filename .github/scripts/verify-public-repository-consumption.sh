#!/usr/bin/env bash
set -euo pipefail
VERSION=${1:-}
REPOSITORY_URL=${2:-https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/}
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 X.Y.Z [repository-url]" >&2; exit 2
fi
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CONSUMER_POM="$ROOT_DIR/.github/public-repository-consumer/pom.xml"
ATTEMPTS=${PUBLIC_REPOSITORY_ATTEMPTS:-1}
RETRY_SECONDS=${PUBLIC_REPOSITORY_RETRY_SECONDS:-10}
MAVEN_COMMAND=${MAVEN_COMMAND:-mvn}
[[ "$ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || { echo "PUBLIC_REPOSITORY_ATTEMPTS must be positive" >&2; exit 2; }
[[ "$RETRY_SECONDS" =~ ^[0-9]+$ ]] || { echo "PUBLIC_REPOSITORY_RETRY_SECONDS must be non-negative" >&2; exit 2; }

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
SETTINGS="$WORK_DIR/settings.xml"
LOCAL_REPO="$WORK_DIR/repository"
LOG="$WORK_DIR/maven.log"
cat > "$SETTINGS" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><interactiveMode>false</interactiveMode></settings>
XML

run_clean() {
  env -u GITHUB_TOKEN -u GH_TOKEN -u GITHUB_ACTOR \
      -u CENTRAL_USERNAME -u CENTRAL_PASSWORD \
      -u MAVEN_CENTRAL_USERNAME -u MAVEN_CENTRAL_PASSWORD \
      -u MAVEN_GPG_KEY -u MAVEN_GPG_PASSPHRASE -u MAVEN_ARGS \
    "$MAVEN_COMMAND" -B -U -s "$SETTINGS" -Dmaven.repo.local="$LOCAL_REPO" "$@"
}

resolve_once() {
  rm -rf "$LOCAL_REPO"; : > "$LOG"
  set +e
  (
    run_clean org.apache.maven.plugins:maven-dependency-plugin:3.11.0:get \
      -Dartifact="io.github.carstenartur:jgit-storage-hibernate-parent:${VERSION}:pom" \
      -DremoteRepositories="jgit-public::default::${REPOSITORY_URL}" -Dtransitive=false \
    && run_clean -f "$CONSUMER_POM" \
      -Dpublic.version="$VERSION" -Dpublic.repository.url="$REPOSITORY_URL" dependency:go-offline
  ) 2>&1 | tee "$LOG"
  status=${PIPESTATUS[0]}; set -e
  [[ $status -eq 0 ]] || return "$status"

  if grep -Eiq 'maven\.pkg\.github\.com|central\.sonatype\.com|repo1\.maven\.org/.*/jgit-storage-hibernate' "$LOG"; then
    echo "Project artifacts were resolved from an unintended publishing service" >&2
    return 1
  fi

  if grep -Eiq 'checksum validation failed|no checksums available|could not validate (artifact )?integrity' "$LOG"; then
    echo "Maven resolved an artifact without a compatible integrity sidecar" >&2
    return 1
  fi

  local base="$LOCAL_REPO/io/github/carstenartur" artifact extension expected
  for artifact in jgit-storage-hibernate-parent jgit-storage-hibernate-core jgit-storage-hibernate-search jgit-storage-hibernate-java-analysis jgit-storage-hibernate-architecture; do
    extension=jar; [[ "$artifact" == jgit-storage-hibernate-parent ]] && extension=pom
    expected="$base/$artifact/$VERSION/$artifact-$VERSION.$extension"
    [[ -s "$expected" ]] || { echo "Missing resolved artifact: $expected" >&2; return 1; }
  done
  echo "Anonymous public repository consumption and checksum validation verified for $VERSION from $REPOSITORY_URL"
}

for ((attempt=1; attempt<=ATTEMPTS; attempt++)); do
  echo "Public repository resolution attempt $attempt/$ATTEMPTS"
  if resolve_once; then exit 0; fi
  (( attempt < ATTEMPTS )) && sleep "$RETRY_SECONDS"
done
echo "Could not resolve all $VERSION artifacts anonymously with valid checksums from $REPOSITORY_URL" >&2
exit 1
