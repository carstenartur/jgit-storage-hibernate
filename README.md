# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![JMH Performance](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/performance.json)](docs/badges/performance.json)
[![Release](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/release.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/release.yml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)
[![Citation: CFF](https://img.shields.io/badge/Citation-CFF-blue)](CITATION.cff)

Hibernate-backed storage backend for JGit repositories.

## Modules

- `jgit-storage-hibernate-core`: database-backed JGit repository storage.
- `jgit-storage-hibernate-search`: optional Hibernate Search history projections.
- `jgit-storage-hibernate-benchmarks`: JMH benchmarks for core storage operations.

## Documentation

- [Consuming](docs/consuming.md)
- [Benchmarks](docs/benchmarks.md)
- [Release process](docs/release-process.md)
- [Citation metadata](CITATION.cff)

## License

BSD-3-Clause. See [LICENSE](LICENSE).
