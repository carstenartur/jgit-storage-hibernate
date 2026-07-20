# Release process

This project publishes Maven artifacts to GitHub Packages and creates a GitHub Release with automatically generated notes and attached build artifacts.

## Published artifacts

- `io.github.carstenartur:jgit-storage-hibernate-parent`
- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-search`
- `io.github.carstenartur:jgit-storage-hibernate-java-analysis`
- `io.github.carstenartur:jgit-storage-hibernate-architecture`
- `io.github.carstenartur:jgit-storage-hibernate-benchmarks`

The benchmark artifact is for CI and release review, not normal runtime use.

## One release path

There is one GitHub Actions entry point and one release state machine:

| Component | Responsibility |
|---|---|
| `.github/workflows/release.yml` | Collect workflow inputs, check out complete history, configure Java and package credentials, then invoke the existing release script. |
| `.github/scripts/release.sh` | Single authority for release-only policy and sequencing: branch checks, requested/current version agreement, automatic public-documentation preparation, Docker/test policy, tag checks, publication, release creation and next-snapshot preparation. |
| `.github/scripts/update-release-metadata.py` | Deterministically update Citation, Zenodo and CodeMeta versions. During a release it also advances the documented release version and active public project dependency examples. |
| `.github/scripts/verify-release-consistency.py` | Read-only static checks reusable from pull-request CI, snapshot publication and the release script. It does not publish, tag or mutate release state. |
| `.github/release.yml` | Category configuration consumed by GitHub-generated release notes. It is not an Actions workflow. |

The workflow must not duplicate Docker checks, Maven version changes, documentation generation, tag creation or `gh release create`. Keeping that policy in `release.sh` makes the same state machine usable from GitHub Actions and from a local dry run.

## Release-note traceability

User-visible features, schema changes, compatibility changes and release-process hardening must have an issue before implementation. Pull requests should close or reference those issues. GitHub-generated release notes then provide a traceable list of merged work instead of a generic one-line release description.

Use clear PR titles because they become release-note entries. Apply release-note category labels when the repository configuration defines them. `.github/release.yml` must retain meaningful categories and a final catch-all category so an unlabeled merged PR cannot disappear from the generated notes.

## Documentation version contract

`docs/current-release-version.txt` contains the version used by active public Maven dependency snippets. During normal development it represents the **last published release**, while Maven and software metadata may already be on the next `-SNAPSHOT` line.

For example, this is the expected state after releasing 0.1.5:

```text
Maven/software development version: 0.1.6-SNAPSHOT
Public documented release:          0.1.5
```

A separate manual release-preparation PR is not required merely to align these two values. When release `X.Y.Z` is dispatched from matching Maven version `X.Y.Z-SNAPSHOT`, the release script automatically:

1. updates `docs/current-release-version.txt` to `X.Y.Z`;
2. updates active Maven dependency blocks for project artifacts to `X.Y.Z`;
3. updates active `io.github.carstenartur:jgit-storage-hibernate-*:X.Y.Z` coordinates;
4. updates the root README's explicit documented-release-line statement;
5. leaves version-neutral placeholders and historical release notes untouched;
6. runs the complete static consistency verifier before publication;
7. includes generated documentation in the release commit.

Historical migration references such as the 0.1.4 legacy baseline are not release dependency snippets and remain unchanged. After publication, the next-development commit advances Maven and software metadata to the next snapshot but continues to document the immutable release that was just published.

`.github/scripts/verify-release-consistency.py` checks:

- root and module Maven versions;
- project-owned dependency versions;
- aligned Flyway, PostgreSQL JDBC and Testcontainers test dependencies;
- `CITATION.cff`, `CITATION.md`, `.zenodo.json` and `codemeta.json`;
- the Java baseline in Maven metadata, CodeMeta and README;
- public project dependency snippets in the root README, nested `docs/**/*.md` and module READMEs;
- version-neutral `X.Y.Z` and `X.Y.Z-SNAPSHOT` help text in the release workflow;
- delegation from the workflow to `release.sh` without duplicated Docker or release-creation commands;
- categorized generated-release-note configuration with a catch-all category;
- the `--generate-notes`, `--verify-tag` and `--fail-on-no-commits` safeguards on the existing `gh release create` command.

Version placeholders such as `...`, `X.Y.Z`, `${project.version}` and `${revision}` are permitted in explicitly version-neutral examples. Concrete project versions in active documentation must equal `docs/current-release-version.txt`. Historical release notes under `docs/releases/` are excluded from this active-documentation check.

The release script requires the requested release version and the base of the current Maven snapshot to agree. A different documented release version is normal and produces an informational notice because the script advances it automatically. Invalid or internally inconsistent documentation still fails the pre-mutation or post-generation consistency checks.

## Snapshot publishing

Snapshots are published by `.github/workflows/publish-snapshot.yml`. Normal CI and snapshot publication run the same static consistency verifier so metadata and public documentation cannot silently diverge within their separate development and published-release roles.

## Manual release workflow

Run `.github/workflows/release.yml` from `main`.

Inputs:

```text
release_version = X.Y.Z
next_development_version = X.Y.Z-SNAPSHOT   # optional; patch is incremented by default
skip_tests = false                          # permitted only for a dry run
dry_run = false
```

The existing `release.sh` invoked by the workflow:

1. validates the requested version format and source branch;
2. requires the current Maven version to be the matching snapshot;
3. reads the currently documented published release and explains when it will be advanced;
4. verifies the clean development checkout's repository, documentation, metadata and release-contract consistency;
5. checks that the release tag does not already exist;
6. sets the release Maven version;
7. updates citation/software metadata and public release documentation;
8. re-runs static consistency verification over the generated release state;
9. requires Docker and runs the complete Maven build, including Testcontainers-backed PostgreSQL migration tests;
10. rejects remaining snapshot POM references;
11. deploys Maven artifacts to GitHub Packages;
12. commits Maven, metadata and generated documentation changes and tags the release;
13. creates a GitHub Release with generated notes and attached JAR/metadata files;
14. advances Maven and citation metadata to the next development snapshot while keeping public dependency examples on the released version;
15. re-runs static consistency verification and pushes the development commit.

A normal dry run performs the same automatic preparation, validation and complete build but does not deploy, tag, create a release or push commits. A deliberately test-free dry run may set `skip_tests=true`; the script rejects that setting for a real release and does not require Docker when tests are intentionally skipped.

## Pre-release checklist

- all release-worthy work has an issue and merged PR;
- required checks are green;
- the current Maven version is `X.Y.Z-SNAPSHOT` for requested release `X.Y.Z`;
- `docs/current-release-version.txt` contains a valid previously published `X.Y.Z` value;
- active documentation is internally consistent with that documented published version;
- database changes have immutable migrations and upgrade fixtures;
- release notes for destructive or compatibility-sensitive migrations include operational instructions;
- a full dry run has completed successfully from the intended source branch.

The documentation version does **not** have to be changed manually to the requested release before dispatch. That transition is part of the tested release state machine.

## Failure behavior

The release script stops before publication on any Maven-version, metadata, generated-documentation, release-note, test, Docker or tag inconsistency.

A Maven mismatch reports both the requested release and current snapshot and explains whether to dispatch the matching release or first move `main` to the requested snapshot. A different valid documented release produces a notice and is advanced automatically. Invalid documentation files or generated snippets produce explicit consistency errors with the affected path.

Do not bypass a failed consistency check by weakening the checker or editing generated release history. Correct genuine source inconsistencies and re-run the workflow.

If deployment succeeds but a later GitHub operation fails, inspect GitHub Packages, tags and releases before retrying so the same version is not published twice.

## Benchmarks

JMH benchmarks live in `jgit-storage-hibernate-benchmarks` and run through `.github/workflows/performance.yml`.

## DOI and Zenodo

The repository includes `.zenodo.json` and `CITATION.cff` so Zenodo can archive GitHub Releases. The DOI is recorded in the project README and citation metadata after it has been minted.
