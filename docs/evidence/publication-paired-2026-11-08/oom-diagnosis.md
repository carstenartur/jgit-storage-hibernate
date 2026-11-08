# Issue 161: independent read-only OOM diagnosis

Date: 2026-11-08. No Java workload, timing study, source modification, heap change
or stopping-rule change was performed for this review.

## Conclusion

The failure is an observed **GCLocker allocation-retry exhaustion at roughly
one-MiB JGit byte-buffer allocations under high allocation pressure**. Concurrent
JGit compression reaches JNI critical regions in the exact JDK used, providing a
concrete compatible mechanism. The retained evidence does **not** yet establish
whether the heap contained enough reclaimable objects to avoid the OOM, nor
whether increasing retained state contributed. Calling this a proved memory
leak, a proved harmless JVM false OOM, or an adaptive-policy regression would
exceed the evidence.

The failed cell remains incomplete. Its three complete pairs and failed fourth
fork must remain attached to their original source and must not be replaced by
diagnostic successes. There is no valid non-regression acceptance result for this
cell under its preregistered 24/96-pair protocol.

## Exact evidence inspected

- [Publication run 42431132779](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42431132779),
  failed coordinate `independent-16w-0ms`, artifact `13598025586`.
- Actual source `5ed8aad0f0691bcecb414b319235b67dc0520581`, full tree
  `8a6035b7563d2901bf8d34f62f2c879bc1cb0601`; inspected local content-equivalent
  checkout `f293b74420fdf23069449d3cdce324d38d1b3338` remains clean.
- Original raw files remain in [the unchanged failed-cell archive](archives/publication-nonregression-independent-16w-0ms.zip).
- `protocol.json`: 16 writers, independent repositories, 256 KiB plus 257 bytes
  actual payload, PostgreSQL/Hikari, stateful writer, requested 0 ms added RTT,
  fresh JVM for each policy fork, two 3-second warmups and three 5-second
  measurements, `-Xms256m -Xmx1024m`, JDK 21.
- `environment.json`: Temurin HotSpot `21.0.13+9-LTS`, four available CPUs on AMD
  EPYC 9V74, PostgreSQL 17.10, host-bound ext4 storage, actual
  `fsync/synchronous_commit/full_page_writes=on`. The final RTT/completion record
  is absent because the study aborted.

Hashes and direct raw observations are in
[`read-only-observations.json`](read-only-observations.json). This report does not
qualify the incomplete cell. The accompanying provenance and replay retain its
original archive and API digest.

## What the failure and source show

The fourth fork is `contention-aware`, first in pair four. Its first measurement
completed at 123.108 ops/s. During measurement two, three worker threads logged
GCLocker retry warnings at JVM uptime 15.338–15.339 seconds, each for **131,074
machine words**. At eight bytes per word this is 1,048,592 bytes, consistent with
one MiB of array data plus a 16-byte array header.

The three reported OOM stacks terminate in two `ReftableOutputStream`
constructions (ordinary update and compaction) and one `DfsInserter.PackStream`
construction. Pinned JGit 7.8.0 bytecode shows these constructors allocate arrays
of the selected block size. This repository's actual
`ReadAheadHibernateObjDatabase.writeFile` wraps every extension in an
`AlignedDfsOutputStream` whose block size is one MiB. The allocation failure site
therefore does not imply that the 256-KiB payload itself became one GiB.

The exact OpenJDK tag's
[G1 allocation code](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/hotspot/share/gc/g1/g1CollectedHeap.cpp)
emits that warning after exhausting GCLocker retries and returns a null
allocation. That branch is different from successfully completing a collection
and still being unable to allocate. The warning proves the retry path was
reached; it does not provide a post-full-GC retained-heap measurement.

The exact tag's
[Deflater native code](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/java.base/share/native/libzip/Deflater.c)
obtains critical access to the Java input/output arrays around native deflation.
Pinned JGit's pack writer uses `DeflaterOutputStream` with byte arrays. Parallel
compression is consequently a concrete possible source of the critical regions,
although the three failure stacks show allocation victims rather than all
threads occupying JNI regions at that moment.

The corresponding
[GCLocker implementation](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/hotspot/share/gc/shared/gcLocker.cpp)
provides `gc+jni=debug` diagnostics for the GC request, allocation stall and
collection after critical-region exit. The read-only observations record hashes of the inspected external source and
JGit bytecode; the immutable upstream URLs above locate the external source.
They were read, not executed as a workload. The three primary source URLs above were successfully fetched through
the GitHub connector; direct browser access to the OpenJDK issue tracker was
blocked, so this report does not claim an upstream bug ID diagnosis.

## Pressure appears in both policies

All six completed forks report only direct publication selections and zero
pre-persisted selections. The differing policy therefore did not switch the
completed operations into the two-transaction pre-persistence path. This does
not prove identical overhead, but it rules out treating this failure as observed
pre-persistence amplification.

| Completed fork | Maximum sampled heap used, MiB | JMH allocation-rate range, reported MB/s |
| --- | ---: | ---: |
| Pair 1, size | 1,022.477 | 396.353–491.879 |
| Pair 1, contention-aware | 1,022.924 | 551.438–740.148 |
| Pair 2, size | 1,023.250 | 480.073–848.104 |
| Pair 2, contention-aware | 1,022.476 | 581.020–808.981 |
| Pair 3, size | 1,022.870 | 523.471–734.899 |
| Pair 3, contention-aware | 1,023.142 | 595.174–780.462 |

Across the 18 completed iterations, allocation is approximately 6.0–7.4 MB per
publication. GC counters record 10–45 collections and 85–306 ms per iteration.
The failed fork's completed first iteration recorded 25 collections, 140 ms and
705.681 MB/s allocation. These aggregate counters do not show collector type,
region size, full-versus-young collections, fragmentation, JNI occupancy or
before/after-GC retained bytes. The sampled heap statistic is total allocated
heap minus free heap sampled after successful operations, not live retained
memory. Its near-limit peaks alone cannot diagnose a leak.

There are indeed **17 SessionFactories per fork**: one schema owner and 16
thread-owned providers. They exist for the whole trial and increase its fixed
footprint, but the source closes them at trial teardown and every policy fork
uses a fresh JVM. An accumulating factory leak across pairs cannot explain this
child-JVM failure. The per-factory retained footprint still needs measurement.

Other inspected storage/measurement structures do not justify a one-GiB retained
cache attribution:

- JGit's default DFS block cache limit is 32 MiB process-wide, not 32 MiB per
  SessionFactory. Its configured bound is not a complete JVM live-memory bound.
- The repository-instance inline handoff cache caps payload bytes at 512 KiB.
  Sixteen active handles therefore contribute at most eight MiB of those bytes,
  excluding object/map overhead. Newly written chunked packs do not enter it.
- Hibernate transaction sessions close through try-with-resources and their
  thread-local handles are removed in `finally`.
- The profiler resets its worker samples each iteration. JMH 1.37's
  `SampleBuffer` is a bounded histogram (54 possible 1,024-int buckets), not an
  unbounded per-operation sample list.
- The benchmark deliberately keeps adding distinct refs/packs throughout a
  trial. Catalog metadata and some query/compaction work can grow with that
  history; this is a real alternative contributor. None of the retained evidence
  measures its dominator size or excludes all retention paths.

## Smallest diagnostic-only next step

Run **exactly four fresh JVMs** for this same coordinate: size, contention-aware,
contention-aware, size. Keep the source/core, JDK 21.0.13+9, 16 writers, payload,
provider, compression, PostgreSQL settings, 0 ms added RTT, warmup/measurement
durations and **one-GiB heap unchanged**. Use a separate diagnostic runner/output
directory with an explicit `diagnosticOnly` manifest. Do not use the paired
study's output directory, resume its pair counter, calculate acceptance bounds,
or extend until a desired outcome occurs. All four successes or failures are
retained, and each child still has its original finite timeout.

The only child-JVM additions should be bounded, separate GC/allocation and JNI
logs, for example unique per-fork paths with these selectors:

```text
-Xlog:gc*=info,gc+heap=debug,gc+humongous=debug,gc+alloc=trace:file=<fork>-gc.log:uptimemillis,level,tags:filecount=2,filesize=16m
-Xlog:gc+jni=debug:file=<fork>-jni.log:uptimemillis,level,tags:filecount=2,filesize=16m
```

Retain every rotated segment and the JMH stdout/stderr/result, source/runtime
manifest and exit status. These selectors are grounded in the pinned source;
validate JVM option acceptance before launching the first workload. GC
initialization output should record the actual collector/region sizing; preserve
it separately if rotation would discard it. Scan all retained logs before
upload. Do not enable or automatically upload HPROF/JFR recordings.

Evaluate those four runs as diagnostics only:

1. Align JNI stall/release events, allocation retries, humongous-region counts and
   GC before/after occupancy around any OOM. Reclaimed space after the locker
   clears would support collection-prevention/retry exhaustion as a cause.
2. If repeated completed full collections leave occupancy close to the limit,
   prioritize retained-state ownership or fragmentation. The next targeted step
   would be a locally inspected class histogram/heap dump with controlled
   handling, not an unreviewed artifact upload.
3. If all four complete, report that outcome and their GC behavior. It does not
   erase the original failure or disprove a scheduling-sensitive mechanism.

Do not increase `GCLockerRetryAllocationCount`, heap, compression settings, change
collector/JDK, reduce factories, or alter the benchmark's state progression in
this first diagnostic. Those would change the mechanism being tested. A later
minimal reproducer or a separately qualified fix may follow the evidence; it
cannot be substituted into the already failed performance acceptance study.
