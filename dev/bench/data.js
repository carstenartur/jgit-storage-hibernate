window.BENCHMARK_DATA = {
  "lastUpdate": 1785246759979,
  "repoUrl": "https://github.com/carstenartur/jgit-storage-hibernate",
  "entries": {
    "Repository backend comparison": [
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "519afb961924d5b25bca2dd713f6e852c823721c",
          "message": "Merge pull request #87 from carstenartur/benchmark/filesystem-hsqldb-postgresql",
          "timestamp": "2026-07-25T23:30:33+02:00",
          "tree_id": "90517ff673b17b97bccd4a75c512fab4fbe3cae0",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/519afb961924d5b25bca2dd713f6e852c823721c"
        },
        "date": 1785015195752,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "value": 0.0004006265405596624,
            "range": "0.0000411254633773248",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "value": 0.00040439813028011595,
            "range": "0.00016910363242879118",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "value": 0.010741770236713891,
            "range": "0.004895685021326033",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "value": 0.4557893218315985,
            "range": "3.1154274514163993",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "value": 1.2814060777863108,
            "range": "3.4099427240208198",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "value": 0.14451043804918642,
            "range": "0.4740009750963655",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "value": 3.509667185493529,
            "range": "6.3377132474425375",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "value": 5.919199884519222,
            "range": "2.0339746242088173",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "value": 0.1851687236717576,
            "range": "0.2069876875997429",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "value": 7.072149736479379,
            "range": "21.033340563385867",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "value": 11.554663343812104,
            "range": "11.842809526427397",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "value": 1.3831292912097506,
            "range": "3.4865675063999917",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "35dd2310fa8605e14153053bb8418feef6fc6c73",
          "message": "Update GitHub Actions runtimes and maintenance (#91)\n\nUse current checkout, upload-artifact and dependency-review action lines across the repository workflows, and add weekly grouped Dependabot maintenance for GitHub Actions. Maven tests, release tooling, compatibility coverage and runtime dependencies remain unchanged.",
          "timestamp": "2026-07-26T09:47:13+02:00",
          "tree_id": "0da5e7c02c7a5234010099b68d45077fe2e8aee8",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/35dd2310fa8605e14153053bb8418feef6fc6c73"
        },
        "date": 1785052168513,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "value": 0.0004032629128647936,
            "range": "0.00006862376441853477",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "value": 0.00040801618854046333,
            "range": "0.00008288973768788089",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "value": 0.006661238448047682,
            "range": "0.0025293595586424206",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "value": 0.29918002300100854,
            "range": "3.4233029866611093",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "value": 0.9497958539587099,
            "range": "3.8157490498333053",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "value": 0.0696936571984393,
            "range": "0.10059993013451854",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "value": 2.9782226611746982,
            "range": "3.143849709676734",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "value": 5.015272457980569,
            "range": "4.035987861080308",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "value": 0.11435586681847965,
            "range": "0.28692099629459134",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "value": 6.533709578582305,
            "range": "15.93480116369454",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "value": 10.36968793414511,
            "range": "8.670759147639926",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "value": 0.6705099481649572,
            "range": "2.017356078386402",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "27772f6e334e7df9ffd59bfbbd6d1453f2fe15aa",
          "message": "Publish Maven-compatible SHA-1 checksums alongside SHA-256/SHA-512 (#98)\n\nPreserve Maven Resolver compatibility sidecars while keeping SHA-256 and SHA-512 as the canonical release integrity evidence. Fail anonymous repository verification on checksum warnings.",
          "timestamp": "2026-07-26T12:09:14+02:00",
          "tree_id": "f978b3176b20e2ef45d9903a1df69daa945212f7",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/27772f6e334e7df9ffd59bfbbd6d1453f2fe15aa"
        },
        "date": 1785060693933,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "value": 0.00036878895790476944,
            "range": "0.00008468187804371582",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "value": 0.0003707959449573321,
            "range": "0.00009149230221294767",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "value": 0.012286154346501285,
            "range": "0.003779712564392266",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "value": 0.41997149763381914,
            "range": "3.0997824036223602",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "value": 1.1721142947798084,
            "range": "4.135088562498928",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "value": 0.1397674955089803,
            "range": "0.5396925899391805",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "value": 3.237187389275841,
            "range": "7.938932480951717",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "value": 5.526568796403502,
            "range": "1.1816888489802055",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "value": 0.16583609811623487,
            "range": "0.08832855023515959",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "value": 7.062961737589725,
            "range": "11.32449312678838",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "value": 11.219228886445014,
            "range": "8.805893654433392",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "value": 1.2948106747295618,
            "range": "3.1713840180918207",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "f04e9457384c8507f3467f9c6c1e2a24e82a07aa",
          "message": "Merge pull request #92 from carstenartur/dependabot/maven/jgit-4bbe80ab37",
          "timestamp": "2026-07-26T15:50:29+02:00",
          "tree_id": "a8f78317cd5f1f20b6262c40bf3b747ed66dd7e1",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/f04e9457384c8507f3467f9c6c1e2a24e82a07aa"
        },
        "date": 1785074019126,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "value": 0.00043761114439898997,
            "range": "0.0001241418044136924",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "value": 0.00043689998983714013,
            "range": "0.00007044614149488563",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "value": 0.010943996626847002,
            "range": "0.0028941468724315777",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "value": 0.4433577364034058,
            "range": "3.146280532052669",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "value": 1.2858910887478154,
            "range": "3.9327593693905722",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "value": 0.14691778430207866,
            "range": "0.42925199534772013",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "value": 3.4378583961397218,
            "range": "6.155239421874515",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "value": 5.937366005445288,
            "range": "3.5138369878229403",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "value": 0.18249080406797613,
            "range": "0.17109007707383758",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "value": 7.345853952123633,
            "range": "31.211807809189697",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "value": 11.844196703826237,
            "range": "13.438024604338608",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "value": 1.3688586135565284,
            "range": "3.8486828516283156",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "e4b09b4bd5a68c6a43d3fdd1639e409b20e298fa",
          "message": "Add SECURITY.md for security policy and reporting\n\nAdded a security policy document outlining supported versions and vulnerability reporting.",
          "timestamp": "2026-07-26T16:45:33+02:00",
          "tree_id": "8f80466e4cc030517f0bc235cb3a18d8f0ff8090",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/e4b09b4bd5a68c6a43d3fdd1639e409b20e298fa"
        },
        "date": 1785077276503,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "value": 0.00038378953465940875,
            "range": "0.00015746582417573856",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "value": 0.00037320843588773957,
            "range": "0.00014924859910210437",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "value": 0.012387859848573672,
            "range": "0.003414911768739339",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "value": 0.5072483037315095,
            "range": "3.930742397982096",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "value": 1.2830380969007293,
            "range": "3.9925281547749165",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "value": 0.13176631597735886,
            "range": "0.335264254984666",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "value": 4.563464526120303,
            "range": "16.65676222482512",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "value": 6.797486343420116,
            "range": "7.614807263613438",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "value": 0.17794046460676005,
            "range": "0.1107663683626005",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "value": 10.612483456648137,
            "range": "28.255263477688885",
            "unit": "ms/op",
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "value": 14.649091769107423,
            "range": "19.406098733746067",
            "unit": "ms/op",
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "value": 1.2910323694170678,
            "range": "3.0920725143764995",
            "unit": "ms/op",
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "carstenartur"
          },
          "distinct": true,
          "id": "aab198d6d574311bde731c27013553f19db9b45a",
          "message": "Publish generated metrics exclusively to gh-pages (#116)\n\nMove generated badges and benchmark history off main, publish them through a tested repository-owned gh-pages worktree publisher, and serialize all generated Pages updates.",
          "timestamp": "2026-07-28T15:28:51+02:00",
          "tree_id": "1228ae1231773970b0070cb7f7d31695ceaf1d1b",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/aab198d6d574311bde731c27013553f19db9b45a"
        },
        "date": 1785245489351,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0004532515331837534,
            "range": 2.6559948521714275e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0004502437248612156,
            "range": 5.173035356540982e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.00771331976818606,
            "range": 0.001795393395633532,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.24173052084433636,
            "range": 1.822007879250845,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.162380048691653,
            "range": 3.206433773912869,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.08477487122473988,
            "range": 0.11826417707345466,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.33311803774134,
            "range": 28.599736504494714,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.107578623994078,
            "range": 30.069786573418426,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.18723180109323004,
            "range": 1.1892603826930999,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 14.53109756399319,
            "range": 65.58727360330919,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 22.060058623895255,
            "range": 55.88664513236748,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.9599982171980798,
            "range": 2.054756832506975,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "carstenartur"
          },
          "distinct": true,
          "id": "b065cb3e520ee47c693a543f7d7ee95a58d49804",
          "message": "Deploy performance dashboard through GitHub Pages (#117)\n\nDeploy the generated gh-pages branch as a real GitHub Pages artifact after successful main-branch JMH runs.",
          "timestamp": "2026-07-28T15:50:08+02:00",
          "tree_id": "5aa89554ad0a34426a13ece57e9a128f2e89d7a0",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/b065cb3e520ee47c693a543f7d7ee95a58d49804"
        },
        "date": 1785246759979,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003414024379787113,
            "range": 4.2399762239613765e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003507549833224994,
            "range": 0.00012495424715519175,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01251208548782394,
            "range": 0.003490600566707921,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.24933357894582428,
            "range": 2.513624492254477,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.4160626454275285,
            "range": 1.6646823868976088,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.12993740285043218,
            "range": 0.2668140256915136,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.325530704378277,
            "range": 61.85480536433035,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.109014584456505,
            "range": 46.179187850654024,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1644056184550701,
            "range": 0.10982691088864147,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 19.244963167651303,
            "range": 86.84230275935337,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 25.97709276786154,
            "range": 73.5965187136826,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3163452051680435,
            "range": 3.7849201005763424,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      }
    ]
  }
}
