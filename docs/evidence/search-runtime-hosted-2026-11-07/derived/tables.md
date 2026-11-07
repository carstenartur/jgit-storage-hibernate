# Hosted Search runtime observations

Generated from the verified JMH raw shots. Rates divide work by mean elapsed time. Query percentiles are means of per-invocation percentiles; they are not pooled percentiles or service-level estimates.

## 1000-500-normal-full

3 measured shots per operation/scenario, one fork. Submission and ready measurements use separate invocations. The wait column is observed after submission inside the ready invocation.

| Scenario | Submit (commits/s) | Burst ready (ms) | Wait after submit (ms) | Query p50 / p95 / p99 (ms) | Query samples per shot | Rebuild (commits/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `reference` | 782.6 | 508.09 | 18.11 | 0.37 / 0.80 / 9.52 | 1057–1383 | 1,056.1 |
| `sync-async-r0` | 2,603.4 | 372.87 | 154.68 | 4.85 / 67.45 / 84.90 | 11–21 | 1,964.9 |
| `sync-async-r100` | 2,863.3 | 414.65 | 210.84 | 1.26 / 5.34 / 36.12 | 67–160 | 1,727.4 |
| `sync-async-r500` | 2,809.5 | 507.36 | 309.61 | 0.87 / 2.47 / 4.14 | 178–319 | 1,516.1 |
| `sync-async-r1000` | 3,079.9 | 1,185.91 | 974.44 | 0.64 / 1.81 / 3.84 | 221–404 | 863.5 |
| `sync-write-sync-r0` | 1,004.6 | 564.16 | 16.66 | 0.61 / 4.62 / 14.10 | 324–740 | 1,007.5 |
| `sync-write-sync-r100` | 1,108.7 | 512.99 | 19.01 | 0.45 / 1.17 / 3.41 | 714–1228 | 998.2 |
| `sync-write-sync-r500` | 894.0 | 670.31 | 31.47 | 0.34 / 0.57 / 2.28 | 1202–1806 | 998.1 |
| `sync-write-sync-r1000` | 1,091.9 | 1,011.53 | 505.06 | 0.34 / 0.51 / 1.44 | 1417–2476 | 970.7 |
| `sync-read-sync-r0` | 2,392.7 | 303.53 | 81.23 | 1.48 / 25.62 / 58.61 | 57–141 | 1,853.6 |
| `sync-read-sync-r100` | 2,516.7 | 274.56 | 69.72 | 1.77 / 31.14 / 109.24 | 31–71 | 2,053.5 |
| `sync-read-sync-r500` | 2,523.5 | 280.94 | 69.49 | 1.34 / 22.68 / 99.17 | 32–119 | 2,065.3 |
| `sync-read-sync-r1000` | 2,427.0 | 275.74 | 74.38 | 1.37 / 19.76 / 75.01 | 31–127 | 2,047.9 |
| `sync-sync-r0` | 1,144.0 | 480.98 | 13.57 | 0.52 / 2.04 / 13.09 | 523–871 | 1,014.1 |
| `sync-sync-r100` | 923.4 | 488.71 | 15.76 | 0.43 / 1.04 / 2.49 | 759–1530 | 922.7 |
| `sync-sync-r500` | 1,035.6 | 572.26 | 18.31 | 0.47 / 1.23 / 3.87 | 724–1167 | 989.2 |
| `sync-sync-r1000` | 1,072.5 | 456.53 | 14.35 | 0.44 / 1.51 / 3.88 | 689–1130 | 993.9 |
| `writer-ram16-t1` | 1,288.2 | 349.61 | 11.65 | 0.59 / 3.00 / 8.45 | 353–763 | 1,301.6 |
| `writer-ram16-t2` | 1,300.5 | 428.48 | 15.74 | 0.59 / 4.21 / 11.54 | 266–843 | 1,218.7 |
| `writer-ram16-t4` | 810.3 | 488.14 | 17.84 | 0.50 / 3.41 / 14.09 | 356–947 | 1,014.1 |
| `writer-ram16-t8` | 1,059.5 | 531.21 | 17.62 | 0.46 / 2.21 / 11.22 | 716–856 | 1,045.7 |
| `writer-ram32-t1` | 1,388.5 | 378.15 | 13.40 | 0.50 / 2.64 / 8.59 | 408–839 | 1,378.4 |
| `writer-ram32-t2` | 711.2 | 417.07 | 13.20 | 0.39 / 1.28 / 8.62 | 845–1033 | 1,083.4 |
| `writer-ram32-t4` | 1,135.3 | 473.49 | 19.37 | 0.59 / 4.93 / 12.32 | 330–847 | 1,046.9 |
| `writer-ram32-t8` | 1,003.7 | 641.50 | 17.33 | 0.60 / 3.64 / 11.67 | 333–1199 | 970.0 |
| `writer-ram64-t1` | 1,447.1 | 435.72 | 13.87 | 0.50 / 2.17 / 8.11 | 459–838 | 944.1 |
| `writer-ram64-t2` | 1,244.1 | 459.95 | 11.81 | 0.60 / 4.30 / 11.23 | 299–762 | 673.5 |
| `writer-ram64-t4` | 1,095.4 | 559.04 | 16.67 | 0.59 / 3.72 / 16.01 | 307–833 | 841.3 |
| `writer-ram64-t8` | 954.5 | 472.62 | 15.78 | 0.48 / 2.88 / 11.02 | 492–1088 | 840.1 |
| `writer-ram128-t1` | 1,248.3 | 344.16 | 9.28 | 0.53 / 3.83 / 8.78 | 415–758 | 1,426.6 |
| `writer-ram128-t2` | 1,261.2 | 443.92 | 18.04 | 0.63 / 4.01 / 11.67 | 371–847 | 1,186.4 |
| `writer-ram128-t4` | 1,164.0 | 459.01 | 14.23 | 0.55 / 3.07 / 12.86 | 506–824 | 1,142.0 |
| `writer-ram128-t8` | 880.9 | 817.72 | 16.27 | 0.56 / 4.15 / 11.31 | 343–1037 | 916.3 |
| `writer-ram256-t1` | 1,182.5 | 325.92 | 9.85 | 0.58 / 4.44 / 9.73 | 304–757 | 1,320.7 |
| `writer-ram256-t2` | 1,078.0 | 409.48 | 11.47 | 0.58 / 3.70 / 10.09 | 398–1004 | 1,133.6 |
| `writer-ram256-t4` | 1,001.6 | 506.07 | 19.09 | 0.55 / 3.52 / 12.14 | 409–994 | 916.8 |
| `writer-ram256-t8` | 1,162.9 | 493.12 | 21.78 | 0.46 / 2.90 / 10.86 | 529–2551 | 971.8 |
| `batch-1` | 135.8 | 3,575.50 | 13.79 | 0.19 / 2.21 / 5.56 | 7022–9658 | 125.6 |
| `batch-50` | 1,130.2 | 420.66 | 18.52 | 0.56 / 3.70 / 12.39 | 506–664 | 580.5 |
| `batch-250` | 1,271.3 | 409.03 | 24.34 | 1.16 / 4.72 / 33.55 | 101–313 | 1,376.2 |
| `batch-500` | 2,184.6 | 241.89 | 16.55 | 0.92 / 3.43 / 27.24 | 146–217 | 1,701.8 |

## 10000-50-normal-smoke

2 measured shots per operation/scenario, one fork. Submission and ready measurements use separate invocations. The wait column is observed after submission inside the ready invocation.

| Scenario | Submit (commits/s) | Burst ready (ms) | Wait after submit (ms) | Query p50 / p95 / p99 (ms) | Query samples per shot | Rebuild (commits/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `reference` | 527.7 | 69.40 | 7.06 | 2.38 / 12.28 / 20.86 | 30–116 | 1,058.5 |
| `sync-async-r500` | 1,171.7 | 404.54 | 358.23 | 14.08 / 30.60 / 30.76 | 5–20 | 2,288.9 |
| `sync-read-sync-r0` | 540.0 | 457.01 | 25.17 | 5.94 / 26.19 / 26.19 | 5–16 | 1,990.3 |
| `sync-sync-r0` | 332.9 | 54.06 | 6.13 | 8.29 / 21.61 / 24.09 | 9–21 | 1,045.4 |
| `sync-write-sync-r500` | 921.1 | 298.31 | 230.51 | 4.79 / 10.89 / 10.89 | 17–19 | 1,063.1 |
| `writer-ram64-t4` | 371.8 | 59.08 | 5.91 | 6.40 / 22.88 / 22.88 | 11–14 | 808.2 |
| `batch-250` | 1,505.6 | 89.87 | 11.94 | 3.99 / 22.26 / 22.26 | 12–12 | 1,590.8 |

## 10000-500-normal-smoke

2 measured shots per operation/scenario, one fork. Submission and ready measurements use separate invocations. The wait column is observed after submission inside the ready invocation.

| Scenario | Submit (commits/s) | Burst ready (ms) | Wait after submit (ms) | Query p50 / p95 / p99 (ms) | Query samples per shot | Rebuild (commits/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `reference` | 1,222.9 | 424.31 | 9.49 | 1.26 / 5.88 / 11.90 | 281–451 | 1,500.6 |
| `sync-async-r500` | 1,908.7 | 682.58 | 406.87 | 1.51 / 4.15 / 7.60 | 145–284 | 3,285.5 |
| `sync-read-sync-r0` | 1,454.9 | 276.57 | 41.50 | 2.52 / 24.32 / 62.69 | 28–90 | 2,873.8 |
| `sync-sync-r0` | 1,387.3 | 391.91 | 12.83 | 1.27 / 6.46 / 11.09 | 241–435 | 1,426.8 |
| `sync-write-sync-r500` | 1,228.4 | 460.09 | 35.90 | 1.21 / 2.44 / 5.34 | 386–591 | 1,483.1 |
| `writer-ram64-t4` | 1,276.8 | 905.67 | 9.33 | 3.16 / 12.92 / 19.52 | 57–369 | 1,503.9 |
| `batch-250` | 1,597.5 | 448.88 | 76.53 | 1.32 / 4.08 / 13.97 | 177–263 | 2,345.4 |

## 10000-500-constrained-smoke

2 measured shots per operation/scenario, one fork. Submission and ready measurements use separate invocations. The wait column is observed after submission inside the ready invocation.

| Scenario | Submit (commits/s) | Burst ready (ms) | Wait after submit (ms) | Query p50 / p95 / p99 (ms) | Query samples per shot | Rebuild (commits/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `reference` | 825.2 | 722.81 | 25.92 | 10.97 / 23.16 / 35.54 | 54–113 | 768.5 |
| `sync-async-r500` | 1,052.2 | 1,007.35 | 653.89 | 15.17 / 27.42 / 60.16 | 21–48 | 1,102.2 |
| `sync-read-sync-r0` | 993.0 | 651.30 | 110.93 | 43.49 / 71.61 / 194.93 | 6–30 | 1,090.5 |
| `sync-sync-r0` | 702.8 | 765.49 | 23.38 | 13.99 / 29.34 / 54.40 | 46–81 | 776.6 |
| `sync-write-sync-r500` | 697.7 | 633.94 | 22.10 | 8.82 / 21.51 / 24.91 | 79–162 | 779.1 |
| `writer-ram64-t4` | 863.5 | 895.64 | 44.62 | 8.95 / 30.81 / 48.03 | 56–133 | 690.5 |
| `batch-250` | 501.0 | 1,131.57 | 35.32 | 13.56 / 291.91 / 551.23 | 13–27 | 923.1 |
