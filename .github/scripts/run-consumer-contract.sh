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
mkdir -p "$(dirname "$log_file")"

if [[ -x "$checkout/.github/jgit-storage-hibernate-contract.sh" ]]; then
  echo "Running repository-owned contract for $consumer"
  (
    cd "$checkout"
    set -o pipefail
    .github/jgit-storage-hibernate-contract.sh 2>&1 | tee "$OLDPWD/$log_file"
  )
  exit 0
fi

echo "No repository-owned contract for $consumer; running the documented central fallback."
if [[ -x "$checkout/mvnw" ]]; then
  maven=("$checkout/mvnw")
else
  maven=(mvn)
fi

case "$consumer" in
  audio-analyzer)
    command=("${maven[@]}" -B -DskipITs verify)
    ;;
  Taxonomy)
    docker info >/dev/null
    command=("${maven[@]}" -B verify)
    ;;
  sandbox)
    command=(xvfb-run -a "${maven[@]}" -B -DskipTests verify)
    ;;
  *)
    usage
    ;;
esac

(
  cd "$checkout"
  set -o pipefail
  "${command[@]}" 2>&1 | tee "$OLDPWD/$log_file"
)
