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

## Implementation boundary

There is one release workflow and one release state machine:

| Component | Responsibility |
|---|---|
| `.github/workflows/release.yml` | GitHub Actions entry point: collect inputs, check out complete history, configure Java/package credentials and invoke the existing release script. |
| `.github/scripts/release.sh` | Single authority for release-only policy and sequencing: branch, requested/current/documented version agreement, Docker/test requirements, tag checks, version changes, publication, release creation and next-snapshot preparation. |
| `.github/scripts/update-release-metadata.py` | Deterministically update Citation, Zenodo and CodeMeta versions and dates. |
| `.github/scripts/verify-release-consistency.py` | Read-only static checks reusable from pull-request CI, snapshot publishing and the release script. It does not publish, tag or mutate release state. |
| `.github/release.yml` | Category configuration consumed by GitHub-generated release notes. It is not an Actions workflow. |

Keeping release policy in `release.sh` makes the same state machine available for a local or CI dry run; the workflow does not reimplement it.

## Release-note traceability

User-visible features, schema changes, compatibility changes and release-process hardening must have an issue before implementation. Pull requests should close or reference those issues. GitHub-generated release notes then provide a traceable list of merged work instead of a generic one-line release description.

Use clear PR titles because they become release-note entries. Apply release-note category labels when the repository configuration defines them. `.github/release.yml` must retain multiple meaningful categories and a final catch-all category so an unlabeled merged PR cannot disappear from the generated notes.

## Documentation version contract

`docs/current-release-version.txt` contains the version used by public Maven dependency snippets. It represents the release being documented, not necessarily the current development `-SNAPSHOT` version.

Before releasing `X.Y.Z`:

- update the file to `X.Y.Z` in the release-preparation PR;
- update all public dependency snippets to `X.Y.Z`;
- keep historical migration references such as the 0.1.4 legacy baseline where they are semantically required.

`.github/scripts/verify-release-consistency.py` performs read-only checks for:

- root and module Maven versions;
- project-owned dependency versions;
- aligned Flyway, PostgreSQL JDBC and Testcontainers test dependencies;
- `CITATION.cff`, `CITATION.md`, `.zenodo.json` and `codemeta.json`;
- the Java baseline in Maven metadata, CodeMeta and README;
- public project dependency snippets in the root README, nested `docs/**/*.md` and module READMEs;
- version-neutral `X.Y.Z` and `X.Y.Z-SNAPSHOT` help text in the existing release workflow;
- delegation from the workflow to the existing release script;
- categorized generated-release-note configuration with a catch-all category;
- the `--generate-notes`, `--verify-tag` and `--fail-on-no-commits` safeguards on the existing `gh release create` command.

The release script separately requires the requested release version, current Maven snapshot base and documented release version to agree. This keeps release-time state policy in one place while allowing the static checker to remain reusable in pull requests and snapshot publication.

## Snapshot publishing

Snapshots are published by `.github/workflows/publish-snapshot.yml`. Normal CI runs the static consistency verifier so metadata and public documentation cannot silently diverge.

## Manual release workflow

Run `.github/workflows/release.yml` from `main`.

Inputs:

```text
release_version = X.Y.Z
next_development_version = X.Y.Z-SNAPSHOT   # optional; patch is incremented by default
skip_tests = false                          # real releases reject true
dry_run = false
```

The existing `release.sh` invoked by the workflow:

1. verifies that the current Maven version is the matching snapshot;
2. verifies that the documented release version matches the request;
3. verifies static repository, documentation and metadata consistency;
4. checks that the tag does not already exist;
5. sets the release Maven version;
6. updates citation and software metadata;
7. requires Docker and runs the full Maven build, including Testcontainers-backed PostgreSQL migration tests;
8. rejects remaining snapshot POM references;
9. deploys Maven artifacts to GitHub Packages;
10. commits and tags the release;
11. creates a GitHub Release with generated notes and attached JAR/metadata files;
12. advances Maven and citation metadata to the next development snapshot;
13. re-runs static consistency verification and pushes the development commit.

A normal dry run performs the same validation and full build but does not deploy, tag, create a release or push commits. A deliberately test-free dry run may set `skip_tests=true`; the script rejects that setting for a real release and does not require Docker when no tests run.

## Pre-release checklist

- all release-worthy work has an issue and merged PR;
- required checks are green;
- `docs/current-release-version.txt` equals the requested version;
- README and module guides describe the release version;
- database changes have immutable migrations and upgrade fixtures;
- release notes for destructive or compatibility-sensitive migrations include operational instructions;
- a full dry run has completed successfully from the intended source branch.

## Failure behavior

The release script stops before publication on any branch, version, metadata, documentation, test or tag inconsistency. Do not bypass a failed consistency check by weakening the search or editing generated release history. Correct the source documentation, configuration or metadata in a reviewed commit and re-run the workflow.

If deployment succeeds but a later GitHub operation fails, inspect GitHub Packages, tags and releases before retrying so the same version is not published twice.

## Benchmarks

JMH benchmarks live in `jgit-storage-hibernate-benchmarks` and run through `.github/workflows/performance.yml`.

## DOI and Zenodo

The repository includes `.zenodo.json` and `CITATION.cff` so Zenodo can archive GitHub Releases. The DOI is recorded in the project README and citation metadata after it has been minted.
