# Release process

The project publishes immutable releases to an anonymous static Maven repository in branch `maven-repository`, then creates a GitHub Release. Development snapshots may continue to use GitHub Packages.

## Public artifacts

- `io.github.carstenartur:jgit-storage-hibernate-parent`
- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-search`
- `io.github.carstenartur:jgit-storage-hibernate-java-analysis`
- `io.github.carstenartur:jgit-storage-hibernate-architecture`
- `io.github.carstenartur:jgit-storage-hibernate-benchmarks`

The benchmark artifact is for CI and release review, not normal runtime use.

## Distribution channels

| Channel | Role | Consumer authentication |
|---|---|---|
| Static `maven-repository` branch | Primary immutable public release channel | none |
| GitHub Packages | Development snapshots | GitHub token |
| GitHub Releases | Notes and convenience artifacts | none |

The public Maven URL is:

```text
https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/
```

See [Public Maven repository](public-maven-repository.md).

## One release authority

`.github/scripts/release.sh` owns version validation, documentation updates, complete Maven verification, static repository staging, immutability checks, anonymous local and remote consumption, release commit/tag creation, GitHub Release creation and next-snapshot preparation.

The root POM keeps publication behind explicit profiles:

- `public-repository-release` deploys to a local file repository for immutable public releases;
- `github-packages` supplies authenticated snapshot distribution metadata.

No Sonatype account, Central token, PGP key or consumer credential is part of this path.

## CI contract

The normal Maven workflow runs a `Public Maven repository contract` job. It derives the release version from the current snapshot, invokes the real release script with `DRY_RUN=true` and `SKIP_TESTS=true`, stages the complete repository locally and resolves all artifacts from a new empty Maven cache. This validates release layout without publishing.

## Starting a release

A release can be started from the Actions UI or through a guarded request branch.

The normal Actions dialog does not accept version strings. The workflow reads the authoritative root POM version `X.Y.Z-SNAPSHOT`, derives release `X.Y.Z`, and asks only how the following development line should advance:

```text
next_version_increment = patch | minor | major
skip_tests = false                          # only permitted for a dry run
dry_run = false
```

For example, a repository at `0.1.17-SNAPSHOT` releases `0.1.17`; the choices produce:

| Choice | Next development version |
|---|---:|
| `patch` | `0.1.18-SNAPSHOT` |
| `minor` | `0.2.0-SNAPSHOT` |
| `major` | `1.0.0-SNAPSHOT` |

For an agent-driven standard release, create branch `release-request/X.Y.Z` from current `main` and add `.github/release-request` containing exactly `X.Y.Z`. The workflow checks out authoritative `main`, verifies the marker against the branch name, runs the release and removes the request branch after success. A reviewed non-standard next version can be expressed as JSON:

```json
{
  "release_version": "X.Y.Z",
  "next_development_version": "A.B.C-SNAPSHOT"
}
```

Exact version jumps therefore live in version control and review history, rather than in an ad-hoc Actions text field.

## Real release sequence

1. Derive and validate current/documented versions and static repository configuration.
2. Prepare release Maven and documentation metadata.
3. Run the complete Maven reactor, including Testcontainers-backed PostgreSQL coverage.
4. Deploy all release POMs and primary/source/Javadoc JARs into a local Maven-layout directory.
5. Verify required files, reject snapshots and generate SHA-256/SHA-512 evidence.
6. Resolve the staged repository anonymously from an empty Maven cache.
7. Merge it into branch `maven-repository`; an existing version is accepted only when bytes are identical.
8. Resolve the published repository over anonymous HTTPS with retry for CDN propagation.
9. Commit/tag the release on `main` and create the GitHub Release.
10. Advance Maven/software metadata to the calculated or reviewed next snapshot while public examples remain on the released version.

A real release cannot skip tests. A dry run stops after local repository staging and anonymous resolution.

## Recovery

The Maven repository may be published before a later GitHub tag/release step fails. Re-run only after inspecting the branch, tag and GitHub Release. The release script will continue when existing version bytes are identical and will fail on any attempted overwrite.
