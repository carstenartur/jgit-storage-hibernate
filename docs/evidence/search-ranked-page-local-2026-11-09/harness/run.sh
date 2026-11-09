#!/usr/bin/env bash
set -euo pipefail
work=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
base=$(dirname "$work")
root=$(dirname "$base")
probe="$base/consumer/reproducers/hibernate-search-ranked-scroll"
search="$base/search-clean"
jdk="$root/build-support/jdk-21"
documents=${1:-100000}
mode=${2:-both}
output="$work/results/$documents"
mkdir -p "$output"
case "$mode" in both|memory|timing) ;; *) echo 'Use both, memory or timing' >&2; exit 2;; esac
release_cp=$(cat "$probe/target/release-classpath.txt")
source_jars=()
for module in engine backend/lucene mapper/orm mapper/pojo-base util/common; do
  source_jars+=("$(find "$search/$module/target" -maxdepth 1 -name 'hibernate-search-*-8.4.2-SNAPSHOT.jar' -print)")
done
candidate_cp=$(
  python3 - "$release_cp" "${source_jars[@]}" <<'PY'
from pathlib import Path
import sys
release = sys.argv[1].split(':')
replacements = {Path(value).name.removesuffix('-8.4.2-SNAPSHOT.jar'): value for value in sys.argv[2:]}
assert len(replacements) == 5 and all(Path(value).is_file() for value in replacements.values()), replacements
resolved = [replacements.pop(Path(value).name.removesuffix('-8.4.1.Final.jar'), value) for value in release]
assert not replacements, replacements
print(':'.join(resolved))
PY
)
mkdir -p "$work/overlay-classes" "$work/probe-classes"
"$jdk/bin/javac" --release 17 -proc:none -cp "$candidate_cp" -d "$work/overlay-classes" "$work/src/LuceneSearchScrollImpl.java"
"$jdk/bin/jar" --create --file "$work/cap-overlay.jar" -C "$work/overlay-classes" .
"$jdk/bin/javac" --release 21 -proc:none -cp "$release_cp" -d "$work/probe-classes" "$work/src/RankedScrollMemoryProbe.java"
{
  "$jdk/bin/java" -version
  git -C "$base/consumer" rev-parse HEAD 'HEAD^{tree}'
  git -C "$search" rev-parse HEAD 'HEAD^{tree}'
  git -C "$search" diff --exit-code HEAD
  git -C "$base/consumer" diff --exit-code HEAD
  uname -sm
  lscpu | sed -n '1,18p'
  sha256sum "$work/cap-overlay.jar" "$work/src/"*.java "${source_jars[@]}"
  IFS=: read -r -a release_jars <<< "$release_cp"
  sha256sum "${release_jars[@]}"
} > "$output/provenance.txt" 2>&1

run() {
  local kind=$1 variant=$2 cp=$3 expected_origin=$4
  local folder="$output/$kind-$variant"
  mkdir "$folder"
  local opts=() budget=()
  if [[ "$kind" == timing ]]; then
    opts+=(-Dranked.scroll.experimental-timing=true)
  elif [[ "$variant" != release ]]; then
    budget+=("$((variant + 1))")
  fi
  if [[ "$variant" != release && "$variant" != 512 ]]; then
    opts+=("-Dranked.scroll.experimental-page-limit=$variant")
  fi
  printf '%s %s %s\n' "$documents" "$kind" "$variant"
  printf '%s\n' "$cp" > "$folder/classpath.txt"
  "$jdk/bin/java" -Xms256m -Xmx512m -XX:ActiveProcessorCount=2 \
    "-Xlog:class+load=info:file=$folder/class-loads.log" \
    -Dorg.jboss.logging.provider=jdk \
    "-Djava.util.logging.config.file=$probe/logging.properties" \
    "${opts[@]}" -cp "$cp" io.github.carstenartur.reproducer.RankedScrollMemoryProbe \
    "$documents" 128 "$folder" "${budget[@]}" > "$folder/console.txt" 2>&1
  rg 'org.hibernate.search.backend.lucene.search.query.impl.LuceneSearchScrollImpl source:' "$folder/class-loads.log" \
    | tee "$folder/scroll-class-origin.txt" | rg -F "$expected_origin"
  local search_version=8.4.2-SNAPSHOT
  if [[ "$variant" == release ]]; then search_version=8.4.1.Final; fi
  for artifact in backend-lucene engine mapper-orm mapper-pojo-base util-common; do
    rg -m 1 -F "hibernate-search-$artifact-$search_version.jar" "$folder/class-loads.log"
  done > "$folder/runtime-search-origins.txt"
  if [[ "$kind" == timing ]]; then
    cat "$folder/verified-export-timings.csv"
  else
    awk -F, 'NR>1 && $3>max {max=$3} END {print "maximum sampled live ranked hits:", max}' "$folder/retained-hits.csv"
  fi
}
for kind in timing memory; do
  if [[ "$mode" != both && "$mode" != "$kind" ]]; then continue; fi
  classes="$probe/target/classes"
  if [[ "$kind" == timing ]]; then classes="$work/probe-classes"; fi
  run "$kind" release "$classes:$release_cp" hibernate-search-backend-lucene-8.4.1.Final.jar
  run "$kind" 512 "$classes:$candidate_cp" hibernate-search-backend-lucene-8.4.2-SNAPSHOT.jar
  run "$kind" 4096 "$classes:$work/cap-overlay.jar:$candidate_cp" cap-overlay.jar
  run "$kind" 16384 "$classes:$work/cap-overlay.jar:$candidate_cp" cap-overlay.jar
done
