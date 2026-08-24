# Release process

The project publishes immutable Maven releases to an anonymous static repository in branch
`maven-repository`, creates a GitHub Release and publishes the standalone server as a versioned
multi-architecture OCI image in GitHub Container Registry. Development snapshots may continue to use
GitHub Packages for Maven artifacts.

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
| Static `maven-repository` branch | Primary immutable public Maven release channel | none |
| GHCR server image | Runnable PostgreSQL-backed Git Smart HTTP server | none after public-visibility verification |
| GitHub Packages | Development Maven snapshots | GitHub token |
| GitHub Releases | Notes and convenience artifacts | none |

The public Maven URL is:

```text
https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/
```

The released server image is:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:X.Y.Z
```

Numeric image tags are treated as first-write immutable by repository automation. `latest` and
`edge` are moving compatibility aliases; deployments and stable tests should pin a numeric tag, or
the published manifest digest when byte-for-byte identity is required.

See [Public Maven repository](public-maven-repository.md) and the
[standalone server guide](../jgit-storage-hibernate-server/README.md).

## One release authority per channel

`.github/scripts/release.sh` owns version validation, documentation updates, complete Maven
verification, static repository staging, immutability checks, anonymous local and remote consumption,
release commit/tag creation, GitHub Release creation and next-snapshot preparation.

`.github/workflows/server-image-publish.yml` is the separate OCI publication authority. It accepts
only an annotated `vX.Y.Z` tag, checks that the tag target and Maven reactor both represent `X.Y.Z`,
and then either publishes the version tag once or verifies and reuses an already published matching
digest. It builds `linux/amd64` and `linux/arm64`, adds OCI labels plus provenance and SBOM
attestations, and finally verifies the version reference without registry credentials.

The root POM keeps Maven publication behind explicit profiles:

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
branch head to start Maven, BOM, JGit compatibility, real-consumer compatibility, server-image,
server-image-publication-contract and JMH checks without a PAT or manual workflow approval.

The image publication job has only `contents: read` and `packages: write`. It logs in to GHCR with the
short-lived `GITHUB_TOKEN`; final inspection uses a new Docker configuration with no credentials. If
GitHub creates the first package with private visibility rather than inheriting the public repository
visibility, the anonymous check fails explicitly and the package must be changed to **Public** before
publication is considered complete.

An optional fine-grained `RELEASE_GITHUB_TOKEN` may still be configured as an operational override
for the Maven/GitHub release workflow. When absent, every checkout, branch push, PR operation,
publication step and explicit check dispatch uses `github.token`. No credential is written to
repository files, logs or artifacts.

## CI contract

The normal Maven workflow runs a `Public Maven repository contract` job. It derives the release
version from the current snapshot, invokes the real release script with `DRY_RUN=true` and
`SKIP_TESTS=true`, stages the complete repository locally and resolves all artifacts from a new empty
Maven cache. This validates release layout without publishing.

The staged repository verifier requires the parent and BOM POMs plus the expected primary, source and
Javadoc artifacts for all public JAR modules. Every retained release file receives canonical
SHA-256/SHA-512 evidence and a SHA-1 compatibility sidecar. The anonymous consumer then imports the
candidate BOM and resolves every production module.

`Server image publication contract` statically verifies immutable source selection, first-write
version-tag behavior, full-SHA action pins, tag/reactor validation, non-rollback aliases,
multi-architecture output, OCI evidence, anonymous digest checks, Compose parsing, safe deployment
defaults and alignment of Compose/Testcontainers defaults with the documented release. The normal
server-image smoke test separately builds the current source through the explicit development Compose
override and proves create, push, query, PostgreSQL inspection, restart and clone behavior.

Runtime, schema and dependency compatibility with active downstream applications is covered
separately by the [real-consumer compatibility gates](consumer-compatibility.md). Release review must
inspect retained audio-analyzer, Taxonomy and sandbox evidence when a candidate changes public APIs,
migrations, mappings, packaging or transitive dependencies.

## Starting a release

A release can be started from the Actions UI or through a guarded request branch. The release version
is never entered manually. The workflow reads the authoritative root POM version `X.Y.Z-SNAPSHOT`
and derives release `X.Y.Z`.

```text
next_development_version = A.B.C-SNAPSHOT  # optional exact override
next_version_increment = patch | minor | major
skip_tests = false                          # only permitted for a dry run
dry_run = false
```

Normal automated releases provide only `next_version_increment`; they do not construct or transmit a
next-version string. The optional exact override is normalized, validated and must be numerically
newer than the release.

For a repository at `X.Y.Z-SNAPSHOT`, the increment choices produce the corresponding next patch,
minor or major development line. For an agent-driven standard release, create branch
`release-request/X.Y.Z` from current `main` and add `.github/release-request` containing exactly
`X.Y.Z`. The workflow checks out authoritative `main`, verifies the marker against the branch name and
prepares a protected release PR. A reviewed non-standard next version can be expressed as JSON:

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
8. Create the annotated immutable `vX.Y.Z` tag, GitHub Release and release convenience artifacts.
9. The tag starts OCI publication. The version tag is created once, `latest` and `edge` move to its exact digest, and all references are checked anonymously on both architectures.
10. Push `release/next-A.B.C`, open the protected next-development PR and explicitly dispatch the same critical checks.
11. Merge the reviewed next-development PR to restore `A.B.C-SNAPSHOT` on `main` while public examples and image defaults remain pinned to `X.Y.Z`.

A real release cannot skip tests. A dry run stops after release preparation and complete local
verification without pushing a branch or creating a PR.

## OCI immutability and alias policy

GHCR allows a tag to be moved, so immutability is enforced by the publication workflow rather than
assumed from the registry.

For a version `X.Y.Z` the workflow follows this contract:

1. Resolve an annotated `vX.Y.Z` Git tag and verify its target commit and reactor version.
2. Inspect `ghcr.io/carstenartur/jgit-storage-hibernate-server:X.Y.Z` before building.
3. If absent, build and push that numeric tag once for `linux/amd64` and `linux/arm64`.
4. If present, require both platforms and require the image's OCI `version` and `revision` labels to
   match `X.Y.Z` and the immutable Git tag target. Reuse its existing digest without rebuilding.
5. Fail on label, source, platform or digest ambiguity instead of overwriting a numeric tag.
6. For a normal new tag event, move `latest` and `edge` to the selected digest and reject a semantic
   version rollback.
7. Verify every changed reference anonymously and require it to resolve to the selected digest.

This means a rerun is idempotent: it either confirms the existing artifact or fails visibly. A source
change requires a new release version, never a rebuilt image under an old numeric tag.

## OCI image backfill

A release tag created before the OCI workflow existed can be published without rebuilding snapshot
sources. Dispatch `Publish server OCI image` from **main** with `release_tag=vX.Y.Z`.

`update_aliases` defaults to `false`. Keep that default for routine backfills so publishing an older
missing version cannot move `latest` or `edge` backwards. Set it only for an intentional current
release recovery; the workflow still rejects rollback behind a newer `latest` version.

The manual path:

1. validates the requested `vX.Y.Z` syntax and that the workflow itself runs from `main`;
2. checks out the existing tag rather than current snapshot sources;
3. requires an annotated tag whose target equals the checked-out commit;
4. requires the reactor version to be exactly `X.Y.Z` and rejects snapshots;
5. creates the version tag only when absent, otherwise verifies and reuses it;
6. never uses a temporary branch as a publication trigger.

Never move or recreate an existing release tag to repair image publication.

## Consumer pinning

A numeric tag is stable under the repository publication contract and is the recommended balance for
normal deployments:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:X.Y.Z
```

A security-sensitive or fully reproducible deployment should pin the manifest digest reported in the
workflow summary and GHCR package metadata:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:X.Y.Z@sha256:<manifest-digest>
```

`latest` and `edge` are explicitly mutable and must not be used as reproducibility or rollback
boundaries.

## Recovery

The Maven repository may be published before a later GitHub tag/release or OCI step fails. Re-run only
after inspecting the branch, tag, GitHub Release and package state. The Maven release script continues
only when existing version bytes are identical and fails on any attempted overwrite.

For OCI recovery, rerun the tag workflow or use the manual backfill from `main`. If the numeric image
exists with the expected source labels and platforms, the workflow reuses its digest. If it differs,
the workflow fails and the discrepancy must be investigated; it never repairs the situation by
silently overwriting the version tag. Alias repair copies the verified digest and cannot roll back
behind a newer semantic version.
