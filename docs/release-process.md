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

## Release-note traceability

User-visible features, schema changes, compatibility changes and release-process hardening must have an issue before implementation. Pull requests should close or reference those issues. GitHub-generated release notes then provide a traceable list of merged work instead of a generic one-line release description.

Use clear PR titles because they become release-note entries. Apply release-note category labels when the repository configuration defines them. `.github/release.yml` must retain multiple meaningful categories and a final catch-all category so an unlabeled merged PR cannot disappear from the generated notes.

## Documentation version contract

`docs/current-release-version.txt` contains the version used by public Maven dependency snippets. It represents the release being documented, not necessarily the current development `-SNAPSHOT` version.

Before releasing `X.Y.Z`:

- update the file to `X.Y.Z` in the release-preparation PR;
- update all public dependency snippets to `X.Y.Z`;
- keep historical migration references such as the 0.1.4 legacy baseline where they are semantically required.

`.github/scripts/verify-release-consistency.py` checks:

- the root and module Maven versions;
- project-owned dependency versions;
- `CITATION.cff`, `CITATION.md`, `.zenodo.json` and `codemeta.json`;
- the Java baseline in Maven metadata, CodeMeta and README;
- every public Maven snippet for a project artifact;
- the requested release version against the documented release version;
- version-neutral `X.Y.Z` and `X.Y.Z-SNAPSHOT` help text in the existing release workflow;
- categorized generated-release-note configuration with a catch-all category;
- the `--generate-notes`, `--verify-tag` and `--fail-on-no-commits` safeguards on the existing `gh release create` command.

This distinguishes legitimate historical version references from stale copy-and-paste dependency snippets. It also protects the existing release path from being split or silently weakened: `.github/workflows/release.yml` remains the orchestration entry point and delegates publication to `.github/scripts/release.sh`.

## Snapshot publishing

Snapshots are published by `.github/workflows/publish-snapshot.yml`. Normal CI runs the consistency verifier so metadata and public documentation cannot silently diverge.

## Manual release workflow

Run `.github/workflows/release.yml` from `main`.

Inputs:

```text
release_version = X.Y.Z
next_development_version = X.Y.Z-SNAPSHOT   # optional; patch is incremented by default
skip_tests = false                          # real releases reject true
dry_run = false
```

The workflow:

1. verifies that the current Maven version is the matching snapshot;
2. verifies release documentation and metadata consistency;
3. checks that the tag does not already exist;
4. sets the release Maven version;
5. updates citation and software metadata;
6. requires Docker and runs the full Maven build, including Testcontainers-backed PostgreSQL migration tests;
7. rejects remaining snapshot POM references;
8. deploys Maven artifacts to GitHub Packages;
9. commits and tags the release;
10. creates a GitHub Release with generated notes and attached JAR/metadata files;
11. advances Maven and citation metadata to the next development snapshot;
12. re-runs consistency verification and pushes the development commit.

A dry run performs all validation and build steps but does not deploy, tag, create a release or push commits.

## Pre-release checklist

- all release-worthy work has an issue and merged PR;
- required checks are green;
- `docs/current-release-version.txt` equals the requested version;
- README and module guides describe the release version;
- database changes have immutable migrations and upgrade fixtures;
- release notes for destructive or compatibility-sensitive migrations include operational instructions;
- a dry run has completed successfully from the intended source branch.

## Failure behavior

The release script stops before publication on any version, metadata, documentation, generated-note, workflow, test or tag inconsistency. Do not bypass a failed consistency check by weakening the search or editing generated release history. Correct the source documentation, configuration or metadata in a reviewed commit and re-run the workflow.

If deployment succeeds but a later GitHub operation fails, inspect GitHub Packages, tags and releases before retrying so the same version is not published twice.

## Benchmarks

JMH benchmarks live in `jgit-storage-hibernate-benchmarks` and run through `.github/workflows/performance.yml`.

## DOI and Zenodo

The repository includes `.zenodo.json` and `CITATION.cff` so Zenodo can archive GitHub Releases. The DOI is recorded in the project README and citation metadata after it has been minted.
