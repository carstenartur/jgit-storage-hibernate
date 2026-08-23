# jgit-storage-hibernate Testcontainers

This module starts the same OCI server image used by Docker deployments together with PostgreSQL.
It is intentionally more than a generic Git test server: tests can use normal clone/fetch/push,
wait for the searchable projection and inspect the relational evidence through JDBC.

```java
@Container
static final JgitStorageEnvironment git =
    new JgitStorageEnvironment("ghcr.io/carstenartur/jgit-storage-hibernate-server:edge");

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

Pin an immutable server image tag in stable test suites. The `edge` default is intended for testing
the current main branch and examples.
