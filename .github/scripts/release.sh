#!/usr/bin/env bash
set -euo pipefail
: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
NEXT_VERSION_INPUT=${NEXT_VERSION_INPUT:-}
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
SOURCE_BRANCH=${SOURCE_BRANCH:-main}
REQUEST_BRANCH=${REQUEST_BRANCH:-}
TAG_NAME="v${RELEASE_VERSION}"
DOCUMENTED_RELEASE_VERSION_FILE=docs/current-release-version.txt
PUBLIC_REPOSITORY_BRANCH=${PUBLIC_REPOSITORY_BRANCH:-maven-repository}
PUBLIC_REPOSITORY_URL=${PUBLIC_REPOSITORY_URL:-https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/${PUBLIC_REPOSITORY_BRANCH}/}
PUBLIC_REPOSITORY_ATTEMPTS=${PUBLIC_REPOSITORY_ATTEMPTS:-12}
PUBLIC_REPOSITORY_RETRY_SECONDS=${PUBLIC_REPOSITORY_RETRY_SECONDS:-10}
PUBLIC_REPOSITORY_STAGE=${PUBLIC_REPOSITORY_STAGE:-$PWD/target/public-maven-repository}
PUBLIC_REPOSITORY_EVIDENCE=${PUBLIC_REPOSITORY_EVIDENCE:-$PWD/target/public-repository-evidence/manifest.json}
WORKTREE=
cleanup(){ if [[ -n "$WORKTREE" ]]; then git worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT
boolean(){ [[ "${!1}" == true || "${!1}" == false ]] || { echo "::error::$1 must be true or false"; exit 1; }; }
boolean SKIP_TESTS; boolean DRY_RUN
[[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "::error::release_version must use X.Y.Z"; exit 1; }
[[ "$SOURCE_BRANCH" == main || "$DRY_RUN" == true ]] || { echo "::error::Real releases must build main, not $SOURCE_BRANCH"; exit 1; }
[[ "$SKIP_TESTS" != true || "$DRY_RUN" == true ]] || { echo "::error::Real releases must run the complete test suite"; exit 1; }
CURRENT_VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
[[ "$CURRENT_VERSION" == *-SNAPSHOT ]] || { echo "::error::Current Maven version must be SNAPSHOT, was $CURRENT_VERSION"; exit 1; }
[[ "${CURRENT_VERSION%-SNAPSHOT}" == "$RELEASE_VERSION" ]] || { echo "::error title=Release version mismatch::Requested $RELEASE_VERSION from $CURRENT_VERSION"; exit 1; }
DOCUMENTED_RELEASE_VERSION=$(tr -d '[:space:]' < "$DOCUMENTED_RELEASE_VERSION_FILE")
[[ "$DOCUMENTED_RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "::error::Invalid documented release version"; exit 1; }
if [[ -n "$NEXT_VERSION_INPUT" ]]; then NEXT_VERSION=$NEXT_VERSION_INPUT; else IFS=. read -r a b c <<< "$RELEASE_VERSION"; NEXT_VERSION="$a.$b.$((c+1))-SNAPSHOT"; fi
[[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]] || { echo "::error::next_development_version must use X.Y.Z-SNAPSHOT"; exit 1; }

git config user.name 'github-actions[bot]'; git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
echo "Release $RELEASE_VERSION; next $NEXT_VERSION; repository $PUBLIC_REPOSITORY_URL; dry run $DRY_RUN"
python3 .github/scripts/verify-release-consistency.py
python3 .github/scripts/verify-public-repository-publishing.py
git fetch origin --tags --force
if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then echo "::error::Tag $TAG_NAME already exists"; exit 1; fi
# Automatic release preparation updates Maven coordinates and public documentation together.
mvn -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
python3 .github/scripts/update-release-metadata.py "$RELEASE_VERSION" --release
python3 .github/scripts/verify-release-consistency.py
python3 .github/scripts/verify-public-repository-publishing.py
git diff --check
if [[ "$SKIP_TESTS" == true ]]; then mvn -B -DskipTests verify; else docker info >/dev/null; mvn -B verify; fi
if grep -R "SNAPSHOT" --include='pom.xml' --exclude-dir=target --exclude-dir=.git .; then echo "::error::SNAPSHOT POM reference remains"; exit 1; fi

rm -rf "$PUBLIC_REPOSITORY_STAGE" "$(dirname "$PUBLIC_REPOSITORY_EVIDENCE")"
mkdir -p "$PUBLIC_REPOSITORY_STAGE"
mvn -B -Ppublic-repository-release -DskipTests -Dpublic.repository.directory="$PUBLIC_REPOSITORY_STAGE" deploy
python3 .github/scripts/prepare-public-repository.py "$RELEASE_VERSION" "$PUBLIC_REPOSITORY_STAGE" "$PUBLIC_REPOSITORY_EVIDENCE"
MAVEN_COMMAND=mvn PUBLIC_REPOSITORY_ATTEMPTS=1 .github/scripts/verify-public-repository-consumption.sh "$RELEASE_VERSION" "file://$PUBLIC_REPOSITORY_STAGE"
if [[ "$DRY_RUN" == true ]]; then echo "Dry run completed after local anonymous repository verification."; exit 0; fi

remove_volatile_version_files(){
  local root=$1
  [[ -d "$root/io/github/carstenartur" ]] || return 0
  find "$root/io/github/carstenartur" -type f -path "*/$RELEASE_VERSION/*" \
    \( -name '*.md5' -o -name '*.sha1' -o -name '*.lastUpdated' -o -name '_remote.repositories' \) \
    -delete
}

publish_repository(){
  local exists=false file rel dest
  git ls-remote --exit-code --heads origin "refs/heads/$PUBLIC_REPOSITORY_BRANCH" >/dev/null 2>&1 && exists=true
  WORKTREE=$(mktemp -d); rmdir "$WORKTREE"
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
  while IFS= read -r -d '' file; do
    rel=${file#"$PUBLIC_REPOSITORY_STAGE/"}
    case "$rel" in */"$RELEASE_VERSION"/*)
      dest="$WORKTREE/$rel"
      if [[ -e "$dest" ]] && ! cmp -s "$file" "$dest"; then echo "::error::Immutable release file differs: $rel"; exit 1; fi
    esac
  done < <(find "$PUBLIC_REPOSITORY_STAGE" -type f -print0)
  rsync -a "$PUBLIC_REPOSITORY_STAGE/" "$WORKTREE/"
  mkdir -p "$WORKTREE/releases"
  cp "$PUBLIC_REPOSITORY_EVIDENCE" "$WORKTREE/releases/$RELEASE_VERSION.json"
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
  if ! git -C "$WORKTREE" diff --cached --quiet; then git -C "$WORKTREE" commit -m "Publish Maven repository version $RELEASE_VERSION"; git -C "$WORKTREE" push origin "HEAD:refs/heads/$PUBLIC_REPOSITORY_BRANCH"; fi
}
publish_repository
PUBLIC_REPOSITORY_ATTEMPTS="$PUBLIC_REPOSITORY_ATTEMPTS" PUBLIC_REPOSITORY_RETRY_SECONDS="$PUBLIC_REPOSITORY_RETRY_SECONDS" \
  .github/scripts/verify-public-repository-consumption.sh "$RELEASE_VERSION" "$PUBLIC_REPOSITORY_URL"

git add pom.xml '*/pom.xml' CITATION.cff CITATION.md .zenodo.json codemeta.json README.md docs jgit-storage-hibernate-*/README.md
git commit -m "Release version $RELEASE_VERSION"
git tag -a "$TAG_NAME" -m "Release version $RELEASE_VERSION"
rm -rf target/release-artifacts; mkdir -p target/release-artifacts
find . -path './target/release-artifacts' -prune -o -path '*/target/*.jar' -type f ! -name 'original-*' -exec cp {} target/release-artifacts/ \;
cp CITATION.cff CITATION.md .zenodo.json codemeta.json "$PUBLIC_REPOSITORY_EVIDENCE" target/release-artifacts/
git push origin HEAD:main; git push origin "$TAG_NAME"
gh release create "$TAG_NAME" target/release-artifacts/* --title "jgit-storage-hibernate $RELEASE_VERSION" --verify-tag --fail-on-no-commits --generate-notes
mvn -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
python3 .github/scripts/update-release-metadata.py "$NEXT_VERSION"
python3 .github/scripts/verify-release-consistency.py
python3 .github/scripts/verify-public-repository-publishing.py
git add pom.xml '*/pom.xml' CITATION.cff CITATION.md .zenodo.json codemeta.json
git commit -m "Prepare next development version $NEXT_VERSION"
git push origin HEAD:main
if [[ -n "$REQUEST_BRANCH" ]]; then git push origin --delete "$REQUEST_BRANCH" || true; fi
