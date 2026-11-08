# Search runtime sustained and activity diagnostic

## Intent and authorization

Complete the two explicit remaining requirements of issue #196: sustained/overload incremental indexing with concurrent queries, and direct Lucene commit/reader/merge activity. The existing full JMH matrix, recovery evidence and conservative `write-sync` decision remain intact. Root approved this bounded benchmark-only follow-up and an isolated worktree from `495653b`.

## Chosen approach

Use a separate diagnostic runner in the benchmark test sources, sharing the existing production `CommitIndexer`, Search mapping, synthetic Git history conventions, failure handler and resource budgets. Extend the existing registered runtime workflow with an explicit manual diagnostic mode. Root owns publication and hosted execution after review.

An expanded full JMH Cartesian matrix would be expensive and would not itself prove overload or individual Lucene activity. Production instrumentation or public API changes would add unrelated scope. The chosen runner instead retains a bounded time series and separate instrumented activity pass.

## Observable contract

- Two synchronization modes: existing `reference` and `sync-async-r500`; normal four-CPU/1-GiB and constrained one-CPU/512-MiB hosted resource budgets.
- A seeded Git history followed by repeated small projection transactions and parallel stable queries over a bounded sustained period. All workers, histories, sample counts, output size and waits have explicit limits.
- A separately identified overload phase uses a small supported indexing queue and a releasable filesystem output gate. It must prove the gate was reached and submission/visibility progress was delayed; it must not label a normal burst as saturation.
- Release the gate before joining workers or closing repository/provider, including failure and interruption paths. Preserve the original failure and attach cleanup failures. Verify exact SQL/Lucene/Git membership after drain, then reopen persistent storage and verify again.
- Untraced observations and a separately labelled trace pass remain separate. Trace-specific buffer settings, if needed to exercise merge events, are recorded and prevent interpreting trace timings as baseline performance.
- Retain raw CSV time series/transactions/queries, raw Lucene InfoStream events, exact settings/source/resource metadata and a completion record. A standard-library Python validator reproduces event summaries and operating metrics without inventing samples or attributing post-close events to acknowledgement.
- Label `getReader` events as NRT reader-open work, not proof of a changed reader. Segments-file publication, commit invocation, background merge completion and close are different events. Do not infer fsync or acknowledged durability from a filename event.
- No production defaults or APIs change; no new universal performance claim or default-promotion gate.

## Remaining implementation choices

The implementer chooses bounded phase lengths and parser event signatures after inspecting the pinned Lucene 9.12.3 implementation and a real local correctness fixture. Local tests use small deterministic HSQLDB/file-Lucene runs for correctness only; timed deployment studies run later on dedicated hosted runners.
