# Reverse-reflog performance

Queryable reflogs are stored in `git_reflog` and served newest-first. A normal JGit request for the latest entry or the latest bounded history uses the logical shape:

```sql
where repository_name = :repository
  and ref_name = :ref
order by id desc
fetch first :limit rows only
```

A repository can contain many long-lived refs and hundreds of thousands of reflog rows. An index that covers only repository and identity can still inspect rows belonging to unrelated refs before satisfying a small result limit.

## Portable indexed reference key

Core stores the complete nationalized Git ref name and a second bounded lookup key:

```text
ref_name      complete ref, up to 1,024 characters
ref_name_key  first 128 characters
```

The production reader applies both predicates:

```sql
where repository_name = :repository
  and ref_name_key = :refKey
  and ref_name = :ref
order by id desc
```

The bounded prefix is not used as an identity and does not weaken correctness. Two unusually long refs can share the same 128-character prefix; the complete `ref_name` residual predicate still separates them. An integration test writes two such refs and verifies that each reader returns only its own entry.

The physical access path is:

```text
(repository_name, ref_name_key, id descending)
```

SQL Server includes the complete `ref_name` after the bounded key columns. The 128-character nationalized prefix keeps repository + ref key + identity within conservative SQL Server nonclustered-index key limits. PostgreSQL, H2 and HSQLDB use the same logical layout so query behavior and diagnostics remain portable.

## Migration behavior

The 0.9.1 Core migrations:

1. add `ref_name_key`;
2. backfill it from the complete existing ref name;
3. make it non-null;
4. replace the earlier reverse-reflog index with the selective repository/ref-key/identity index.

Existing reflog identities, messages, timestamps and ordering are unchanged. Writers derive the key whenever `ref_name` is assigned; callers do not configure it.

Pre-library adoption tests explicitly remove the new column and index while recreating the historical schema. This prevents a current Entity model from accidentally making an old-schema fixture look newer than the Flyway baseline.

## Retained database comparison

The dedicated `Reflog Performance` workflow starts PostgreSQL 17.10 and SQL Server 2022 and compares:

```text
legacy:     (repository_name, id descending)
selective:  (repository_name, ref_name_key, id descending)
```

The deterministic smoke matrix contains 10,000 rows distributed over 100 refs and measures:

- the latest entry for one ref;
- the latest 100 entries for one ref;
- elapsed time;
- ORM query/entity work;
- prepared statements and transactions;
- JMH allocation and garbage collection.

The workflow retains raw JMH JSON, output, converter input and the complete grouped chart artifact. Only a successful `main` run publishes history.

## Public charts

- [Latest reflog entry](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-reflog-latest-entry)
- [Latest 100 reflog entries](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-reflog-last-100-entries)

PostgreSQL and SQL Server appear as separate series inside each operation, with legacy and selective indexes directly comparable. Larger scheduled/manual profiles can extend row and ref cardinality without changing the chart identifiers.

## Interpretation boundaries

- The prefix removes most unrelated-ref candidates but cannot distinguish refs that share all 128 indexed characters until the complete residual comparison.
- A small repository with one or two refs may show no useful latency change; the additional column and index still have write/storage cost.
- The retained smoke matrix is a regression and direction-finding fixture, not an absolute capacity limit.
- Database-native logical-read, plan and WAL/log evidence belongs to the database telemetry work tracked separately.
