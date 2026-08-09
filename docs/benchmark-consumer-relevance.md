# Consumer relevance in benchmark history

The public benchmark dashboard keeps one retained history per stable library operation. It does not duplicate charts per downstream project and it does not claim that a library microbenchmark was executed inside a consumer application.

## Meaning of a relevance tag

A tag answers this narrower question:

> Can the measured library operation affect a capability that the pinned consumer contract actually selects?

Every newly published benchmark series may therefore retain:

```json
{
  "consumers": ["audio-analyzer"],
  "contract": "search-index-query-rebuild",
  "requiredModules": ["jgit-storage-hibernate-search"],
  "consumerEvidence": [
    {
      "id": "audio-analyzer",
      "ref": "<immutable consumer commit>",
      "modules": [
        "jgit-storage-hibernate-core",
        "jgit-storage-hibernate-search"
      ]
    }
  ]
}
```

The numeric value, unit, range, series name and operation anchor are unchanged. Filtering only hides or shows retained series; it never recalculates values.

## Evidence source

`.github/consumer-compatibility.json` is the authority for consumers, immutable commits and selected runtime modules. The same descriptor drives the [real-consumer compatibility gate](consumer-compatibility.md). `.github/benchmark-consumer-relevance.json` maps benchmark suites or individual comparison series to the module capability they exercise.

The publisher joins both files automatically when they are present in the checked-out repository. A known benchmark capability fails closed when no pinned consumer proves the required module. This prevents Search results from being attributed to a Core-only consumer merely because its repository name appears in documentation.

At the current pinned commits:

| Capability | Consumers proven by the compatibility contract |
|---|---|
| Core storage and repository operations | `audio-analyzer`, `taxonomy`, `sandbox` |
| Hibernate Search indexing, query and runtime behavior | `audio-analyzer` |
| Java Analysis | none in the current real-consumer contract |
| Architecture knowledge | none in the current real-consumer contract |

This table is derived from selected modules, not maintained as an independent promise. When a consumer changes its actual dependencies, the compatibility descriptor must first be advanced and verified; subsequent benchmark publications then derive the new tags automatically.

## Mixed comparison charts

The Git-history crossover suite contains different implementation families in the same stable operation chart:

- `FileRepository / JGit on demand` is retained as a comparison reference and is visible to every consumer filter;
- `HibernateRepository / JGit on demand` requires Core;
- indexed projection, `PostgreSQL compact projection`, Hibernate Search and `content-v1` series require Search.

Filtering a Core-only consumer can therefore leave the filesystem and Core-backed lines visible while hiding the Search projection. The chart anchor and historical measurements remain the same.

## Legacy and unknown data

Older history points have no relevance metadata. They remain visible when all filters are selected and under the explicit **Unclassified legacy data** filter. Unknown future suites also remain unclassified until a reviewed capability rule is added.

This preserves historical continuity without inventing retrospective consumer claims.
