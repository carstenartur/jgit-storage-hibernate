# Oracle support implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement and review the tasks below.

**Goal:** Qualify Oracle alongside SQL Server and publish release 0.12.0.

**Architecture:** Add Oracle to the existing module-owned Flyway migrations and
database contract tests. Extend the Spring Boot database selector and retain the
existing release automation and consumer checks.

**Tech stack:** Java 21, Maven, Hibernate ORM 7.4.12.Final, Flyway 13.11.1,
Testcontainers 2.0.5, Oracle JDBC and Oracle Free.

**Spec:** `docs/superpowers/specs/2026-11-04-oracle-support-design.md`

## Global constraints

- Existing migrations must remain unchanged.
- The three library modules expose `ORACLE_LOCATION` with
  `classpath:db/migration/jgit-storage-hibernate/<module>/oracle`.
- Oracle initial migration filenames use `V0_12_0__create_<module>_schema.sql`.
- Root owns shared dependency versions, Spring Boot integration, documentation,
  version changes, CI evidence and release. Module tasks own their module files.
- Testcontainers must use a fixed Oracle image version, shared consistently by
  all new tests. No unverified claims for older versions or existing schemas.
- Runtime APIs remain compatible; database drivers remain application-owned.
- Release only after real Oracle/MSSQL tests and required checks pass.

## Review focus

1. Oracle maps empty strings to null: preserve consumer-visible values and ensure
   required columns accept valid empty Git metadata where the API allows it.
2. LOB and nationalized text types must match Hibernate and preserve Unicode.
3. Composite indexes must fit Oracle limits for the configured character set.
4. Database-generated IDs, booleans and timestamps must survive a new
   SessionFactory, not just one transaction or persistence context.
5. The normal CI and release run must execute the Oracle tests; skipped tests are
   not backend qualification evidence.

## Task 1: Core Oracle backend

**Files:** Core `pom.xml`, `schema/CoreSchemaMigrations.java`, new migration
`core/oracle/V0_12_0__create_core_schema.sql`, and Core Oracle integration tests.

**Interfaces:** Produce `CoreSchemaMigrations.ORACLE_LOCATION`; consume managed
Oracle JDBC, Flyway Oracle and Testcontainers Oracle dependencies from root.

- [ ] Write migration/validation/reopen and repository-transfer contract tests
  against Oracle, with Unicode reflog content, inline/chunk payloads, ref updates,
  separate SessionFactories, and deletion isolation.
- [ ] Check the missing-support failure when the available runtime permits it;
  record any local execution limitation explicitly.
- [ ] Add the initial migration and location constant, addressing only proven
  portability defects in shared Core code.
- [ ] Run Core tests and review the module diff against the five review risks.

## Task 2: Search and Security Oracle backends

**Files:** Search/Security POMs, schema classes, new respective Oracle initial
migrations and Oracle database integration tests.

**Interfaces:** Produce `SearchSchemaMigrations.ORACLE_LOCATION` and
`SecuritySchemaMigrations.ORACLE_LOCATION`; consume the shared dependency versions
and Core migration location.

- [ ] Write migration/validation/reopen tests and relational/Lucene query tests
  using existing database contracts where possible. Cover empty commit messages,
  distinct author/committer metadata, pagination and projection rebuild behavior.
- [ ] Write Security persistence tests for permissions, principal/group policy,
  credentials/token lifecycle, security version and audit using existing fixtures.
- [ ] Check missing-support failures or record execution limitations; implement
  the two initial migration streams and minimal portability corrections.
- [ ] Run the relevant modules and review the database-specific edge cases.

## Task 3: Integration and support documentation

**Files:** root and module POMs; Spring Boot schema manager and Oracle integration
test; canonical `docs/consuming.md`, linked module guides, operations guide,
release notes, and existing CI workflow if necessary to retain test evidence.

**Interfaces:** Consume the three public location constants; keep all three
module history tables independent. Release candidate reactor is 0.12.0-SNAPSHOT.

- [ ] Add managed dependencies for Flyway Oracle, Oracle JDBC and Oracle Free
  Testcontainers; ensure scopes match the existing backend dependencies.
- [ ] Test automatic Oracle Core/Search migration, repeated initialization,
  Hibernate validation and application context reopening against a real DB.
- [ ] Add Oracle detection/location selection and adapter dependency.
- [ ] Update the support matrix, migration instructions and limits consistently;
  retain explicit SQL Server support and consumer/server deployment boundaries.
- [ ] Advance reactor versions to 0.12.0-SNAPSHOT without rewriting metadata for
  the latest published release before the release workflow.
- [ ] Run normal verification, examine Oracle and SQL Server test reports, and
  retain the reports in existing CI artifacts. Review the complete diff.

## Task 4: Publish 0.12.0

- [ ] Create one implementation PR; resolve required checks and consumer failures.
- [ ] Merge the green reviewed implementation through normal branch protection.
- [ ] Create the documented release-request branch/marker from authoritative
  main and inspect the generated release PR before merging it.
- [ ] Verify immutable tag, GitHub release, anonymous Maven consumption and
  server image publication for both supported architectures.
- [ ] Merge the generated next-snapshot PR after its required checks pass and
  report the exact release and qualified database coverage.
