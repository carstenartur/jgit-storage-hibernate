# Native follow-up integration

The earlier integration is merged at `1c33640bc75206e81a9bc5722768860a2a2321f5` (tree `974c3c0589f0067f0135dbd65e3e6f205591902b`). Integrate the independently reviewed #161 paired publication, #196 sustained Search diagnostic and #211 native consumer publication harnesses without changing production defaults. Keep #189's experimental source overlay separate until its review is complete.

## Exact trigger and source identity

The available GitHub connector supports repository mutations and workflow inspection but exposes no workflow-dispatch operation. The previous native PostgreSQL and consumer runs were push/PR events. Use a documented push on exactly `codex/performance-followup-20261108` to select these four existing workflows on one immutable commit:

1. `performance-investigations.yml`: only its contract check and five preregistered publication-policy cells.
2. `search-runtime-performance.yml`: only the four sustained/overload cells with separate timing/activity passes.
3. `consumer-taxonomy-postgres.yml`: the six release/main/proposed × 0/20 ms native publication jobs and their complete comparison.
4. `consumer-compatibility.yml`: the existing real consumer baseline/candidate contracts, using the identical source commit as the publication study.

Ordinary main/PR/scheduled behavior and existing manual inputs retain their meaning. A manual dispatch on the named branch must still honor its selected mode. Branch deletion must not launch the targeted studies. Do not move the branch while measurements run. Record the actual event, source commit/tree and run IDs; do not label a push as workflow_dispatch. Preserve original raw measurements, source attribution and all unfavorable complete outcomes.

## Verification and integration

- Reproduce all independent review findings before fixing, and retain focused functional/evidence-validation results.
- Check event-selection behavior for the named push, main, PR, schedule, manual inputs and deletion. Parse the resulting YAML and shell/embedded Python; verify that unrelated benchmark matrices are not selected by the named push.
- Run the combined benchmark functional tests and repository Python contracts before publishing.
- Publish one immutable source, then inspect all expected job matrices and collect their artifact digests. Validate/recompute complete evidence before drafting closure claims.
- Integrate source and evidence in a normal protected PR, resolve review findings and merge only after required CI is green. Do not create a release.

## Pre-publication verification

The exact #161 review fixes were independently approved at `e301d16`; #196 at `3d35057`; #211 including native durability and fail-closed artifact privacy at `82c97db`. Their combined source compiles and passes 28 executed Java functional tests (three explicitly opt-in measurement cases are skipped locally) and 358 repository Python tests. The native performance runs are still unmeasured at this point.

The event-selection test first reproduced 12 wrong/missing selections, then passed after the targeted push conditions. Independent review found that the real-consumer workflow also needed deletion guards: all five failing job cases were reproduced and corrected. The final seven behavior tests cover the four workflows, normal/manual/scheduled behavior and deleted/other-branch pushes. YAML parsing and `git diff --check` pass. Independent review confirms only these four workflows match the named branch, no unrelated matrix/history publisher or release/image publication is selected, and native consumer artifact upload remains fail-closed after scanning raw results, logs and both JUnit report locations.
