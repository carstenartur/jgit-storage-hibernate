#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:-}
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 X.Y.Z" >&2
  exit 2
fi

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CONSUMER_POM="$ROOT_DIR/.github/central-consumer/pom.xml"
ATTEMPTS=${CENTRAL_CONSUMER_ATTEMPTS:-1}
RETRY_SECONDS=${CENTRAL_CONSUMER_RETRY_SECONDS:-15}
MAVEN_COMMAND=${MAVEN_COMMAND:-mvn}

if ! [[ "$ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  echo "CENTRAL_CONSUMER_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if ! [[ "$RETRY_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "CENTRAL_CONSUMER_RETRY_SECONDS must be a non-negative integer" >&2
  exit 2
fi
if [[ ! -r "$CONSUMER_POM" ]]; then
  echo "Missing clean-room consumer POM: $CONSUMER_POM" >&2
  exit 2
fi

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
SETTINGS_FILE="$WORK_DIR/settings.xml"
LOCAL_REPOSITORY="$WORK_DIR/repository"
LOG_FILE="$WORK_DIR/maven.log"

cat > "$SETTINGS_FILE" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <interactiveMode>false</interactiveMode>
</settings>
XML

run_clean_maven() {
  env \
    -u GITHUB_TOKEN \
    -u GH_TOKEN \
    -u GITHUB_ACTOR \
    -u CENTRAL_USERNAME \
    -u CENTRAL_PASSWORD \
    -u MAVEN_CENTRAL_USERNAME \
    -u MAVEN_CENTRAL_PASSWORD \
    -u MAVEN_GPG_KEY \
    -u MAVEN_GPG_PASSPHRASE \
    -u MAVEN_ARGS \
    "$MAVEN_COMMAND" \
      -B \
      -U \
      -s "$SETTINGS_FILE" \
      -Dmaven.repo.local="$LOCAL_REPOSITORY" \
      "$@"
}

resolve_once() {
  rm -rf "$LOCAL_REPOSITORY"
  : > "$LOG_FILE"

  set +e
  {
    run_clean_maven \
      org.apache.maven.plugins:maven-dependency-plugin:3.11.0:get \
      -Dartifact="io.github.carstenartur:jgit-storage-hibernate-parent:${VERSION}:pom" \
      -Dtransitive=false
    run_clean_maven \
      -f "$CONSUMER_POM" \
      -Dcentral.version="$VERSION" \
      dependency:go-offline
  } 2>&1 | tee "$LOG_FILE"
  status=${PIPESTATUS[0]}
  set -e

  if [[ $status -ne 0 ]]; then
    return "$status"
  fi

  if grep -Eiq 'maven\.pkg\.github\.com|github\.com/.*/packages' "$LOG_FILE"; then
    echo "Clean-room resolution unexpectedly contacted GitHub Packages" >&2
    return 1
  fi

  local group_path="$LOCAL_REPOSITORY/io/github/carstenartur"
  local artifact extension expected
  for artifact in \
    jgit-storage-hibernate-parent \
    jgit-storage-hibernate-core \
    jgit-storage-hibernate-search \
    jgit-storage-hibernate-java-analysis \
    jgit-storage-hibernate-architecture \
    jgit-storage-hibernate-benchmarks; do
    extension=jar
    if [[ "$artifact" == "jgit-storage-hibernate-parent" ]]; then
      extension=pom
    fi
    expected="$group_path/$artifact/$VERSION/$artifact-$VERSION.$extension"
    if [[ ! -f "$expected" ]]; then
      echo "Missing resolved Central artifact: $expected" >&2
      return 1
    fi
  done

  echo "Anonymous Maven Central consumption verified for $VERSION"
}

for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
  echo "Clean-room Central resolution attempt $attempt/$ATTEMPTS for $VERSION"
  if resolve_once; then
    exit 0
  fi
  if (( attempt < ATTEMPTS )); then
    sleep "$RETRY_SECONDS"
  fi
done

echo "Could not resolve all $VERSION artifacts anonymously from Maven Central" >&2
exit 1
