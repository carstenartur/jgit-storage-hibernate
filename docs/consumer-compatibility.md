# Consumer compatibility

`jgit-storage-hibernate` has three active consumers. They do not exercise the same modules or the same operational risks, so a candidate release is validated through three separate contracts rather than one generic downstream build.

The machine-readable pins and commands live in [`.github/consumer-compatibility.json`](../.github/consumer-compatibility.json). The `Consumer Compatibility` workflow installs the current candidate into the job-local Maven repository and then builds an exact, immutable revision of each consumer with only `jgit-storage-hibernate.version` overridden. No unpublished remote snapshot repository is required.

## Audio Analyzer: workflow history and generic Search

Audio Analyzer consumes both Core and Search. Its workflow-history adapter currently uses:

- `CommitIndexer` for best-effort incremental projection updates and bounded rebuilds;
- `CommitHistoryQuery` with text, author, path, time and candidate-object filters;
- `GitHistorySearchService.findChanges(...)` returning complete `GitCommitIndex` entities;
- changed-path values when mapping a hit into its own workflow-history result;
- the retained `getCommitTime()` compatibility alias.

This means lightweight Search projections are additive for Audio Analyzer, not an immediate replacement for the entity API. An upstream optimization must not remove changed-path detail or turn a projection failure into a failure of authoritative Git persistence.

The pull-request smoke builds and tests `audio-app` against locally installed Core and Search artifacts. Persistent PostgreSQL/restart scenarios remain part of the consumer's own deeper integration suite.

## Taxonomy: fail-closed Core schema ownership

Taxonomy consumes Core, but it owns an independent Hibernate Search domain for taxonomy data. Its critical upstream contract is therefore not the generic Search API. It is the physical and operational Core schema:

- exact released columns and index access paths;
- Flyway history and baseline versions;
- migration ordering before Hibernate validation;
- HSQLDB development and PostgreSQL/SQL Server/Oracle deployment families;
- repository lifecycle, deletion and transaction behavior.

Taxonomy intentionally rejects an unknown schema shape. New Core columns or indexes therefore require a coordinated Taxonomy adaptation; silently relaxing the classifier would weaken its startup safety. The smoke suite runs the focused schema, migration, index and storage integration tests. Database-container matrices remain explicit release gates for relevant schema changes.

## Sandbox: server lifecycle, legacy adoption and packaging

Sandbox currently consumes Core through two forms:

- an OSGi/plain-Maven integration bundle;
- a shaded standalone JGit Smart HTTP server.

Its primary deployment database is SQL Server, while HSQLDB and PostgreSQL legacy-adoption paths and H2 tests are also retained. Its critical contract includes:

- the public repository factory and lifecycle/delete APIs;
- read-only legacy preflight and safe adoption;
- restart and Smart HTTP repository behavior;
- bnd-generated OSGi metadata;
- shaded service/provider linkage.

Sandbox still contains copied Search and Java-analysis capabilities and has not completed the migration to the public upstream Search module. The compatibility matrix must not claim otherwise. Generic Search changes are release-relevant to Audio Analyzer today and migration-enabling for Sandbox later.

## Change classification

| Candidate change | Required consumer evidence |
|---|---|
| Search query/indexing API or mapping | Audio Analyzer Core+Search smoke; upstream persistent-Lucene/database matrix |
| Core entity, migration, column or index | Taxonomy schema tests; Sandbox lifecycle/adoption tests; relevant upstream database matrix |
| Repository lifecycle, deletion or transaction semantics | Taxonomy storage tests and Sandbox server tests |
| Module metadata, services or packaging | Sandbox OSGi manifest and shaded server package |
| Java-analysis public API | Sandbox migration contract once it consumes the upstream module |

## Coordinated changes

A candidate is not made backward compatible merely by weakening a consumer's assertions. When an intentional upstream schema or API change requires adaptation:

1. keep the upstream pull request open;
2. prepare a consumer pull request against the candidate artifacts or an equivalent local-install workflow;
3. preserve explicit migration and rollback behavior in the consumer;
4. update the pinned consumer commit only after that adaptation is merged;
5. record the three validated revisions in the release notes.

The pinned revisions are deliberately immutable. Scheduled maintenance may propose newer pins, but a moving default branch cannot silently change the evidence attached to an upstream pull request.
