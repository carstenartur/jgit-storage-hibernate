# Storage metrics configuration

Repository transaction and lock counters are disabled by default. Enable them only for diagnostics or benchmarking:

```properties
jgit.storage.hibernate.metrics.enabled=true
```

Hibernate query, statement, transaction and connection statistics are controlled separately:

```properties
hibernate.generate_statistics=true
```

See [Protocol storage metrics](protocol-storage-metrics.md) for counter semantics and interpretation guidance.
