# Consuming jgit-storage-hibernate

This project publishes a parent POM plus two consumer-facing Maven artifacts:

```text
io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-search
```

Use `jgit-storage-hibernate-core` when an application only needs database-backed JGit repositories. Add `jgit-storage-hibernate-search` only when the application also needs generic Hibernate Search/Lucene history indexing.

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
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Optional search dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Minimal repository setup

```java
Properties properties = new Properties();
properties.put("hibernate.connection.url", "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
properties.put("hibernate.connection.driver_class", "org.h2.Driver");
properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
properties.put("hibernate.hbm2ddl.auto", "update");

try (HibernateSessionFactoryProvider provider = new HibernateSessionFactoryProvider(properties)) {
    HibernateRepositoryFactory factory =
        new DefaultHibernateRepositoryFactory(provider.getSessionFactory());

    try (HibernateGitStorage storage = factory.open(new RepositoryName("demo"))) {
        Repository repository = storage.repository();
        // Use standard JGit APIs here.
    }
}
```

## Search setup

Search projections require the additional search entities when creating the `SessionFactory`.

```java
try (HibernateSessionFactoryProvider provider =
         new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses())) {
    // open repository and use CommitIndexer / GitHistorySearchService
}
```

## Recommended first integration in audio-analyzer

For the first `audio-analyzer` integration, depend only on `jgit-storage-hibernate-core`.

Recommended first slice:

```text
audio-analyzer
  -> add dependency on jgit-storage-hibernate-core
  -> create an audio-workflow-persistence module or package
  -> serialize one minimal workflow to a Git blob
  -> commit it through standard JGit APIs
  -> reopen the repository and read the blob back
```

Do not depend on `jgit-storage-hibernate-search` until the core workflow persistence slice works.
