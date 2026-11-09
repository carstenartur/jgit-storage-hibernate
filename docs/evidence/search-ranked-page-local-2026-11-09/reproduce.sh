#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
workspace=$(realpath -m "${1:?Pass an empty workspace path}")
java21=$(realpath "${JAVA21_HOME:?Set JAVA21_HOME to Temurin 21.0.13+9}")
java25=$(realpath "${JAVA25_HOME:?Set JAVA25_HOME to JDK 25.0.5+8}")
maven=$(command -v mvn)
consumer_commit=2eeb52bff134133cd02aea361a6ec99428ac1d33
search_commit=2e6751194ff514e4d3e6fc31b672acc68ae96997
if [[ -e "$workspace" && -n "$(ls -A "$workspace")" ]]; then
  echo 'Use an empty workspace to avoid mixing retained and newly measured data.' >&2
  exit 2
fi
"$java21/bin/java" -version 2>&1 | grep -F '21.0.13+9'
"$java25/bin/java" -version 2>&1 | grep -F '25.0.5+8'
JAVA_HOME="$java25" "$maven" -version | grep -F 'Apache Maven 3.9.16'
mkdir -p "$workspace/build-support" "$workspace/study"
ln -s "$java21" "$workspace/build-support/jdk-21"
cd "$workspace/study"
checkout() {
  local name=$1 repo=$2 commit=$3 tree=$4
  git init -q "$name"
  git -C "$name" remote add origin "$repo"
  git -C "$name" fetch --depth 1 origin "$commit"
  git -C "$name" checkout --quiet --detach FETCH_HEAD
  [[ $(git -C "$name" rev-parse HEAD) == "$commit" ]]
  [[ $(git -C "$name" rev-parse 'HEAD^{tree}') == "$tree" ]]
}
checkout consumer https://github.com/carstenartur/jgit-storage-hibernate.git "$consumer_commit" 172b54b6f5169940d3ee4b429c3ffb609d1268d3
checkout search-clean https://github.com/carstenartur/hibernate-search.git "$search_commit" 6a33f97fad814f6895910d338a5ad0785e6be4e3
cp -R "$here/harness" experiment
(cd search-clean; JAVA_HOME="$java25" "$maven" -B -ntp -Pskip-checks -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -pl mapper/orm,backend/lucene -am package)
probe="$PWD/consumer/reproducers/hibernate-search-ranked-scroll"
(cd "$probe"; JAVA_HOME="$java21" "$maven" -B -ntp -Dhibernate-search.version=8.4.1.Final compile dependency:build-classpath -Dmdep.outputFile=target/release-classpath.txt)
release=$(cat "$probe/target/release-classpath.txt")
for variant in threshold million; do
  mkdir "experiment/$variant/classes"
  "$java21/bin/javac" --release 21 -proc:none -cp "$release" -d "experiment/$variant/classes" \
    "experiment/$variant/src/RankedScrollMemoryProbe.java"
done
experiment/run.sh 100000 both
experiment/run-threshold.sh 100000
experiment/run-million.sh
for size in 100000 1000000; do
  python3 experiment/summarize.py "experiment/results/$size" > "experiment/results/$size/summary.csv"
done
