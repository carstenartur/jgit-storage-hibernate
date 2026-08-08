#!/usr/bin/env bash
# Run one consumer's repository-owned jgit-storage-hibernate contract or its bounded fallback.
set -euo pipefail

usage() {
  echo "usage: run-consumer-contract.sh <audio-analyzer|Taxonomy|sandbox> <checkout> <log-file>" >&2
  exit 64
}

[[ $# -eq 3 ]] || usage
consumer=$1
checkout=$2
log_file=$3

[[ -d "$checkout" ]] || { echo "Missing consumer checkout: $checkout" >&2; exit 66; }
checkout=$(cd "$checkout" && pwd)
mkdir -p "$(dirname "$log_file")"
log_file=$(cd "$(dirname "$log_file")" && pwd)/$(basename "$log_file")

maven_repository=${MAVEN_REPO_LOCAL:-}
maven_repository_argument=()
if [[ -n "$maven_repository" ]]; then
  mkdir -p "$maven_repository"
  maven_repository_argument=("-Dmaven.repo.local=$maven_repository")
fi

export JGIT_STORAGE_HIBERNATE_CONSUMER=${consumer}
export JGIT_STORAGE_HIBERNATE_CONTRACT_MODE=${JGIT_STORAGE_HIBERNATE_CONTRACT_MODE:-candidate}
export JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION=${JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION:-}

if [[ -n "$maven_repository" ]]; then
  # Repository-owned contracts receive the same isolated repository even when they invoke
  # Maven themselves. The explicit fallback commands below pass it again as a normal option.
  export MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }-Dmaven.repo.local=$maven_repository"
fi

if [[ -x "$checkout/.github/jgit-storage-hibernate-contract.sh" ]]; then
  echo "Running repository-owned contract for $consumer"
  (
    cd "$checkout"
    set -o pipefail
    .github/jgit-storage-hibernate-contract.sh 2>&1 | tee "$log_file"
  )
  exit 0
fi

echo "No repository-owned contract for $consumer; running the documented central fallback."
(
  cd "$checkout"
  if [[ -x ./mvnw ]]; then
    maven=(./mvnw)
  else
    maven=(mvn)
  fi

  case "$consumer" in
    audio-analyzer)
      command=(
        "${maven[@]}" -B -ntp -nsu
        "${maven_repository_argument[@]}"
        -pl audio-app -am
        -DskipITs=true
        verify
      )
      ;;
    Taxonomy)
      docker info >/dev/null
      export GEMINI_API_KEY=
      export OPENAI_API_KEY=
      export ANTHROPIC_API_KEY=
      export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
      command=(
        "${maven[@]}" -B -ntp -nsu
        "${maven_repository_argument[@]}"
        -pl taxonomy-app -am
        -DskipITs=false
        -DexcludedGroups=real-llm,onnx,db-mssql,db-oracle
        -Dtaxonomy.model.download.skip=true
        -Dtaxonomy.ui.skip=true
        -Dtaxonomy.quality.skip=true
        -Dtest=JgitStorageHibernateIntegrationTest,JgitStorageOptimizedIndexContractTest,JgitStorageSchemaIndexValidationTest,JgitStorageSchemaMigrationConfigTest,CommitIndexHibernateSearchTest
        -Dit.test=JgitStoragePostgresMigrationIT,TaxonomyPostgresValidateStartupIT,TaxonomySchemaPostgresMigrationIT
        -Dsurefire.failIfNoSpecifiedTests=false
        -Dfailsafe.failIfNoSpecifiedTests=false
        verify
      )
      ;;
    sandbox)
      # Sandbox currently consumes upstream Core only through these two modules. Do not
      # turn the complete Eclipse cleanup reactor or copied Search/Java-analysis code into
      # an accidental library contract.
      set -o pipefail
      "${maven[@]}" -B -ntp -nsu \
        "${maven_repository_argument[@]}" \
        -N -f pom.xml install
      "${maven[@]}" -B -ntp -nsu \
        "${maven_repository_argument[@]}" \
        -f sandbox-jgit-storage-hibernate/pom.xml install
      "${maven[@]}" -B -ntp -nsu \
        "${maven_repository_argument[@]}" \
        -f sandbox-jgit-server-webapp/pom.xml package
      test -s sandbox-jgit-storage-hibernate/target/classes/META-INF/MANIFEST.MF
      test -s sandbox-jgit-server-webapp/target/jgit-server.jar
      exit 0
      ;;
    *)
      usage
      ;;
  esac

  set -o pipefail
  "${command[@]}" 2>&1 | tee "$log_file"
)
