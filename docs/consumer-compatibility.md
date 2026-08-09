# Real-consumer compatibility gates

The library validates candidate changes against three real downstream applications before treating API, schema, packaging or transitive-dependency changes as safe.

## Why the contracts are consumer-owned

`jgit-storage-hibernate` owns candidate construction and reproducible version substitution. It does **not** duplicate each application's behavioral test knowledge.

Each downstream repository owns `.github/jgit-storage-hibernate-contract.sh`. That script defines the application-specific contract and writes retained evidence to `target/jgit-storage-hibernate-contract/`.

The central workflow therefore has a narrow responsibility:

1. install the exact library commit under test into an isolated Maven repository;
2. check out an immutable downstream commit;
3. discover the downstream's actual `io.github.carstenartur:jgit-storage-hibernate-*` Maven dependencies;
4. change only their literal/property-backed version selector to the candidate version;
5. retain the POM diff and substitution report;
6. run the downstream-owned contract in `candidate` mode against the isolated Maven repository;
7. retain the consumer result, dependency tree and Maven test reports.

A baseline run executes the same consumer scripts without candidate substitution. Pull-request and `main`-push baselines use the immutable pinned commits, so candidate and baseline evidence remains reproducible. The weekly schedule instead checks out each declared default branch to detect consumer-side drift that has not yet been incorporated into the pins.

## Pinned consumers

The exact commits live in `.github/consumer-compatibility.json` and are intentionally immutable during one library revision.

| Consumer | Library modules expected by the central gate | Consumer-owned authority |
|---|---|---|
| audio-analyzer | Core + Search | `audio-app` reactor verification, workflow-history/runtime linkage and packaged-JAR leakage checks |
| Taxonomy | Core | Taxonomy's catalogue-driven schema/migration/Hibernate Search/PostgreSQL contract |
| sandbox | Core | sandbox storage lifecycle/adoption/OSGi/packaging contract |

The table describes what the **current pinned consumer POMs actually consume**. It must not be used to speculate that a consumer uses another optional module. If a consumer adds Search, Java Analysis or Architecture later, the version-substitution report changes and the descriptor must be updated explicitly.

## Candidate provenance

Remote snapshots are never used. Candidate artifacts come from `mvn install` of the same GitHub Actions checkout that triggered the compatibility workflow. Only the generated `io/github/carstenartur` Maven subtree is passed to downstream jobs; third-party dependencies continue to resolve normally.

The consumer job sets `maven.repo.local` to a fresh directory containing that subtree. The consumer contract then verifies that its resolved dependency tree contains the exact candidate version.

## Version substitution safety

`.github/scripts/patch-consumer-candidate.py` operates only on Maven `pom.xml` files. It recognizes project artifacts whose group is exactly `io.github.carstenartur` and whose artifact ID starts with `jgit-storage-hibernate-`.

Supported forms are:

- a literal dependency or dependency-management `<version>`;
- a `${property}` version whose property has one unambiguous value in the checkout.

The workflow fails if:

- the consumer declares no library dependency;
- a version property cannot be resolved or has conflicting values;
- the discovered module set differs from the pinned descriptor;
- substitution changes a non-POM file;
- the consumer-owned contract does not produce its result evidence;
- the resolved consumer dependency tree does not contain the candidate version.

This makes accidental broad XML rewrites and false-green baseline resolution visible in the retained artifact.

## Reporting and retained evidence

Every matrix leg retains the exact checked-out consumer commit, dependency tree, contract result, Maven test reports and elapsed contract time. Candidate legs additionally retain the original version selector, substituted candidate version, changed-file proof and POM diff.

The final `Summarize real-consumer evidence` job downloads those artifacts and writes one workflow table containing, per consumer:

- checked-out commit;
- selected modules;
- original and substituted library versions;
- candidate and baseline result plus duration;
- the consumer-owned contract description;
- a link to the retained workflow artifacts and logs.

A failed or incomplete artifact is reported as such; the summarizer never infers success from the absence of evidence.

## When the matrix runs

Candidate checks run for pull requests and `main` pushes that change runtime source, Maven descriptors or the compatibility tooling. Documentation-only edits do not start three downstream candidate builds.

For pull requests and `main` pushes, the baseline uses immutable refs from `.github/consumer-compatibility.json`. The bounded weekly schedule uses `defaultBranch` instead, making consumer-side API, dependency, packaging and test drift visible before the next pin update. Updating an immutable pin remains a reviewable library change: first verify the new consumer baseline, then advance the descriptor.

## Relationship to performance evidence

A consumer being compatible does not imply every benchmark is relevant to it. Performance-dashboard consumer relevance is a separate metadata layer: it is derived from the modules and contract capabilities proven here, not guessed from repository names. For example, Hibernate Search history-query charts must not be labelled as sandbox evidence while the pinned sandbox contract consumes Core only.
