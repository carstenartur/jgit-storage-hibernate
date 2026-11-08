# Optional Maven Central Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the integration and a focused independent worker for bundle verification.

**Goal:** Make Central publishing explicitly selectable without affecting existing releases.

**Architecture:** An inactive Maven profile generates a signed bundle by default. The
existing release authority stays unchanged. Repository policy and CI verify profile
isolation and complete bundle contents without publication credentials.

**Tech Stack:** Maven, GnuPG, Python standard library, GitHub Actions.

**Spec:** ../specs/2026-11-08-optional-maven-central-design.md

## Global Constraints

- Keep the reactor version and existing release workflow/script unchanged.
- No live deployment, production keys, tokens or new release authority.
- Default Central upload is skipped; final publication remains manual.
- Keep the existing `public-repository-release` and `github-packages` profiles.

## Review Focus

- Central plugins accidentally active during an ordinary build or existing release.
- Both publishing profiles active together, or snapshot artifacts selected for Central.
- A local bundle unexpectedly requests credentials or contacts the publisher API.
- Parent/BOM, optional modules, signatures or checksums omitted from the bundle.
- CI test keys or credentials escaping temporary storage or entering uploaded artifacts.

## Task 1: Optional profile and publication policy

**Files:** `pom.xml`, `.github/scripts/verify-public-repository-publishing.py`,
`.github/scripts/test_verify_public_repository_publishing.py`.

- [ ] Exercise the policy against an allowed optional profile and unsafe mutations.
- [ ] Add profile-only signing/publishing, release guards and profile exclusion.
- [ ] Replace the blanket Central prohibition with scoped safety checks.
- [ ] Run policy regression tests and existing release consistency checks.

## Task 2: Bundle evidence and user instructions

**Files:** `.github/scripts/verify-central-bundle.py`,
`.github/scripts/test_verify_central_bundle.py`, `.github/workflows/maven.yml`,
`docs/maven-central.md`, `docs/release-process.md`, `docs/public-maven-repository.md`, `README.md`.

- [ ] Test incomplete/corrupt bundles, then implement the bundle verifier.
- [ ] Extend the existing CI job with ephemeral signing and no-upload bundle verification.
- [ ] Document the two profiles and the explicit upload switch, linking the guide.
- [ ] Run release/publication tests, review the complete diff independently and check CI.
