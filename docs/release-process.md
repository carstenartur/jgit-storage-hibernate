# Release process

The project publishes immutable releases to an anonymous static Maven repository in branch
`maven-repository`, then creates a GitHub Release. Development snapshots may continue to use GitHub
Packages.

## Public artifacts

- `io.github.carstenartur:jgit-storage-hibernate-parent`
- `io.github.carstenartur:jgit-storage-hibernate-bom`
- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-security`
- `io.github.carstenartur:jgit-storage-hibernate-smart-http`
- `io.github.carstenartur:jgit-storage-hibernate-search`
- `io.github.carstenartur:jgit-storage-hibernate-spring-boot-autoconfigure`
- `io.github.carstenartur:jgit-storage-hibernate-spring-boot-starter`
- `io.github.carstenartur:jgit-storage-hibernate-server`
- `io.github.carstenartur:jgit-storage-hibernate-testcontainers`
- `io.github.carstenartur:jgit-storage-hibernate-java-analysis`
- `io.github.carstenartur:jgit-storage-hibernate-architecture`
- `io.github.carstenartur:jgit-storage-hibernate-benchmarks`

The parent and BOM are POM-only artifacts. The benchmark artifact is for CI and release review, not
normal runtime use. Smart HTTP, the Spring Boot integration, the standalone server and Testcontainers
support are optional product paths and are versioned with the same reactor release as Core.

## Distribution channels

| Channel | Role | Consumer authentication |
|---|---|---|
| Static `maven-repository` branch | Primary immutable public release channel | none |
| GHCR server image | Runnable PostgreSQL-backed Git Smart HTTP server | none for public images |
| GitHub Packages | Development snapshots | GitHub token |
| GitHub Releases | Notes and convenience artifacts | none |

The public Maven URL is:

```text
https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/
```

See [Public Maven repository](public-maven-repository.md).

## One release authority

`.github/scripts/release.sh` owns version validation, documentation updates, complete Maven
verification, static repository staging, immutability checks, anonymous local and remote consumption,
release commit/tag creation, GitHub Release creation and next-snapshot preparation.

The root POM keeps publication behind explicit profiles:

- `public-repository-release` deploys to a local file repository for immutable public releases;
- `github-packages` supplies authenticated snapshot distribution metadata.

No Sonatype account, Central token, PGP key or consumer credential is part of this path.

## Repository permissions and token model

The standard release does **not** require a long-lived personal access token. The release workflow
uses its short-lived repository `GITHUB_TOKEN` with explicit permissions:

```yaml
permissions:
  actions: write
  contents: write
  pull-requests: write
```

Repository **Settings → Actions → General** must allow GitHub Actions to create pull requests. The
workflow never approves or merges its own protected release PR.

GitHub intentionally suppresses ordinary workflow recursion for pushes made with `GITHUB_TOKEN`.
After creating a generated release or next-development PR, the release workflow therefore invokes
`.github/scripts/dispatch-generated-pr-checks.sh`. It uses `workflow_dispatch` on the exact remote
branch head to start Maven, BOM, JGit compatibility, real-consumer compatibility, server-image and
JMH checks without a PAT or manual workflow approval.

An optional fine-grained `RELEASE_GITHUB_TOKEN` may still be configured as an operational override.
When absent, every checkout, branch push, PR operation, publication step and explicit check dispatch
uses `github.token`. No credential is written to repository files, logs or artifacts.

## CI contract

The normal Maven workflow runs a `Public Maven repository contract` job. It derives the release
version from the current snapshot, invokes the real release script with `DRY_RUN=true` and
`SKIP_TESTS=true`, stages the complete repository locally and resolves all artifacts from a new empty
Maven cache. This validates release layout without publishing.

The staged repository verifier requires the parent and BOM POMs plus the expected primary, source and
Javadoc artifacts for all public JAR modules. Every retained release file receives canonical
SHA-256/SHA-512 evidence and a SHA-1 compatibility sidecar. The anonymous consumer then imports the
candidate BOM and resolves every production module.

Runtime, schema and dependency compatibility with the active downstream applications is covered
separately by the [real-consumer compatibility gates](consumer-compatibility.md). The release review
must inspect retained audio-analyzer, Taxonomy and sandbox evidence when a candidate changes public
APIs, migrations, mappings, packaging or transitive dependencies.

## Starting a release

A release can be started from the Actions UI or through a guarded request branch.

The release version is never entered manually. The workflow reads the authoritative root POM version
`X.Y.Z-SNAPSHOT` and derives release `X.Y.Z`.

```text
next_development_version = A.B.C-SNAPSHOT  # optional exact override
next_version_increment = patch | minor | major
skip_tests = false                          # only permitted for a dry run
dry_run = false
```

Normal automated releases provide only `next_version_increment`; they do not construct or transmit a
next-version string. The optional exact override is normalized, validated and must be numerically
newer than the release.

For example, a repository at `0.11.2-SNAPSHOT` releases `0.11.2`; without an exact override the
choices produce:

| Choice | Next development version |
|---|---:|
| `patch` | `0.11.3-SNAPSHOT` |
| `minor` | `0.12.0-SNAPSHOT` |
| `major` | `1.0.0-SNAPSHOT` |

For an agent-driven standard release, create branch `release-request/X.Y.Z` from current `main` and
add `.github/release-request` containing exactly `X.Y.Z`. The workflow checks out authoritative
`main`, verifies the marker against the branch name and prepares a protected release PR. A reviewed
non-standard next version can be expressed as JSON:

```json
{
  "release_version": "X.Y.Z",
  "next_development_version": "A.B.C-SNAPSHOT"
}
```

Exact version jumps therefore live in version control and review history when automation initiates
them, rather than in a generated dispatch payload.

## Real release sequence

1. Derive and validate current/documented versions and static repository configuration.
2. Prepare release Maven and documentation metadata and run the complete Maven reactor.
3. Push `release/prepare-X.Y.Z`, open the protected release PR and explicitly dispatch its repository-owned checks.
4. After the reviewed PR is merged, validate the immutable release candidate from the merge commit.
5. Deploy all release POMs and primary/source/Javadoc artifacts into a local Maven-layout directory.
6. Verify required files, reject snapshots, generate checksums and resolve the staged repository anonymously.
7. Merge byte-identical artifacts into branch `maven-repository` and verify anonymous HTTPS consumption.
8. Create the immutable `vX.Y.Z` tag, GitHub Release and release convenience artifacts.
9. Push `release/next-A.B.C`, open the protected next-development PR and explicitly dispatch the same critical checks.
10. Merge the reviewed next-development PR to restore `A.B.C-SNAPSHOT` on `main` while public examples remain pinned to `X.Y.Z`.

A real release cannot skip tests. A dry run stops after release preparation and complete local
verification without pushing a branch or creating a PR.

## Recovery

The Maven repository may be published before a later GitHub tag/release step fails. Re-run only after
inspecting the branch, tag and GitHub Release. The release script will continue when existing version
bytes are identical and will fail on any attempted overwrite. The recovery workflow also uses the
short-lived repository token unless the optional override secret is configured.
