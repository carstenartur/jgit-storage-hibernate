# Optional Maven Central preparation

## Intent

Prepare Maven Central publishing while the anonymous branch-backed Maven repository
remains the working default. The namespace is already verified and the maintainer
has a local Maven server entry. No credentials, production signing keys, uploads,
release tags or version changes are part of this preparation.

## Approach

Use the existing `public-repository-release` profile for the current channel and an
explicit, inactive `maven-central` profile for Central. Central defaults to a signed
local bundle, with upload requiring `-Dcentral.skipUpload=false`. Uploaded bundles
still require manual publication in the Portal. This follows the requested profile
choice; replacing the current release channel or coupling its success to Central
would undermine the working fallback.

## Contract

- Normal builds, GitHub snapshots and the existing release script require no GPG or
  Central account. Their publication behavior does not change.
- Central and another publishing profile cannot be combined. Central rejects
  snapshot projects/dependencies and signs the POMs and attached artifacts.
- The existing publication policy allows Central plugins only in the inactive
  profile and checks its no-upload/manual-publication defaults.
- Existing CI checks a signed multi-module bundle using an ephemeral signing key
  and empty Maven settings. It performs no Central publication or keyserver upload.
- Documentation distinguishes local preparation, upload, final Portal publication
  and the continued branch-backed release channel.

## Verification

Exercise policy violations and incomplete/corrupt bundles with regression tests;
run the existing release checks; verify the actual Maven bundle in CI. Local Maven
dependency resolution is unavailable in this session (the unchanged baseline fails
resolving imported BOMs), so report that limitation separately from passing checks.
