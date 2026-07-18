#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"

NEXT_VERSION_INPUT=${NEXT_VERSION_INPUT:-}
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
SOURCE_BRANCH=${SOURCE_BRANCH:-main}
TAG_NAME="v${RELEASE_VERSION}"

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
  echo "::error::Release $RELEASE_VERSION does not match current version $CURRENT_VERSION"
  exit 1
fi

DOCUMENTED_RELEASE_VERSION=$(tr -d '[:space:]' < docs/current-release-version.txt)
if [[ "$DOCUMENTED_RELEASE_VERSION" != "$RELEASE_VERSION" ]]; then
  echo "::error::Documented release $DOCUMENTED_RELEASE_VERSION does not match requested release $RELEASE_VERSION"
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

git config user.name 'github-actions[bot]'
git config user.email 'github-actions[bot]@users.noreply.github.com'

echo "Release version: $RELEASE_VERSION"
echo "Current version: $CURRENT_VERSION"
echo "Documented release version: $DOCUMENTED_RELEASE_VERSION"
echo "Next development version: $NEXT_VERSION"
echo "Dry run: $DRY_RUN"
echo "Skip tests: $SKIP_TESTS"

python3 .github/scripts/verify-release-consistency.py

git fetch origin --tags --force
if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
  echo "::error::Tag $TAG_NAME already exists"
  exit 1
fi

mvn -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
python3 .github/scripts/update-release-metadata.py "$RELEASE_VERSION" --release
python3 .github/scripts/verify-release-consistency.py

if [[ "$SKIP_TESTS" == "true" ]]; then
  mvn -B -DskipTests verify
else
  docker info >/dev/null
  mvn -B verify
fi

if grep -R "SNAPSHOT" --include="pom.xml" --exclude-dir=target --exclude-dir=.git .; then
  echo "::error::SNAPSHOT references still found in pom.xml files after release version update"
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "Dry run completed before deploy/tag/release."
  exit 0
fi

mvn -B -DskipTests deploy

git add pom.xml '*/pom.xml' CITATION.cff CITATION.md .zenodo.json codemeta.json
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

mvn -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
python3 .github/scripts/update-release-metadata.py "$NEXT_VERSION"
python3 .github/scripts/verify-release-consistency.py
git add pom.xml '*/pom.xml' CITATION.cff CITATION.md .zenodo.json codemeta.json
git commit -m "Prepare next development version $NEXT_VERSION"
git push origin HEAD:main
