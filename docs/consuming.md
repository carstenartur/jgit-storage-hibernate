# Consuming jgit-storage-hibernate

This project publishes a parent POM plus two consumer-facing Maven artifacts:

```text
io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-search
```

Use `jgit-storage-hibernate-core` when an application only needs database-backed JGit repositories. Add `jgit-storage-hibernate-search` only when the application also needs generic Hibernate Search/Lucene history indexing.

The supported `0.1.4` line uses Hibernate ORM `7.4.5.Final` and Hibernate Search `8.4.0.Final`. Consumers should keep those versions aligned through the published artifacts instead of overriding only one side of the pair.

## GitHub Packages repository

Until the project is published to Maven Central, configure GitHub Packages as a Maven repository.

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/carstenartur/jgit-storage-hibernate</url>
  </repository>
</repositories>
```

For private package access, add credentials to `~/.m2/settings.xml`. Public packages may still require GitHub authentication depending on account/package settings.

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

The token needs at least `read:packages` for consuming and `write:packages` for publishing.

## Core dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.4</version>
</dependency>
```

## Optional search dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.4</version>
</dependency>
```

## Minimal repository setup

`HibernateSessionFactoryProvider` is a standalone convenience bootstrap. It always registers the core pack/reflog entities and can register additional application entities in the same Hibernate persistence context.

```java
Properties properties = new Properties();
properties.put("hibernate.connection.url", "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
properties.put("hibernate.connection.driver_class", "org.h2.Driver");
properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
properties.put("hibernate.hbm2ddl.auto", "update");

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, List.of(MyApplicationEntity.class))) {
  SessionFactory sessionFactory = provider.getSessionFactory();
  HibernateRepositoryFactory factory =
      new DefaultHibernateRepositoryFactory(sessionFactory);

  try (HibernateGitStorage storage = factory.open(new RepositoryName("demo"))) {
    Repository repository = storage.repository();
    // Use standard public JGit APIs here.
  }

  // MyApplicationEntity is persisted through the same SessionFactory and transaction manager.
}
```

Framework-managed applications may construct the `SessionFactory` / Jakarta `EntityManagerFactory` themselves and pass the native Hibernate `SessionFactory` directly to `DefaultHibernateRepositoryFactory`. Register the public core entity package together with application mappings, use one `DataSource`, and let the application own transaction and lifecycle management. The repository facade never requires downstream code to import `org.eclipse.jgit.internal.*`.

The core integration test `ApplicationManagedSessionFactoryIntegrationTest` verifies that a consumer entity and a Hibernate-backed JGit repository coexist and survive rebuilding the shared `SessionFactory` against the same database.

## Search setup

Search projections require the additional search entities when creating the `SessionFactory`.

```java
List<Class<?>> annotatedClasses = new ArrayList<>();
annotatedClasses.addAll(SearchEntities.annotatedClasses());
annotatedClasses.add(MyApplicationEntity.class);

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, annotatedClasses)) {
  // Open repositories and use CommitIndexer / GitHistorySearchService.
  // Application-specific projections can share this persistence context.
}
```

The search index is derived state. Git objects and application audit entities remain authoritative, and indexes must be rebuildable after deletion or corruption.

## Recommended integration in audio-analyzer

For the first `audio-analyzer` integration, depend on `jgit-storage-hibernate-core` and keep the existing `VersionedWorkflowStore` facade as the domain boundary.

```text
audio-analyzer
  -> create one application-managed Hibernate persistence context
  -> register JGit storage plus Audio Analyzer workflow entities
  -> open the repository through DefaultHibernateRepositoryFactory
  -> serialize one minimal workflow to workflow.dsl / workflow.id
  -> commit it through standard public JGit APIs
  -> close and rebuild the persistence context
  -> reopen the repository and read the workflow back
```

Add `jgit-storage-hibernate-search` only for generic Git-history indexing. Audio Analyzer should contribute only workflow-specific projection fields that are not already supplied by the generic commit/path/message/full-text index.
