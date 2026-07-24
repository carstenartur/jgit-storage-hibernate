#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"

NEXT_VERSION_INPUT=${NEXT_VERSION_INPUT:-}
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
SOURCE_BRANCH=${SOURCE_BRANCH:-main}
PUBLISH_GITHUB_PACKAGES=${PUBLISH_GITHUB_PACKAGES:-true}
TAG_NAME="v${RELEASE_VERSION}"
DOCUMENTED_RELEASE_VERSION_FILE=docs/current-release-version.txt
CENTRAL_CONSUMER_ATTEMPTS=${CENTRAL_CONSUMER_ATTEMPTS:-12}
CENTRAL_CONSUMER_RETRY_SECONDS=${CENTRAL_CONSUMER_RETRY_SECONDS:-15}
TEMP_DIRS=()

cleanup() {
  local directory
  for directory in "${TEMP_DIRS[@]:-}"; do
    [[ -n "$directory" ]] && rm -rf "$directory"
  done
}
trap cleanup EXIT

require_boolean() {
  local name=$1
  local value=${!name}
  if [[ "$value" != "true" && "$value" != "false" ]]; then
    echo "::error::$name must be true or false, but was '$value'"
    exit 1
  fi
}

require_env() {
  local name=$1
  if [[ -z "${!name:-}" ]]; then
    echo "::error::Required release credential $name is not configured"
    exit 1
  fi
}

prepare_ephemeral_signing_key() {
  if [[ -n "${MAVEN_GPG_KEY:-}" ]]; then
    return
  fi
  if ! command -v gpg >/dev/null 2>&1; then
    echo "::error::gpg is required to generate the ephemeral Central dry-run signing key"
    exit 1
  fi

  local key_home fingerprint
  key_home=$(mktemp -d)
  TEMP_DIRS+=("$key_home")
  chmod 700 "$key_home"

  gpg \
    --batch \
    --homedir "$key_home" \
    --pinentry-mode loopback \
    --passphrase '' \
    --quick-generate-key \
    'jgit-storage-hibernate Central dry run <central-dry-run@example.invalid>' \
    rsa2048 \
    sign \
    1d

  fingerprint=$(gpg \
    --batch \
    --homedir "$key_home" \
    --with-colons \
    --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')
  if [[ -z "$fingerprint" ]]; then
    echo "::error::Could not identify the ephemeral Central dry-run signing key"
    exit 1
  fi

  MAVEN_GPG_KEY=$(gpg \
    --batch \
    --homedir "$key_home" \
    --armor \
    --export-secret-keys "$fingerprint")
  MAVEN_GPG_PASSPHRASE=''
  export MAVEN_GPG_KEY MAVEN_GPG_PASSPHRASE
  echo "Generated an ephemeral signing key for Central bundle validation only."
}

publish_github_packages() {
  local settings_dir settings_file
  settings_dir=$(mktemp -d)
  TEMP_DIRS+=("$settings_dir")
  settings_file="$settings_dir/settings.xml"

  cat > "$settings_file" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <interactiveMode>false</interactiveMode>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
XML

  mvn -B -s "$settings_file" -Pgithub-packages -DskipTests deploy
}

require_boolean SKIP_TESTS
require_boolean DRY_RUN
require_boolean PUBLISH_GITHUB_PACKAGES

if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::release_version must use X.Y.Z without a leading v"
  exit 1
fi

if [[ "$SOURCE_BRANCH" != "main" && "$DRY_RUN" != "true" ]]; then
  echo "::error::Real releases must be dispatched from main, not $SOURCE_BRANCH"
  exit 1
fi

if [[ "$SKIP_TESTS" == "true" && "$DRY_RUN" != "true" ]]; then
  echo "::error::Real releases must run the complete test suite"
  exit 1
fi

CURRENT_VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$CURRENT_VERSION" != *-SNAPSHOT ]]; then
  echo "::error::Current Maven version must be a SNAPSHOT, but was $CURRENT_VERSION"
  exit 1
fi
if [[ "${CURRENT_VERSION%-SNAPSHOT}" != "$RELEASE_VERSION" ]]; then
  echo "::error title=Release version mismatch::Requested release $RELEASE_VERSION cannot be built from current Maven version $CURRENT_VERSION on source branch $SOURCE_BRANCH. Dispatch release ${CURRENT_VERSION%-SNAPSHOT}, or first move the source branch to $RELEASE_VERSION-SNAPSHOT."
  exit 1
fi

if [[ ! -r "$DOCUMENTED_RELEASE_VERSION_FILE" ]]; then
  echo "::error::Missing or unreadable documented release version file: $DOCUMENTED_RELEASE_VERSION_FILE"
  exit 1
fi
DOCUMENTED_RELEASE_VERSION=$(tr -d '[:space:]' < "$DOCUMENTED_RELEASE_VERSION_FILE")
if ! [[ "$DOCUMENTED_RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error title=Invalid documented release version::$DOCUMENTED_RELEASE_VERSION_FILE must contain the last published X.Y.Z version, but contained '$DOCUMENTED_RELEASE_VERSION'."
  exit 1
fi

if [[ -n "$NEXT_VERSION_INPUT" ]]; then
  NEXT_VERSION=$NEXT_VERSION_INPUT
else
  IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
  NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"
fi
if ! [[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  echo "::error::next_development_version must use X.Y.Z-SNAPSHOT"
  exit 1
fi

if [[ "$DRY_RUN" != "true" ]]; then
  require_env CENTRAL_USERNAME
  require_env CENTRAL_PASSWORD
  require_env MAVEN_GPG_KEY
  require_env MAVEN_GPG_PASSPHRASE
  require_env GH_TOKEN
  if [[ "$PUBLISH_GITHUB_PACKAGES" == "true" ]]; then
    require_env GITHUB_ACTOR
    require_env GITHUB_TOKEN
  fi
fi

git config user.name 'github-actions[bot]'
git config user.email 'github-actions[bot]@users.noreply.github.com'

echo "Release version: $RELEASE_VERSION"
echo "Current version: $CURRENT_VERSION"
echo "Currently documented published release: $DOCUMENTED_RELEASE_VERSION"
echo "Next development version: $NEXT_VERSION"
echo "Dry run: $DRY_RUN"
echo "Skip tests: $SKIP_TESTS"
echo "Publish secondary GitHub Packages copy: $PUBLISH_GITHUB_PACKAGES"

if [[ "$DOCUMENTED_RELEASE_VERSION" == "$RELEASE_VERSION" ]]; then
  echo "Public documentation already targets release $RELEASE_VERSION."
else
  echo "::notice title=Automatic release preparation::Public documentation currently targets published release $DOCUMENTED_RELEASE_VERSION and will be advanced automatically to $RELEASE_VERSION in the release commit."
fi

# Verify that the development checkout is internally consistent before any release mutation.
# At this point public documentation may intentionally still describe the previous release.
python3 .github/scripts/verify-release-consistency.py
python3 .github/scripts/verify-central-publishing.py

git fetch origin --tags --force
if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
  echo "::error::Tag $TAG_NAME already exists"
  exit 1
fi

# Prepare all release state in one operation: Maven versions, software metadata, the
# documented release line, and active public dependency examples.
mvn -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
python3 .github/scripts/update-release-metadata.py "$RELEASE_VERSION" --release
python3 .github/scripts/verify-release-consistency.py
python3 .github/scripts/verify-central-publishing.py

git diff --check

if [[ "$SKIP_TESTS" == "true" ]]; then
  mvn -B -DskipTests verify
else
  if ! docker info >/dev/null 2>&1; then
    echo "::error::Docker is required for the Testcontainers-backed PostgreSQL release tests"
    exit 1
  fi
  mvn -B verify
fi

if grep -R "SNAPSHOT" --include="pom.xml" --exclude-dir=target --exclude-dir=.git .; then
  echo "::error::SNAPSHOT references still found in pom.xml files after release version update"
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  prepare_ephemeral_signing_key
  mvn -B \
    -Pcentral-release \
    -DskipTests \
    -Dcentral.skipPublishing=true \
    deploy
  python3 .github/scripts/verify-central-bundle.py "$RELEASE_VERSION"
  echo "Dry run completed after signed Central bundle validation and before upload/tag/release."
  exit 0
fi

# Maven Central is the primary immutable public distribution channel. The Central
# plugin validates, publishes and waits for the published state before this script
# performs any secondary package publication or creates the GitHub release.
mvn -B -Pcentral-release -DskipTests deploy
python3 .github/scripts/verify-central-bundle.py "$RELEASE_VERSION"

CENTRAL_CONSUMER_ATTEMPTS="$CENTRAL_CONSUMER_ATTEMPTS" \
CENTRAL_CONSUMER_RETRY_SECONDS="$CENTRAL_CONSUMER_RETRY_SECONDS" \
  .github/scripts/verify-central-consumption.sh "$RELEASE_VERSION"

if [[ "$PUBLISH_GITHUB_PACKAGES" == "true" ]]; then
  publish_github_packages
else
  echo "Skipping optional GitHub Packages publication."
fi

git add \
  pom.xml \
  '*/pom.xml' \
  CITATION.cff CITATION.md .zenodo.json codemeta.json \
  README.md docs jgit-storage-hibernate-*/README.md
git commit -m "Release version $RELEASE_VERSION"
git tag -a "$TAG_NAME" -m "Release version $RELEASE_VERSION"

rm -rf target/release-artifacts
mkdir -p target/release-artifacts
find . -path './target/release-artifacts' -prune -o \
  -path '*/target/*.jar' -type f \
  ! -name 'original-*' \
  -exec cp {} target/release-artifacts/ \;
cp CITATION.cff CITATION.md .zenodo.json codemeta.json target/release-artifacts/

git push origin HEAD:main
git push origin "$TAG_NAME"

gh release create "$TAG_NAME" target/release-artifacts/* \
  --title "jgit-storage-hibernate $RELEASE_VERSION" \
  --verify-tag \
  --fail-on-no-commits \
  --generate-notes

# Advance development metadata only. Public dependency examples continue to point to the
# immutable release that was just published until the next release run advances them.
mvn -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
python3 .github/scripts/update-release-metadata.py "$NEXT_VERSION"
python3 .github/scripts/verify-release-consistency.py
python3 .github/scripts/verify-central-publishing.py
git add pom.xml '*/pom.xml' CITATION.cff CITATION.md .zenodo.json codemeta.json
git commit -m "Prepare next development version $NEXT_VERSION"
git push origin HEAD:main
