# jgit-storage-hibernate Testcontainers

This module starts the same OCI server image used by Docker deployments together with PostgreSQL.
It is intentionally more than a generic Git test server: tests can use normal clone/fetch/push,
wait for the searchable projection and inspect the relational evidence through JDBC.

The no-argument environment is pinned to the documented release image:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2
```

Add the test dependency from the public Maven repository:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-testcontainers</artifactId>
  <version>0.11.2</version>
  <scope>test</scope>
</dependency>
```

Then use the production server and PostgreSQL as one JUnit lifecycle:

```java
@Container
static final JgitStorageEnvironment git = new JgitStorageEnvironment();

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

Stable suites should use the default or another explicit numeric image tag. To test a deliberately
selected image, pass it explicitly:

```java
new JgitStorageEnvironment(
    "ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2");
```

`latest` and `edge` are moving compatibility aliases for the newest published release and are not
recommended for reproducible CI. The Testcontainers artifact and server image are released from one
Maven reactor; using the same numeric version keeps helper APIs and server endpoints aligned.
