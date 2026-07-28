# API compatibility and quality gates

## Supported compatibility surface

The `0.1.x` line is pre-1.0, but released consumer APIs are not changed silently. Every runtime artifact is compared with the documented released baseline (`0.1.13` while developing `0.1.14`).

The supported compatibility surface includes public classes, constructors, methods and fields unless one of the following applies:

- the class or its package is annotated with `@InternalApi`;
- it belongs to an explicitly incubating persistence package documented as application-owned in `0.1.x`;
- it is a demonstration package rather than a consumer contract.

The current explicit compatibility exclusions are:

```text
io.github.carstenartur.jgit.storage.hibernate.objects
io.github.carstenartur.jgit.storage.hibernate.refs
io.github.carstenartur.jgit.storage.hibernate.repository
io.github.carstenartur.jgit.storage.hibernate.javaanalysis.internal
io.github.carstenartur.jgit.storage.hibernate.javaanalysis.demo
io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity
io.github.carstenartur.jgit.storage.hibernate.architecture.entity
```

Exclusion is not permission to make arbitrary changes. Internal and incubating packages remain covered by compilation, tests, packaged-JAR linkage verification and relevant schema tests. They are excluded only from the promise that code compiled against the previous release continues to compile and link unchanged.

## Binary and source compatibility

`japicmp` compares each packaged runtime artifact with the same artifact from the released baseline in the anonymous public Maven repository.

The build fails on:

- binary-incompatible changes;
- source-incompatible changes;
- an unavailable old baseline;
- missing classes required while analyzing either artifact.

Intentional incompatible changes require all of the following in one pull request:

1. a written rationale and migration path;
2. a release-note entry;
3. an explicit compatibility exclusion or reviewed plugin override limited to the affected API;
4. tests for the replacement contract.

Changing the baseline version merely to make a failure disappear is not an acceptable fix.

## Packaged-JAR linkage verification

The old Google Cloud Linkage Checker Maven rule could not load under the repository's current Maven/Aether runtime. It has been replaced rather than left as a dormant profile.

The last reactor module opens the actual packaged JARs for Core, Search, Java Analysis and Architecture. It constructs an isolated class loader with those JARs first on the class path, then:

- loads every class listed in every packaged JAR;
- verifies the class was loaded from that JAR rather than `target/classes`;
- resolves superclasses, interfaces, constructors, methods, fields, generic bounds, exceptions, records and annotations;
- fails on `LinkageError` or `TypeNotPresentException`;
- recursively checks supported public/protected signatures for `org.eclipse.jgit.internal.*` types.

This catches the classpath/linkage class of failure that dependency convergence alone cannot prove, while remaining reproducible through the normal reactor build.

## Javadoc gate

Javadoc is built with warnings treated as errors and doclint enabled. The blanket `missing` category is excluded so the gate focuses on malformed markup, invalid references, inaccessible symbols and other correctness problems rather than requiring prose on every bean accessor.

Internal DFS/Reftable adapter and demonstration packages are excluded from the published consumer Javadoc. Supported public APIs remain subject to doclint.

## Coverage floors

JaCoCo line and branch floors are enforced independently for each runtime module. Benchmarks are excluded because benchmark harness code is exercised through JMH rather than unit-test coverage.

| Module | Line floor | Branch floor | Measured when introduced |
|---|---:|---:|---:|
| Core | 84% | 64% | 84.4% / 64.7% |
| Search | 85% | 75% | 85.6% / 76.0% |
| Java Analysis | 81% | 64% | 81.7% / 64.7% |
| Architecture | 92% | 65% | 92.4% / 65.5% |

The Architecture figures exclude `architecture.entity`. Those classes are the explicitly incubating, application-owned persistence mapping made up primarily of accessor boilerplate; supported parsing, mapping, drift and semantic-diff code remains inside the gate.

The floors are deliberately just below the measured values. Their purpose is to stop unexplained regression, not to reward shallow tests written only to increase a percentage. Raising a floor is encouraged when meaningful tests increase sustained coverage; lowering one requires a documented reason in the pull request.

The Maven workflow uploads every module's `jacoco.xml` and `jacoco.csv`, including when a gate fails, so changes can be diagnosed from evidence rather than by repeatedly weakening a threshold.

## Local reproduction

The complete gate is part of the standard build:

```bash
mvn verify
```

With Docker available, the same command also executes PostgreSQL/Testcontainers coverage. Without Docker, the PostgreSQL classes are disabled while H2, HSQLDB, API compatibility, Javadoc, packaged-JAR linkage and the remaining quality gates still run.
