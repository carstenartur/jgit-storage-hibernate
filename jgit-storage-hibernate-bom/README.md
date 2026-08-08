# jgit-storage-hibernate BOM

The BOM aligns versions of the independently selectable production modules. Importing it does not add a runtime dependency, database driver, Hibernate Search backend or application framework.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-bom</artifactId>
      <version>${jgit-storage-hibernate.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Declare only the capabilities the application uses.

## Minimal database-backed Git storage

Suitable for an embedding consumer that needs packs, objects, refs, reflogs and transactions without indexed history:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
</dependency>
```

The application selects its JDBC driver explicitly.

## Transactional searchable history

Suitable for applications such as Taxonomy that deliberately maintain a derived Hibernate Search projection:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
</dependency>
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
</dependency>
```

The application still selects the database driver and Search backend/directory strategy appropriate to its deployment. Git persistence remains authoritative when optional Search projection work fails.

## Java tooling and architecture analysis

Java/Eclipse tooling declares only the analysis layers it actually uses:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
</dependency>
```

Add `jgit-storage-hibernate-architecture` only when architecture-level projections or queries are required. Importing the BOM does not imply Hibernate Search, Spring Boot, Tycho or OSGi dependencies beyond those exposed by the explicitly selected module.

## Managed modules

- `jgit-storage-hibernate-core`
- `jgit-storage-hibernate-search`
- `jgit-storage-hibernate-java-analysis`
- `jgit-storage-hibernate-architecture`

`jgit-storage-hibernate-benchmarks` is intentionally not managed or recommended as a consumer dependency. It is development evidence, not a production capability.
