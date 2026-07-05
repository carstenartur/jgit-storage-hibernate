# Usage

## Core storage

Applications that only need database-backed Git semantics should depend on:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The public entry point is `HibernateRepositories`:

```java
Properties properties = new Properties();
properties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
properties.setProperty("hibernate.connection.url", "jdbc:h2:mem:jgit;DB_CLOSE_DELAY=-1");
properties.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
properties.setProperty("hibernate.hbm2ddl.auto", "update");

try (SessionFactory sessionFactory = HibernateGitStorageConfiguration.buildSessionFactory(properties);
     HibernateGitStorage storage = HibernateRepositories.open(sessionFactory, new RepositoryName("my-repo"))) {
  Repository repository = storage.repository();
  // Use public JGit APIs here.
}
```

Consuming projects should not import implementation packages and must not import `org.eclipse.jgit.internal.*`.

## Optional search

Applications that need searchable Git history can add:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The search module registers optional projection entities for commits, blobs and paths. It is separate from core so that applications that only need storage do not pull the full search stack.
