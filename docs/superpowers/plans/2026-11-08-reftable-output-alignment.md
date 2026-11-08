# Preserve reftable output configuration

**Goal:** Stop the physical pack-chunk alignment hint from overriding JGit's
logical reftable block size and alignment configuration.

**Evidence:** [Original-runtime GC diagnostic](../../evidence/publication-gc-diagnostic-2026-11-08/README.md).
The OOM and allocation-pressure evidence motivated this inspection. The fix is
also independently justified by a reproduced configuration-semantic defect; it
does not claim to eliminate every possible OOM.

## Scope and constraints

- Only `PackExt.REFTABLE` output omits the one-MiB physical alignment hint.
- Pack and all other extension output, read channels, physical storage chunks,
  publication policy/default, heap and benchmark budgets stay unchanged.
- Existing archived studies keep their exact original source and raw outcomes.
- Counterfactual diagnosis and later qualification, if run, have separate
  immutable source identities and retained outcomes.

## Implementation and checks

- [x] Run real production `writeFile` through JGit's actual
  `DfsPackCompactor.configureReftable`, writer and reader before changing code.
  Default 4,096-byte and explicit 8,192-byte header assertions failed with
  observed 1,048,576; the unchanged non-REFTABLE alignment/read test passed.
- [x] Return the ordinary local staging stream only for REFTABLE. No public API
  or transaction behavior changes.
- [x] Recompile all core sources with `javac --release 21` against cached pinned
  JGit 7.8/Hibernate 7.4 and run four actual JUnit tests. Default/configured
  headers, 512 exact refs per table, unaligned layout and all other extension
  alignment/positional reads pass. These are correctness tests, not timings.
- [ ] Require hosted Maven/full compatibility and database checks before merge.
- [ ] Retain a separate four-fork corrected-source diagnostic; assess whether a
  newly declared five-cell qualification is warranted after the result.

**Test ruling:** JGit's `ReftableWriter.encodeHeader` intentionally writes zero
for an unaligned table. The unaligned case checks that zero header, the requested
8,192-byte writer block statistic, zero padding and every ref. Aligned default
and explicit-size cases separately check literal 4,096/8,192-byte headers.

**Local limitation:** The Maven reactor cannot resolve uncached Jackson/Spring
BOMs in this environment. Direct compilation and JUnit execution prove the
focused behavior, while the hosted reactor remains the integration gate.
