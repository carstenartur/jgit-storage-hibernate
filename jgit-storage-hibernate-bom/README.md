# jgit-storage-hibernate BOM

The BOM aligns the independently selectable production modules without adding a runtime dependency, database driver, Hibernate Search backend or application framework by itself.

Import the BOM once in `dependencyManagement`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-bom</artifactId>
      <version>0.10.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

After the import, declare only the capabilities the application uses and omit their versions. The managed production coordinates are:

- `io.github.carstenartur:jgit-storage-hibernate-core`
- `io.github.carstenartur:jgit-storage-hibernate-security`
- `io.github.carstenartur:jgit-storage-hibernate-search`
- `io.github.carstenartur:jgit-storage-hibernate-java-analysis`
- `io.github.carstenartur:jgit-storage-hibernate-architecture`

## Capability selection

Use `jgit-storage-hibernate-core` for packs, objects, refs, reflogs and transactions without indexed history. The application still selects its JDBC driver explicitly.

Add `jgit-storage-hibernate-security` for the optional framework-neutral principal/group ACL schema and deterministic repository/ref evaluator. It depends on Core and Hibernate ORM, not Hibernate Search, Spring or Servlet APIs.

Add `jgit-storage-hibernate-search` when the application deliberately maintains a derived Hibernate Search projection, for example searchable commit and path history. The application selects the Search backend or directory strategy appropriate to its deployment. Git persistence remains authoritative when optional Search projection work fails.

Add `jgit-storage-hibernate-java-analysis` for Java and Eclipse-aware history analysis. Add `jgit-storage-hibernate-architecture` only when architecture-level projections or queries are required.

Importing the BOM does not imply Hibernate Search, Spring Boot, Tycho or OSGi dependencies beyond those exposed by the explicitly selected modules.

`jgit-storage-hibernate-benchmarks` is intentionally not managed or recommended as a consumer dependency. It is development evidence, not a production capability.
