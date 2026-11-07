# Large routing observations

Generated from the verified raw CSV files. Times are point estimates; p95 values are from 200 warmed queries per mode.

## stable: Lucene 9.12.3

### Repository text p95

| Initial documents | Repository layout | Unrouted baseline (ms) | Routed 1 (ms) | Routed 8 (ms) | Fan-out 8 (ms) |
| ---: | --- | ---: | ---: | ---: | ---: |
| 100,000 | One repository | 9.194 | 9.720 | 9.411 | 8.900 |
| 100,000 | 100 uniform | 1.444 | 1.009 | 0.687 | 1.193 |
| 100,000 | 1,000, 80% hot | 7.087 | 5.920 | 5.998 | 6.256 |
| 1,000,000 | One repository | 83.946 | 84.590 | 85.323 | 85.209 |
| 1,000,000 | 100 uniform | 6.952 | 5.970 | 4.418 | 5.206 |
| 1,000,000 | 1,000, 80% hot | 90.118 | 97.193 | 95.252 | 95.910 |

### Routed index and restart costs

Each pair is 1 / 8 shards. Index files and bytes are measured after close; factory reopen includes the readiness check and first hit in the same JVM.

| Initial documents | Repository layout | Initial docs/s | Files | Index MiB | Factory reopen (s) | Full rebuild (s) |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 100,000 | One repository | 7,456 / 8,190 | 95 / 82 | 8.57 / 8.56 | 1.00 / 0.72 | 2.25 / 2.09 |
| 100,000 | 100 uniform | 6,704 / 4,932 | 107 / 340 | 8.72 / 10.68 | 0.94 / 0.96 | 2.20 / 1.43 |
| 100,000 | 1,000, 80% hot | 10,111 / 6,583 | 101 / 385 | 8.65 / 10.08 | 0.96 / 0.71 | 1.71 / 1.09 |
| 1,000,000 | One repository | 8,310 / 8,003 | 86 / 85 | 82.35 / 82.82 | 3.40 / 3.20 | 18.73 / 16.13 |
| 1,000,000 | 100 uniform | 4,176 / 2,852 | 89 / 883 | 84.68 / 92.72 | 6.01 / 5.82 | 19.32 / 23.67 |
| 1,000,000 | 1,000, 80% hot | 5,933 / 4,897 | 92 / 688 | 83.76 / 90.33 | 6.56 / 6.10 | 24.80 / 26.37 |

## next: Lucene 10.4.0

### Repository text p95

| Initial documents | Repository layout | Unrouted baseline (ms) | Routed 1 (ms) | Routed 8 (ms) | Fan-out 8 (ms) |
| ---: | --- | ---: | ---: | ---: | ---: |
| 100,000 | One repository | 9.810 | 12.053 | 12.176 | 11.184 |
| 100,000 | 100 uniform | 1.631 | 2.445 | 1.208 | 1.921 |
| 100,000 | 1,000, 80% hot | 6.057 | 5.700 | 6.726 | 6.477 |
| 1,000,000 | One repository | 80.521 | 81.066 | 85.355 | 84.767 |
| 1,000,000 | 100 uniform | 7.124 | 17.788 | 6.426 | 4.753 |
| 1,000,000 | 1,000, 80% hot | 100.758 | 106.041 | 102.341 | 101.519 |

### Routed index and restart costs

Each pair is 1 / 8 shards. Index files and bytes are measured after close; factory reopen includes the readiness check and first hit in the same JVM.

| Initial documents | Repository layout | Initial docs/s | Files | Index MiB | Factory reopen (s) | Full rebuild (s) |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 100,000 | One repository | 8,947 / 9,144 | 62 / 49 | 8.48 / 8.34 | 0.87 / 0.82 | 2.57 / 2.28 |
| 100,000 | 100 uniform | 6,779 / 3,973 | 38 / 370 | 8.56 / 11.02 | 1.10 / 0.84 | 2.16 / 2.47 |
| 100,000 | 1,000, 80% hot | 9,657 / 7,723 | 47 / 292 | 8.49 / 9.70 | 0.98 / 0.68 | 1.76 / 1.06 |
| 1,000,000 | One repository | 8,644 / 8,104 | 59 / 118 | 80.31 / 80.65 | 3.42 / 3.35 | 17.46 / 17.66 |
| 1,000,000 | 100 uniform | 4,632 / 3,211 | 86 / 331 | 82.62 / 88.57 | 9.90 / 6.89 | 19.96 / 30.53 |
| 1,000,000 | 1,000, 80% hot | 6,665 / 5,291 | 101 / 382 | 81.50 / 87.91 | 6.48 / 5.66 | 25.45 / 26.20 |
