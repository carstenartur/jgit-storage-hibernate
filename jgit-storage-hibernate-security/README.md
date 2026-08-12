# Security capability

`jgit-storage-hibernate-security` is the optional, framework-neutral security capability described by
[ADR-0003](../docs/adrs/0003-optional-principal-bound-security.md).

This first implementation phase provides:

- immutable, explicitly propagated `GitAccessContext` values;
- Git-generic repository permissions;
- deterministic principal/group grant and protected-ref evaluation;
- module-owned Hibernate entities for principals, groups, memberships, grants, ref rules and security versions;
- independent Flyway migrations for H2, HSQLDB, PostgreSQL and Microsoft SQL Server.

The evaluator fails closed. Repository grants are a prerequisite for access, explicit deny grants win
over allows, and the highest-precedence matching ref rule can further allow or deny a ref mutation.
Rule precedence is higher numeric priority, then more literal pattern characters, then stable rule ID.
Grant and ref-rule IDs are globally unique within one evaluator snapshot so every emitted evidence ID
identifies exactly one policy entry. Principal, group, grant and rule IDs are limited to 128 characters,
matching the Hibernate mappings and database migrations.
Glob semantics are independent of database collation: `*` matches within one ref segment, `**` crosses
`/`, and `?` matches one non-`/` character.

This module deliberately has no Hibernate Search, Spring, Servlet or HTTP-server dependency. Direct
JGit enforcement, audit and local credential/token services, and Smart HTTP integration are later
stages of issue #233. Until the Core enforcement SPI is added, code holding the raw
`HibernateRepositoryFactory` or an unrestricted JGit `Repository` remains privileged infrastructure.
