# jgit-storage-hibernate Testcontainers

This module starts the same OCI server image used by Docker deployments together with PostgreSQL. It is intentionally more than a generic Git test server: tests can use normal clone/fetch/push, wait for the searchable projection and inspect the relational evidence through JDBC.

Stable suites must pin an immutable full release tag or digest:

```java
@Container
static final JgitStorageEnvironment git =
    new JgitStorageEnvironment(
        "ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2");

@Test
void pushedHistoryIsQueryable() throws Exception {
  URI remote = git.createRepository("demo");
  // Push with the Git CLI or JGit using remote.
  git.awaitProjection("demo", Duration.ofSeconds(30));

  try (Connection connection = git.getInspectionDataSource().getConnection();
      PreparedStatement statement = connection.prepareStatement(
          "select count(*) from jsh_inspection.commit_change where repository_name = ?")) {
    statement.setString(1, "demo");
    try (ResultSet result = statement.executeQuery()) {
      result.next();
      assertTrue(result.getInt(1) > 0);
    }
  }
}
```

The no-argument container and examples that explicitly select `edge` follow the current `main` image. That is useful for compatibility testing but intentionally not reproducible. Use `X.Y.Z`, `vX.Y.Z` or a manifest digest for deterministic integration tests.

The Testcontainers helper exercises the standard Git Smart HTTP endpoint; it does not imply GitLab, Gitea or another vendor REST/API compatibility. See the [standalone server image guide](../docs/operations/server-image.md) for the complete compatibility and tag contract.
