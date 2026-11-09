#!/usr/bin/env bash
set -euo pipefail
work=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
base=$(dirname "$work")
root=$(dirname "$base")
jdk="$root/build-support/jdk-21"
probe="$base/consumer/reproducers/hibernate-search-ranked-scroll"
output="$work/results/1000000"
prior="$work/results/100000"
mkdir "$output"
cp "$work/million-protocol.md" "$output/protocol.md"
cp "$prior/provenance.txt" "$output/provenance.txt"
sha256sum "$work/million/src/RankedScrollMemoryProbe.java" "$output/protocol.md" >> "$output/provenance.txt"
rg '^[a-f0-9]{64}  ' "$output/provenance.txt" | sha256sum --check --quiet
for variant in release 16384; do
  original="$prior/timing-$variant"
  folder="$output/$variant"
  mkdir "$folder"
  cp=$(cat "$original/classpath.txt")
  case "$cp" in "$work/probe-classes:"*) ;; *) echo 'Unexpected baseline classpath' >&2; exit 1;; esac
  cp="$work/million/classes:${cp#*:}"
  echo "$cp" > "$folder/classpath.txt"
  opts=() budget=()
  search_version=8.4.1.Final
  if [[ "$variant" == 16384 ]]; then
    opts+=(-Dranked.scroll.experimental-page-limit=16384)
    budget+=(16385)
    search_version=8.4.2-SNAPSHOT
  fi
  echo "1000000 exact threshold $variant"
  "$jdk/bin/java" -Xms256m -Xmx512m -XX:ActiveProcessorCount=2 \
    "-Xlog:class+load=info:file=$folder/class-loads.log" \
    -Dorg.jboss.logging.provider=jdk \
    "-Djava.util.logging.config.file=$probe/logging.properties" \
    "${opts[@]}" -cp "$cp" io.github.carstenartur.reproducer.RankedScrollMemoryProbe \
    1000000 128 "$folder" "${budget[@]}" > "$folder/console.txt" 2>&1
  expected_origin=$(sed -n 's/^.* source: //p' "$original/scroll-class-origin.txt")
  rg 'org.hibernate.search.backend.lucene.search.query.impl.LuceneSearchScrollImpl source:' "$folder/class-loads.log" \
    | tee "$folder/scroll-class-origin.txt" | rg -F "$expected_origin"
  for artifact in backend-lucene engine mapper-orm mapper-pojo-base util-common; do
    rg -m 1 -F "hibernate-search-$artifact-$search_version.jar" "$folder/class-loads.log"
  done > "$folder/runtime-search-origins.txt"
  cat "$folder/verified-export-timings.csv"
  awk -F, 'NR>1 && $3>max {max=$3} END {print "maximum sampled live ranked hits:", max}' "$folder/retained-hits.csv"
done
rg '^[a-f0-9]{64}  ' "$output/provenance.txt" | sha256sum --check --quiet
