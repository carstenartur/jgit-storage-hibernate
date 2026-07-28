# Performance implementation notes

The adaptive pack writer assumes the `DfsOutputStream` contract used by JGit's pack writers is append-only between flushes. It therefore tracks the previously persisted length and, for chunked files, rewrites at most the previous partial chunk before appending new chunks.

The inline threshold is intentionally an implementation constant rather than a public configuration option in the first release. The benchmark history should establish whether 256 KiB is appropriate before a stable consumer-facing property is introduced.

The HikariCP configuration is benchmark-only. Applications should normally provide their framework-managed `DataSource` or connection pool to Hibernate rather than rely on library-owned production pool defaults.
