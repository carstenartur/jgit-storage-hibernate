# Security capability

`jgit-storage-hibernate-security` is the optional, framework-neutral security capability described by
[ADR-0003](../docs/adrs/0003-optional-principal-bound-security.md).

It provides:

- immutable, explicitly propagated `GitAccessContext` values;
- Git-generic repository permissions;
- deterministic principal/group grant and protected-ref evaluation;
- module-owned Hibernate entities for principals, groups, memberships, grants, ref rules and security versions;
- independent Flyway migrations for H2, HSQLDB, PostgreSQL and Microsoft SQL Server;
- a database-backed adapter for Core's dependency-free repository access SPI;
- principal-bound repository sessions that enforce direct JGit ref mutations and repository deletion.

The evaluator fails closed. Repository grants are a prerequisite for access, explicit deny grants win
over allows, and the highest-precedence matching ref rule can further allow or deny a ref mutation.
Rule precedence is higher numeric priority, then more literal pattern characters, then stable rule ID.
Grant and ref-rule IDs are globally unique within one evaluator snapshot so every emitted evidence ID
identifies exactly one policy entry. Principal, group, grant and rule IDs are limited to 128 characters,
matching the Hibernate mappings and database migrations.
Glob semantics are independent of database collation: `*` matches within one ref segment, `**` crosses
`/`, and `?` matches one non-`/` character.

## Principal-bound direct JGit access

Use `SecuredHibernateRepositoryFactory` for application or request code that must not receive an
unrestricted repository factory. The database-backed policy reloads the current principal, active
group memberships, repository grants and enabled ref rules for every sensitive check. Group IDs in
the caller-supplied context are not trusted as authorization evidence.

```java
GitAccessContext accessContext =
    new GitAccessContext(
        "principal-123",
        Set.of(),
        "oidc",
        "session-456",
        "correlation-789",
        Map.of());

HibernateSecurityRepositoryAccessPolicy accessPolicy =
    new HibernateSecurityRepositoryAccessPolicy(sessionFactory);
SecuredHibernateRepositoryFactory<GitAccessContext> repositories =
    new SecuredHibernateRepositoryFactory<>(sessionFactory, accessPolicy);

try (AuthorizedRepositorySession<GitAccessContext> session =
    repositories.open(new RepositoryName("demo"), accessContext)) {
  Repository repository = session.repository();

  RefUpdate update = repository.updateRef("refs/heads/topic");
  update.setExpectedOldObjectId(oldObjectId);
  update.setNewObjectId(newObjectId);
  RefUpdate.Result result = update.update();
}
```

Opening checks `DISCOVER` and `READ` before repository existence is inspected. The returned repository
is guarded: direct `RefUpdate`, symbolic-ref updates and `BatchRefUpdate` commands are classified as
create, fast-forward update, force update or delete and are checked again while Core holds the
repository publication lock. A denied atomic batch publishes none of its commands. Repository deletion
uses a separate operation mapped to `ADMINISTER` and is rechecked after the database repository lock is
acquired:

```java
RepositoryDeletionResult result =
    repositories.deleteRepository(new RepositoryName("demo"), accessContext);
```

The initial `READ` decision is an open-session decision. Long-lived read handles are not automatically
revoked while they remain open, so applications must bound session lifetime and reopen when their
security policy requires prompt read revocation. Ref mutations and repository deletion do not inherit
that initial decision: they reload and re-evaluate current database policy at their publication
boundary.

The raw `HibernateRepositoryFactory`, repository creation, repository transfer and any repository
opened without an access guard remain explicitly privileged infrastructure capabilities. Do not inject
them into untrusted request or domain code. Persisting loose/unreachable Git objects does not publish a
branch or tag; authority-changing ref publication is enforced separately.

This module deliberately has no Hibernate Search, Spring, Servlet or HTTP-server dependency. Audit,
local credential/token services and Smart HTTP integration remain later stages of issue #233.
