# Publication non-regression follow-up implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Resolve the uncertain independent-repository comparison in #161 with a preregistered, bounded native study.

**Architecture:** A manual test-only runner executes the existing JMH operation in paired fresh forks against a dedicated PostgreSQL/Toxiproxy fixture. A strict Python validator derives reproducible paired intervals and an observational conclusion. An explicit mode in the existing Performance Investigations workflow runs five isolated cells.

**Tech Stack:** Java 21, JMH, Testcontainers, PostgreSQL 17.10, Toxiproxy 2.12.0, Python standard library, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-07-publication-nonregression.md`

## Global constraints

- No production API, selector threshold or default changes.
- Independent cells: preregistered 24/96-pair cumulative looks; shared: fixed 24 pairs. Two 3-second warmups and three 5-second measurements per fresh JVM.
- Four independent boundary cells and one shared crossover; no pooling with historical trials.
- Independent throughput-regression margin -5%; predeclared lower bounds corrected for four cells and two looks. Other resource/latency metrics remain separate.
- Only hosted dedicated runners produce new timed evidence.
- Private connection files are temporary, outside the artifact tree, and never embedded in JMH arguments.

## Review focus

- Missing/duplicated/relabelled policy pairs must fail evidence validation.
- Partial JMH forks, non-finite scores and unexpected iteration/thread/parameter counts must fail validation.
- A genuine negative result must survive validation and remain visible as inconclusive or regressed.
- Explicitly durable database settings and actual bound filesystem must be captured, not inferred from container defaults.
- Exceptions must close containers and delete connection files; partial observations remain available for diagnosis without being promoted as complete.

### Task 1: Strict paired evidence and statistics

**Files:** Create `.github/scripts/summarize-publication-nonregression.py` and `.github/scripts/test_summarize_publication_nonregression.py`.

**Interfaces:** `summarize(root: Path) -> dict` consumes `protocol.json`, `trials.json`, `environment.json` and unmodified `raw/*.json`; produces `summary.json` and `pairs.csv`. `paired_interval(ratios: list[float], critical: float) -> dict` returns geometric change and lower/upper percent limits.

- [x] Write failing tests for a complete paired fixture, missing/duplicate pairs, wrong source/parameters, nonfinite scores and a valid unfavorable conclusion.
- [x] Run `python3 .github/scripts/test_summarize_publication_nonregression.py`; expect missing-module failure.
- [x] Implement validation and fixed-protocol statistics, with no third-party numerical dependency.
- [x] Run the same focused test file; expect all tests passing. Commit the tested parser and tests.

### Task 2: Native paired runner and explicit manual workflow mode

**Files:** Create `jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/PackPublicationPairedBenchmarkIT.java`; modify `.github/workflows/performance-investigations.yml` and its workflow contract tests.

**Interfaces:** Manual runner reads `jgit.storage.benchmark.publication.cell` (five fixed cell IDs), `benchmark.resultDirectory`, and `jgit.storage.benchmark.source`; emits Task 1's protocol/trial/environment records and 48 or 192 complete raw forks under the fixed stopping rule.

- [ ] Add a focused workflow contract that selecting the manual paired mode excludes unrelated long matrices and uploads only the designated evidence tree.
- [ ] Observe the new contract fail before workflow changes.
- [ ] Implement the bounded runner, private connection lifecycle, exact durable settings and independent fork manifests; extend the registered workflow with the fixed five-cell matrix.
- [ ] Compile the benchmark reactor and run focused tooling/workflow tests. Validate YAML and run normal Python tooling gates; no local timing study.
- [ ] Commit and request an independent review before publishing the source branch.

### Task 3: Native study, retained evidence and acceptance decision

**Files:** Add a dated evidence directory and update `docs/performance-status.md` plus the adaptive-publication guide as warranted by observed results.

- [ ] Publish the reviewed source on a dedicated branch and dispatch the fixed manual profile once.
- [ ] Require all five complete native artifacts; verify archive hashes, source, environment and every raw fork, including both cumulative looks where performed.
- [ ] Reproduce the summaries and charts from unchanged raw data. State negative/inconclusive results honestly.
- [ ] Review the source/evidence pairing independently, run the combined CI required by branch rules and merge the verified result.
- [ ] Update #161 according to the original acceptance criteria; retain documented unqualified deployment limits.
