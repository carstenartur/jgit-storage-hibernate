# SDD ledger — plan: docs/superpowers/plans/2026-11-07-publication-nonregression.md

Base: local 495653b9939c8b9cc7280d73cfedbb4ac2b8bb3f, byte-identical tree to merged main 1c33640bc75206e81a9bc5722768860a2a2321f5 (974c3c0589f0067f0135dbd65e3e6f205591902b).

Pre-flight: Task 2 emits Task 1's protocol.json/trials.json/environment.json and raw files; Task 3 only consumes validated complete artifacts. No other shared interfaces.

Ruling: Execute inline with independent whole-branch review — all other agents own independent issue follow-ups, and the user already authorized working the issues; no additional approval is needed for this bounded test/evidence work. Native timing runs are hosted to avoid contamination from parallel local builds.

Ruling: Before any new measurements, independent review found that 24 pairs alone have weak planning precision at the old study's variance. Adopt a fully preregistered 24/96-pair design, alpha 0.05/(4*2) per independent cell/look, stopping only at a first-look pass or the fixed final maximum. This preserves family-wise error control and bounds the work; an inconclusive final result remains possible. Shared retains a fixed 24-pair 95% interval. No production or universal matrix guarantee follows from these boundary cells.

Review: Scope wording now explicitly excludes original larger-payload/internal-pool/full-matrix completion, removes an unproven worst-case claim and separates throughput inference from latency/allocation observations.

Task 1: complete — parser observed RED for missing implementation, unvalidated latency omission and the revised stopping contract; 16 focused Python tests now pass. Original unfavorable complete results are retained. Task 2: runner and manual workflow implemented. Java stopping-rule test observed missing-helper RED, then all4 tests GREEN; native runner compiles on the benchmark reactor. Workflow isolation tests observed RED and now7 pass. Parser final-calibration regression observed RED; all17 parser tests pass. Complete Python discovery is green with319 tests. No local timing run. Independent whole-branch review pending.

Ruling: Reuse the existing validate_search_database_telemetry.py --privacy-only scanner instead of introducing another privacy scanner; the uploaded root contains only protocol/environment/trials and native raw results, not private properties or broad target directories.

Whole-branch independent review at 24c7ab8 found three bounded evidence-validation gaps: raw-output substitution/orphans, missing CPU/checkpoint provenance and unchecked fork JVM/JMH/heap identity. All were reproduced (47 failing subcases across 26 tests) before fixing. The parser now requires matching original logs and the complete raw file set, typed CPU/runtime/checkpoint metadata including nullable stats_reset, and the actual fork runtime plus exactly one initial/maximum heap setting. The runner writes CPU model/affinity and VM build, preserves JSON nulls and explicitly sets fork arguments instead of inheriting conflicting heap options. All 26 focused parser tests and all four Java pairing tests pass; the complete native runner recompiles. Checkpoint activity is retained without adding an outcome-dependent exclusion rule. No timed measurements have started and the statistical protocol is unchanged.
