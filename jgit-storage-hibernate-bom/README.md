# jgit-storage-hibernate BOM

The BOM aligns independently selectable production modules without adding a runtime dependency,
database driver, Hibernate Search backend or application framework by itself.

The documented release line is **0.11.0**. Its public BOM aligns Core, Security, Smart HTTP, Search,
Java Analysis and Architecture without adding runtime capabilities by itself.

Import the current public BOM once in `dependencyManagement`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-bom</artifactId>
      <version>0.11.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

After the import, declare only capabilities contained in that release and omit their versions. The
published production coordinates are:

- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-security`
- `io.github.carstenartur:jgit-storage-hibernate-smart-http`
- `io.github.carstenartur:jgit-storage-hibernate-search`
- `io.github.carstenartur:jgit-storage-hibernate-java-analysis`
- `io.github.carstenartur:jgit-storage-hibernate-architecture`

## Capability selection

Use `jgit-storage-hibernate-core` for packs, objects, refs, reflogs and transactions without indexed
history. The application still selects its JDBC driver explicitly.

Add `jgit-storage-hibernate-security` from the public release for the optional framework-neutral
principal/group ACL schema, local credentials/tokens, audit and deterministic repository/ref
evaluator. It depends on Core and Hibernate ORM, not Hibernate Search, Spring or Servlet APIs.

Add `jgit-storage-hibernate-smart-http` only in the server application that exposes authenticated
clone, fetch and push through JGit Smart HTTP. It depends on Core and owns the optional JGit
HTTP/Servlet boundary; selecting Security alone does not select it.

Add `jgit-storage-hibernate-search` when the application deliberately maintains a derived Hibernate
Search projection, for example searchable commit and path history. The application selects the Search
backend or directory strategy appropriate to its deployment. Git persistence remains authoritative
when optional Search projection work fails.

Add `jgit-storage-hibernate-java-analysis` for Java and Eclipse-aware history analysis. Add
`jgit-storage-hibernate-architecture` only when architecture-level projections or queries are
required.

Importing the BOM does not imply Hibernate Search, Servlet, Spring Boot, Tycho or OSGi dependencies
beyond those exposed by explicitly selected modules.

`jgit-storage-hibernate-benchmarks` is intentionally not managed or recommended as a consumer
dependency. It is development evidence, not a production capability.
