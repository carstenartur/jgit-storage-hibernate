#!/usr/bin/env bash
set -euo pipefail
work=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
base=$(dirname "$work")
root=$(dirname "$base")
jdk="$root/build-support/jdk-21"
probe="$base/consumer/reproducers/hibernate-search-ranked-scroll"
threshold="$work/threshold"
documents=${1:-100000}
output="$work/results/$documents"
for variant in 512 4096 16384; do
  origin="$output/timing-$variant"
  test -f "$origin/verified-export-timings.csv"
  folder="$output/threshold1-$variant"
  mkdir "$folder"
  cp=$(cat "$origin/classpath.txt")
  case "$cp" in "$work/probe-classes:"*) ;; *) echo 'Unexpected baseline classpath' >&2; exit 1;; esac
  cp="$threshold/classes:${cp#*:}"
  echo "$cp" > "$folder/classpath.txt"
  sha256sum "$threshold/src/RankedScrollMemoryProbe.java" > "$folder/probe-sha256.txt"
  opts=()
  if [[ "$variant" != 512 ]]; then
    opts+=("-Dranked.scroll.experimental-page-limit=$variant")
  fi
  echo "$documents threshold1 $variant"
  "$jdk/bin/java" -Xms256m -Xmx512m -XX:ActiveProcessorCount=2 \
    "-Xlog:class+load=info:file=$folder/class-loads.log" \
    -Dorg.jboss.logging.provider=jdk \
    "-Djava.util.logging.config.file=$probe/logging.properties" \
    -Dranked.scroll.experimental-timing=true "${opts[@]}" \
    -cp "$cp" io.github.carstenartur.reproducer.RankedScrollMemoryProbe \
    "$documents" 128 "$folder" > "$folder/console.txt" 2>&1
  expected_origin=$(sed -n 's/^.* source: //p' "$origin/scroll-class-origin.txt")
  rg 'org.hibernate.search.backend.lucene.search.query.impl.LuceneSearchScrollImpl source:' "$folder/class-loads.log" \
    | tee "$folder/scroll-class-origin.txt" | rg -F "$expected_origin"
  for artifact in backend-lucene engine mapper-orm mapper-pojo-base util-common; do
    rg -m 1 -F "hibernate-search-$artifact-8.4.2-SNAPSHOT.jar" "$folder/class-loads.log"
  done > "$folder/runtime-search-origins.txt"
  cat "$folder/verified-export-timings.csv"
done
