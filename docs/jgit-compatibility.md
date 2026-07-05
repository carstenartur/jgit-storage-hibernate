# JGit compatibility guardrails

This repository treats JGit as a platform dependency. Dependency updates must not be accepted only because the local project tests pass with one JGit version.

## Supported matrix

The CI workflow `.github/workflows/jgit-compatibility.yml` verifies the full Maven build against these JGit versions:

| JGit version | Purpose |
|---|---|
| `7.5.0.202512021534-r` | Oldest currently tested support line. |
| `7.6.0.202603022253-r` | Intermediate compatibility line. |
| `7.7.0.202606012155-r` | Current primary version used by the parent POM. |

Each version is tested on Java 17 and Java 21. Java 17 remains the release baseline; Java 21 protects consuming applications that run newer JVMs.

## Maven guardrails

The parent POM manages the JGit version through the `jgit.version` property and dependency management. Module POMs should not hard-code their own JGit versions.

The default Maven build runs the Enforcer plugin during `validate` and fails on:

- unsupported Maven versions,
- unsupported Java versions,
- dependency convergence conflicts,
- upper-bound dependency conflicts,
- duplicate dependency declarations in POM files.

Run the same checks locally with:

```bash
mvn -B verify
```

## Canary tests

`JGitCompatibilityCanaryTest` is intentionally small and uses only public JGit APIs. It writes and reads a blob, creates a tree and commit, updates a branch ref, checks the reflog and walks the commit.

This is not a replacement for storage-specific integration tests. It is a fast early-warning test for dependency updates that would otherwise show up later as runtime linkage problems or subtle JGit API incompatibilities.

## Optional linkage checker

For release candidates or suspicious dependency updates, run:

```bash
mvn -B -Pdependency-linkage -DskipTests verify
```

The linkage profile uses the Linkage Checker Enforcer Rule with `reportOnlyReachable=true`, so it focuses on reachable runtime linkage errors instead of every optional classpath edge.

## Maintainer checklist for dependency PRs

Before merging a dependency update:

1. Check that the normal Maven workflow is green.
2. Check that the JGit compatibility matrix is green.
3. For JGit updates, confirm that the supported matrix in this document and the workflow still matches the support policy.
4. For Hibernate, Hibernate Search or Lucene updates, consider running the optional linkage profile.
5. Do not merge JGit major-version jumps without an explicit compatibility review.
