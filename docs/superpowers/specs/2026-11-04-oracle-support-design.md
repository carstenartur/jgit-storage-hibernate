# Oracle support and 0.12.0 release

## Intent

Make Microsoft SQL Server and Oracle explicit, verifiable supported backends for
the Core, Search and Security library modules. Publish an immutable release using
the existing release process. SQL Server already has migrations and database
tests; Oracle needs both. The latest release is 0.11.8, so the next feature release
is 0.12.0, not the numerically lower 0.9.0.

## Approach

Extend the existing module-owned Flyway streams and normal Maven verification.
Each module gets an Oracle initial migration at 0.12.0 and a public
`ORACLE_LOCATION` constant. Tests use a pinned real Oracle Free image and validate
Hibernate against the migrated schema. Keep JDBC drivers and migration tooling
out of the Core/Search/Security runtime dependencies, following existing scope.
Spring Boot supplies the Flyway database adapter and selects Oracle automatically;
the application continues to supply its JDBC driver.

This is an implementation decision based on the existing project architecture.
Merely documenting OracleDialect would not meet the requested support contract.
A separate Oracle storage implementation would duplicate the existing one.

## Scope and boundaries

- Official Oracle coverage starts with fresh installations at 0.12.0, using the
  Oracle Free 23.26.3 pinned as `gvenzl/oracle-free:23.26.3-slim-faststart`,
  with AL32UTF8/AL16UTF16. The stream uses native BOOLEAN; older Oracle releases
  are not qualified. Do not imply coverage for
  untested older Oracle releases or editions.
- Cover migrations, Hibernate validation, repository writes/reads/reopening,
  transfer/deletion isolation, ref and reflog behavior, relational/Lucene Search,
  and durable Security policy/credentials/audit as applicable to each module.
- Test database-specific risks: character/LOB lengths, empty-string semantics,
  timestamps, identifier generation, index widths and row locking.
- Preserve existing migration bytes and existing backend behavior. There is no
  implicit adoption path for an arbitrary pre-existing Oracle schema.
- Core/Search bootstrapping supports Oracle. Security schema management stays an
  application/deployment concern. The published standalone server image retains
  its PostgreSQL deployment contract.
- Consumers inherit only the backends exposed and tested by their own integration
  and dependency version. This release does not certify Oracle in every consumer.
- Retain one ordinary `mvn -B -q verify` build locally and in CI. Container tests
  may be skipped when Docker is absent locally; release evidence must come from
  an actual Docker-enabled run, with Oracle test results retained and checked.

## Release acceptance

Review the complete change, obtain green project and real-consumer compatibility
checks, merge through the repository's normal protection rules, then use the
documented `release-request/0.12.0` workflow. Verify public Maven artifacts,
GitHub release/tag and server image publication. Restore the next snapshot via
the generated development-version PR. Never replace existing release artifacts.

## Oracle portability decisions

Use a dedicated `JgitOracleDialect` in the provider to cap nationalized scalar
capacity at 2000 and map longer Search values to NCLOB without altering existing
backend mappings. Preserve custom application dialect/resolver choices. Normalize
null/empty persisted reflog comparison only on dialects that collapse empty
strings. Security managed-key unique indexes use conditional all-null keys for
unmanaged rows. These decisions follow concrete source audits and receive
regression coverage.
