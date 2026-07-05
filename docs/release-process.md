# Release process

This project uses GitHub Packages as the first Maven publication target.

## Artifacts

The release publishes:

- `io.github.carstenartur:jgit-storage-hibernate-parent`
- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-search`
- `io.github.carstenartur:jgit-storage-hibernate-benchmarks`

The benchmark artifact is for CI and release review. Runtime consumers should normally depend only on `jgit-storage-hibernate-core` and optionally `jgit-storage-hibernate-search`.

The GitHub Release also contains built JARs and metadata files.

## Snapshot publishing

Snapshots are published by `.github/workflows/publish-snapshot.yml`.

## Manual release workflow

Use `.github/workflows/release.yml` from the `main` branch.

Required input:

```text
release_version = 0.1.0
```

The workflow validates the current snapshot version, sets the release version, updates citation metadata, runs `mvn verify`, deploys to GitHub Packages, creates a tag and GitHub Release, then bumps to the next development snapshot.

## Benchmarks

JMH benchmarks live in `jgit-storage-hibernate-benchmarks` and are run by `.github/workflows/performance.yml`.

## DOI and Zenodo

The repository includes `.zenodo.json` and `CITATION.cff` so Zenodo can mint a DOI for GitHub Releases.

A DOI cannot be filled in before a DOI provider has minted it. After the first Zenodo archive exists, add the DOI to the citation metadata in a follow-up PR.
