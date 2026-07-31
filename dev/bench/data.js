window.BENCHMARK_DATA = {
  "lastUpdate": 1785529440116,
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
          "id": "fa17442c2e3731b287e187096c41c2e230751bac",
          "message": "Add GitHub Actions workflow for GitHub Pages deployment\n\nThis workflow automates the deployment of static content to GitHub Pages upon pushes to the main branch or manually through the Actions tab.",
          "timestamp": "2026-07-28T19:02:17+02:00",
          "tree_id": "1990a11414c75eb552d8c929bb1b01771c591bef",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/fa17442c2e3731b287e187096c41c2e230751bac"
        },
        "date": 1785258292305,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003510545640918607,
            "range": 7.311546266260676e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00034358950120801085,
            "range": 3.257803910841507e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010421506574682311,
            "range": 0.003236701206926016,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.32931806303874267,
            "range": 2.3899828066613797,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.5022297587788744,
            "range": 1.5867231327944598,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13058661790095996,
            "range": 0.28932875684022785,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 5.496935150870034,
            "range": 20.364582197597098,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.782736111014042,
            "range": 18.159933601038123,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.19734362443394007,
            "range": 0.12683963720339564,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.359937495783269,
            "range": 57.440432690870686,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 19.59902422703638,
            "range": 36.38485422985243,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2836987037779506,
            "range": 2.2296886779255853,
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
          "id": "37e6621fdcbe8ea4ddd18f3aaac995713dd9baa1",
          "message": "Make legacy schema preflight portable to SQL Server (#119)\n\nReplace the database-specific pack-extension length query with a streamed JDBC validation, add SQL Server 2022 coverage and preserve Unicode code-point semantics.\n\nRefs carstenartur/sandbox#1303",
          "timestamp": "2026-07-28T21:38:22+02:00",
          "tree_id": "2bbb355a09bc4e52d0e9ed33de96fe1b82522b5d",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/37e6621fdcbe8ea4ddd18f3aaac995713dd9baa1"
        },
        "date": 1785267699715,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003397155937167658,
            "range": 5.367100712681811e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003421941936497601,
            "range": 5.770823779383148e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012043448425435019,
            "range": 0.0019581507568360145,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.25866197609823977,
            "range": 1.876748315084016,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.3749938347619652,
            "range": 2.030455376702216,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13796345891219522,
            "range": 0.49745556606094316,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 5.345440359662582,
            "range": 28.31310767886964,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.777213544963734,
            "range": 23.02095431077857,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.16527442363167438,
            "range": 0.033412504557672904,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.247201452502589,
            "range": 59.99283473230741,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 22.116831156501473,
            "range": 66.670150763822,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2641168895937558,
            "range": 2.371521926550426,
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
          "id": "b9d257d8bdf64ab629abe16eb121389974e84c57",
          "message": "Merge pull request #118 from carstenartur/perf/adaptive-pack-storage-and-workloads",
          "timestamp": "2026-07-28T21:44:38+02:00",
          "tree_id": "d90421245f4b620db4922bd048ebec8a560b292b",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/b9d257d8bdf64ab629abe16eb121389974e84c57"
        },
        "date": 1785268208321,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0024201914643187656,
            "range": 0.00035153988653727653,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.002448755464195847,
            "range": 0.0002571982221586564,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0024668070833794987,
            "range": 0.0007034011530852724,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014420303816506871,
            "range": 0.001684056853733184,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003360823606163453,
            "range": 6.208933818134161e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00033674965982114896,
            "range": 1.4003776161285325e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003382105467546538,
            "range": 3.5146433294164266e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01207095318153807,
            "range": 0.0010553519581948504,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.3389426690098692,
            "range": 1.5709932930478847,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.4720346307120054,
            "range": 2.7705777528164823,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.4688153228897225,
            "range": 2.318675490602775,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13038176432763926,
            "range": 0.25038850138169894,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0002145789581634492,
            "range": 1.4690007996243353e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00021306216368563446,
            "range": 5.645648104222928e-06,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0002288597138716177,
            "range": 1.973374953665083e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02445156704716775,
            "range": 0.0008186399565467765,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.996052309552155,
            "range": 24.762215989581275,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.741094908226495,
            "range": 33.24020737521803,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.461878391086692,
            "range": 32.698787956941224,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.911386412758578,
            "range": 21.87339334724456,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.064285609621393,
            "range": 18.774011468165547,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.197227883268072,
            "range": 17.362427606918583,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 9.583037754384655,
            "range": 25.381929319671773,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.16547884064213564,
            "range": 0.12351482157796764,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.603320602145706,
            "range": 45.21349653449274,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 16.552896830981183,
            "range": 19.317313438820158,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 16.55864537231969,
            "range": 15.958528662042285,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2309561753207168,
            "range": 2.969037582481313,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.157611997459506,
            "range": 41.51099891865914,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 17.429958782840156,
            "range": 26.222344644573823,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.110367833933235,
            "range": 46.48150998815674,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.974306487904688,
            "range": 13.807884886785601,
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
          "id": "4c172726684ae9509948eff96cc656501565629b",
          "message": "Deploy GitHub Pages only from generated gh-pages content (#122)\n\nRemove the competing main-root Pages deployment and deploy the verified benchmark dashboard exclusively from gh-pages on main pushes and successful JMH runs.",
          "timestamp": "2026-07-28T21:57:41+02:00",
          "tree_id": "0e7223bf7fbd3c3c0cc48983f87d0423f7766a02",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/4c172726684ae9509948eff96cc656501565629b"
        },
        "date": 1785268955693,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.002488560812017926,
            "range": 0.00018119796579508176,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0024948324576050014,
            "range": 2.3115106528988784e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002509869947874,
            "range": 0.00012640714641739759,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014483807290486884,
            "range": 0.0009311311504599017,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003377267810306577,
            "range": 2.7569355141268268e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00033894900521918577,
            "range": 4.909737038641099e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003367089380230688,
            "range": 4.712075074998298e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012175103203251473,
            "range": 0.0020189165585196333,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.3463506296207399,
            "range": 2.763026723957889,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.4517016202751385,
            "range": 1.6809466453277957,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.448618179475213,
            "range": 2.5243139301637867,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13277235782662558,
            "range": 0.2912292906002033,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00021256404012990273,
            "range": 1.9315097354320447e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0002122819539707296,
            "range": 2.5251438875128286e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00021375754985802168,
            "range": 1.8069418705969106e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024290233759895086,
            "range": 0.0031301038114052685,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 9.808253865853606,
            "range": 22.71868589879274,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.966986988815592,
            "range": 23.746771373447427,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.854619984848485,
            "range": 36.66448236245116,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 19.142758874680137,
            "range": 19.918681776156546,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 5.77677336832788,
            "range": 25.756820346884705,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.82919650476863,
            "range": 12.354641099126601,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.7044655065707,
            "range": 5.997895936147737,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.16703099849878214,
            "range": 0.2150945666057604,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.182270330447624,
            "range": 47.13625978520844,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.627014114070487,
            "range": 37.02287385626155,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.67225615514978,
            "range": 31.072016249991208,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2845015272928764,
            "range": 2.2436100418357716,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.386249772916665,
            "range": 37.42417461553595,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 19.96172250997509,
            "range": 46.490869008342074,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 19.42863737653127,
            "range": 34.05911113388756,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.715812881645273,
            "range": 9.664234566156699,
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
          "id": "7b48449a50c8bdc3d15ed7376a9e09c63e0bc284",
          "message": "Merge pull request #123 from carstenartur/perf/chunk-read-ahead\n\nPrefetch sequential database pack chunks",
          "timestamp": "2026-07-28T22:39:01+02:00",
          "tree_id": "fa5b0d34496670aafd976009537ad894c3fdf68f",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/7b48449a50c8bdc3d15ed7376a9e09c63e0bc284"
        },
        "date": 1785271480388,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0027124355363455856,
            "range": 0.0016123605106749964,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0027128628996065207,
            "range": 0.00013079058740525002,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0026687476540436434,
            "range": 0.0005747302430852635,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.013259279190716115,
            "range": 0.004341831604398653,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003384828117021291,
            "range": 9.67640875743325e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003793875299727669,
            "range": 2.8203428255396834e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.000380336676183956,
            "range": 0.00014253024968295593,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01056368293933249,
            "range": 0.0007148300363815267,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.1302152922010889,
            "range": 1.2059029586982875,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.118231381325889,
            "range": 0.09006991892281006,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0998979294396705,
            "range": 0.04409817678510919,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.504212545923019,
            "range": 0.2794484222581894,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.3834356029221211,
            "range": 2.2099219688165443,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.5277297098674003,
            "range": 1.814866748914338,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.5193078414511367,
            "range": 2.1400699524652245,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.134841841598507,
            "range": 0.31143149039431434,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00020388425847864202,
            "range": 1.6682069312954072e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0002057070248604085,
            "range": 6.7481162725947615e-06,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00020338771632427016,
            "range": 3.663307538985246e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02433847973309794,
            "range": 0.004183086289142637,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 9.69626945063643,
            "range": 20.661304796787288,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.144353902316437,
            "range": 17.977109657717754,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.64306658300095,
            "range": 30.145916996805546,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 18.867730581330942,
            "range": 22.346416315639193,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 6.035124985371439,
            "range": 22.4187242854237,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.217842846402831,
            "range": 23.394891368603382,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 9.003203683634668,
            "range": 14.79588440594652,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.17287316173458697,
            "range": 0.14874904825464208,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.300516947784208,
            "range": 37.620717735037665,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.24869246906898,
            "range": 23.297140346723427,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 17.856681948935243,
            "range": 24.422045127253686,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3356937524346906,
            "range": 3.1800187807677447,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.575278612729464,
            "range": 48.19977768837188,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.53404625195336,
            "range": 47.38945597184005,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.64745689683566,
            "range": 44.887193493083856,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.759298836900641,
            "range": 9.284412072492833,
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
          "id": "c91ef30122dcdfea33c8370e9727f2f23c12f57a",
          "message": "Merge pull request #121 from carstenartur/feature/sqlserver-core-migrations",
          "timestamp": "2026-07-28T23:17:06+02:00",
          "tree_id": "67c25ee77312c5658a628c964dfb6df394f681e8",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/c91ef30122dcdfea33c8370e9727f2f23c12f57a"
        },
        "date": 1785273849231,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.002427625842735434,
            "range": 0.0009261559569241809,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.002436472781001857,
            "range": 0.00018025196532648138,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0024623818591182284,
            "range": 0.0006675411576384017,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014340550930249545,
            "range": 0.0009577916472403886,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00034185279304590875,
            "range": 2.3310025742523045e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003370710988770354,
            "range": 2.6438258904279905e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003393388451548983,
            "range": 5.521943806710147e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01217812494833302,
            "range": 0.001269840726120911,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.1791907001679078,
            "range": 0.6095189765973228,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.174271352666052,
            "range": 0.03722583014494705,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.173166200054679,
            "range": 0.07427545655018067,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.585741238303223,
            "range": 0.22352222155188928,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.3808212628992798,
            "range": 2.882020402873014,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.4132339647270804,
            "range": 2.370042358615545,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.4130154709906375,
            "range": 2.2975172333825116,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13447176583705156,
            "range": 0.4242337072439406,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00021676434260465083,
            "range": 7.444773437024248e-06,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00021550659023604187,
            "range": 2.5959087864683963e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00021195419095061124,
            "range": 3.615424311697788e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02441318144942763,
            "range": 0.00540616167360314,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.282853425308641,
            "range": 26.86788609174668,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.784970370953323,
            "range": 20.25419875181442,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 11.10142416337088,
            "range": 11.55173044498779,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.98009447698033,
            "range": 8.550808864397396,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 5.800969160102789,
            "range": 29.964244605387474,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.608825372362537,
            "range": 14.741377406355115,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.031742028986947,
            "range": 9.137603208996698,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.15967656292438917,
            "range": 0.17862253118345578,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.86251099241881,
            "range": 24.465207749497335,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.420683186050905,
            "range": 26.201113441517602,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.370007007007022,
            "range": 33.32126473781936,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.1797497564810742,
            "range": 2.0969521640540827,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.577888107680131,
            "range": 42.86740537050187,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.97687906152395,
            "range": 39.9872171930961,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.323088902041828,
            "range": 27.686249436488566,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.555161444404361,
            "range": 7.14007454326988,
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
          "id": "7a5585778b58dd21d5794db49e8889c587d88688",
          "message": "Document SQL Server Core support and adoption (#125)\n\n* Document SQL Server Core migration support\n\n* Document SQL Server legacy Core adoption\n\n* Add SQL Server to the Core consumer contract",
          "timestamp": "2026-07-28T23:30:21+02:00",
          "tree_id": "942c0972455270926709d2b11680097542ee1538",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/7a5585778b58dd21d5794db49e8889c587d88688"
        },
        "date": 1785274840382,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0026913376663158725,
            "range": 0.00037291023755106764,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0026688839383140956,
            "range": 0.000731457225018275,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002710562682670188,
            "range": 0.0001242161043868699,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.013110502397801498,
            "range": 0.0023161593034287534,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003586442009506702,
            "range": 0.0001279948485194713,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003475766204599689,
            "range": 4.653615910789976e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00035429308879280177,
            "range": 4.8132674441709586e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010881611219861939,
            "range": 0.0032353984937331104,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.092134267640492,
            "range": 0.03561007702626795,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0849995596584205,
            "range": 0.05302870667474375,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0931560055071847,
            "range": 0.11926523702764746,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.478787734153358,
            "range": 0.10182738308453064,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.366368440015731,
            "range": 2.3337908767196414,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.5155642321112797,
            "range": 1.9493579075589569,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.4954327325287264,
            "range": 2.337723963798572,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.14288142988399005,
            "range": 0.5114966601713075,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00020365542465929174,
            "range": 3.7177755109564313e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00022704180404034815,
            "range": 0.00045784260883194116,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00020227463262238753,
            "range": 2.9553553765776903e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024525326281890164,
            "range": 0.008088533607648487,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.708809804267593,
            "range": 19.89172559028864,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.082564667519334,
            "range": 18.103075651203238,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.067541719646632,
            "range": 16.497149518728843,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 18.707910988090237,
            "range": 25.306914621740972,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 5.043405336496544,
            "range": 16.60599381018877,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.351849279849105,
            "range": 6.618010181454202,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.33890238626005,
            "range": 12.998326450909058,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.17799292106699638,
            "range": 0.20972207468824952,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.474411145833335,
            "range": 50.49749472489517,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.01735662229064,
            "range": 9.754405815795554,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 17.699228166727224,
            "range": 28.71750416123431,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3116294595185825,
            "range": 2.5384676210528094,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.016321436866258,
            "range": 42.26593620915169,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 19.010321456903128,
            "range": 44.864356973405044,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 19.07944141108978,
            "range": 39.155763060088894,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.869921472902884,
            "range": 13.16361174249415,
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
          "id": "3fe3fa3368337cec503cfe2f4fe543a239cf0a53",
          "message": "Benchmark real push clone and fetch workflows (#124)\n\nAdd real ReceivePack and UploadPack performance workloads, restore persisted pack extension sizes, align DFS channels to one MiB blocks, and cover two-pack incremental fetch on H2 and HSQLDB.",
          "timestamp": "2026-07-29T00:18:02+02:00",
          "tree_id": "cdcf293e5ad7e6de130e4ac0e669a046d0dd9c1c",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/3fe3fa3368337cec503cfe2f4fe543a239cf0a53"
        },
        "date": 1785277819897,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 24.6735854,
            "range": 17.829918720818277,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 27.756564999999995,
            "range": 17.612853302344377,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 27.524377400000002,
            "range": 22.132152666479016,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 29.000415599999997,
            "range": 9.731460523815105,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 46.15891880000001,
            "range": 32.01601886332918,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 49.007302200000005,
            "range": 22.28312384305655,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 51.631434999999996,
            "range": 28.71869641996587,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 21.863525600000003,
            "range": 11.28360135635074,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 146.08908060000002,
            "range": 41.37856147958694,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 146.9825172,
            "range": 48.20884906730466,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 151.07222919999998,
            "range": 66.40088506222,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 165.71803720000003,
            "range": 41.502995854072864,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 177.3045512,
            "range": 68.5633393558919,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 190.07574440000002,
            "range": 41.862628284692214,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 195.8688604,
            "range": 122.62191540575209,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 151.1189324,
            "range": 62.73523413402061,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0026295539672167665,
            "range": 0.0016417711740667439,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.002652104038685942,
            "range": 0.0008293217994360365,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0028016102524155476,
            "range": 0.004080317129416879,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01282229681801785,
            "range": 0.0009395687684484952,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00034872919883716943,
            "range": 7.419914152569868e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003627172361072904,
            "range": 6.2858945190065666e-06,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003356398912769741,
            "range": 1.9213814392991287e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010707852748611987,
            "range": 0.0008261504419453745,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.049537627902594,
            "range": 0.1835394604333152,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0757004662282346,
            "range": 0.8826930086122289,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0424838594144112,
            "range": 0.04819880192982133,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.460629758165113,
            "range": 0.22629402828888645,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.24178785063146443,
            "range": 1.5421361559380256,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0740500741248888,
            "range": 1.0740789718771082,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.088944277740814,
            "range": 1.2096273179641275,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1369399027619709,
            "range": 0.2858875947862697,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00017557777217932306,
            "range": 7.219861088912906e-06,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00017785393151037085,
            "range": 1.4355499255226606e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018591250453341127,
            "range": 1.589589919508235e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02505400679630719,
            "range": 0.004329536169248968,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.654399242866681,
            "range": 18.930295395373623,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.210919270712814,
            "range": 13.053230472391563,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 11.416537531989151,
            "range": 14.38438708530326,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 19.15859789335093,
            "range": 18.990033955810922,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 4.760849198808946,
            "range": 17.802207422348815,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.151237590902968,
            "range": 8.327556658867207,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.269730983710584,
            "range": 11.094744332276225,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.18466183198566632,
            "range": 0.1003880029652495,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.393124947101448,
            "range": 75.66094044757052,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 17.756804611693592,
            "range": 32.53566562465144,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 17.540220036128304,
            "range": 29.73870781726507,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3186510968616438,
            "range": 2.733049570007094,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.865130792061253,
            "range": 40.95900526262024,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.62762813739479,
            "range": 33.24765840590492,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 19.399935762350456,
            "range": 32.408425232507454,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.140761875054391,
            "range": 6.632077939633862,
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
          "id": "5e4257db802ae97ecd0f29171e8e9c95723bc3ab",
          "message": "Measure protocol storage transactions and lock costs (#128)\n\nAdd opt-in repository transaction and lock metrics, publish Hibernate and storage counters as JMH secondary results, and document the measured protocol costs.",
          "timestamp": "2026-07-29T00:46:03+02:00",
          "tree_id": "6702078dd64f6733de27d088a036c66ec66947cc",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/5e4257db802ae97ecd0f29171e8e9c95723bc3ab"
        },
        "date": 1785279164984,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 29.053303200000006,
            "range": 25.217577197812034,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 28.14864,
            "range": 21.165166464312136,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 27.436321799999995,
            "range": 23.21649884413162,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 28.6128752,
            "range": 16.551479922489545,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 49.1357144,
            "range": 31.361175539865386,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 55.3763522,
            "range": 30.871327595354902,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 55.24874799999999,
            "range": 32.636123988017694,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 22.375134199999998,
            "range": 8.299595284023539,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 145.7011968,
            "range": 60.27327628286743,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 146.7691202,
            "range": 28.444460424414398,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 149.6017484,
            "range": 68.69052600919788,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 166.3417446,
            "range": 59.35394032971868,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 188.29490879999997,
            "range": 44.1746228406394,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 196.3610162,
            "range": 73.18240542347395,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 199.0306778,
            "range": 59.905649710794904,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 153.92893959999998,
            "range": 93.58230226612336,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0026904547280943657,
            "range": 4.9614767311969154e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0026539896224987984,
            "range": 0.00023927206011982788,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002664361133559732,
            "range": 0.0005069411992248988,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.013134404923344197,
            "range": 0.006357643478468496,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003477804367045393,
            "range": 0.0001766304580618003,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00034637117704288366,
            "range": 1.6986842759766368e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00034809435312510914,
            "range": 5.025685489876274e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010514945059244449,
            "range": 0.001439133401261093,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.0869397873883087,
            "range": 0.7661406116345509,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0659935085712544,
            "range": 0.03145775813829435,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0837619529393347,
            "range": 0.7087794024238924,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.480879607128517,
            "range": 0.1394006475771335,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.2616472748243776,
            "range": 1.1587332671891555,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0459216976757302,
            "range": 1.0442191104919887,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0643346756282424,
            "range": 1.1493111471397786,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1400850990625319,
            "range": 0.34635357628841,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00019309595839151527,
            "range": 0.0002804792858888202,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0001837483643131268,
            "range": 4.472987538801491e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00017951972874141915,
            "range": 2.338105856637733e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02424050806568138,
            "range": 0.00388827867857071,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 9.444176884971174,
            "range": 19.031815161704007,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.2495707875453,
            "range": 15.378299003630188,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.542760873257398,
            "range": 23.29529364152914,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.755607953023645,
            "range": 16.236320783415533,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 6.284934716359245,
            "range": 36.405238957158744,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.188350908701032,
            "range": 10.328876257835999,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.888731463221648,
            "range": 10.462647144836163,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.17479854860213928,
            "range": 0.07113137421833679,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.77608321540236,
            "range": 43.977219164371306,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 20.12199990832686,
            "range": 41.728505358057475,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 19.356884296549524,
            "range": 25.04854326968495,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3000945883144892,
            "range": 2.6680745520764018,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.8058227996641,
            "range": 42.27544610606815,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 20.17448995189076,
            "range": 38.89507373053772,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 20.926422425452685,
            "range": 40.46738953440477,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.744512670621099,
            "range": 11.201735528981015,
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
          "id": "82a4e1d6d6b6a931e0e1955e954e676e6b988384",
          "message": "Make overlapping benchmark series visible (#129)\n\nUse stable dash patterns and point markers, group tooltips by commit, and add a latest-result table so nearly identical backend measurements remain inspectable.",
          "timestamp": "2026-07-29T06:01:05+02:00",
          "tree_id": "82426ddaeb6729fe23712f0b4cc54989cebd6815",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/82a4e1d6d6b6a931e0e1955e954e676e6b988384"
        },
        "date": 1785298063395,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 25.739449199999996,
            "range": 13.526042511881634,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 29.671858000000004,
            "range": 18.331966237735397,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 27.397315000000003,
            "range": 15.276890720219882,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 27.7052718,
            "range": 12.352495146866104,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 53.202477599999995,
            "range": 5.567527322333314,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 48.91010240000001,
            "range": 30.016780266770507,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 57.9847858,
            "range": 25.886576873360315,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 20.9190932,
            "range": 11.052726907680695,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 162.53972500000003,
            "range": 43.536122754930474,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 162.0991316,
            "range": 48.350591344134216,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 162.0040214,
            "range": 55.0279124359259,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 182.17340059999998,
            "range": 57.892107835146156,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 191.56850079999998,
            "range": 39.87729446740309,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 208.35546399999998,
            "range": 42.635240202609616,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 209.9319132,
            "range": 74.65091918816869,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 160.737318,
            "range": 23.414503228384813,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.002516932924933928,
            "range": 0.00016645215388733127,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0025141832049540014,
            "range": 0.00027295534539194277,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002509840750967406,
            "range": 0.00015983455431559275,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01457436756863046,
            "range": 0.00295349014991905,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003612480162261082,
            "range": 1.848211435151153e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00037067740863393856,
            "range": 5.5801590085501746e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003363544094930407,
            "range": 8.316984718053791e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012378656537534094,
            "range": 0.004642310145233028,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.2096907356526956,
            "range": 0.9545731434331745,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.1586458144387977,
            "range": 0.477509731418674,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.1488812934154657,
            "range": 0.04373419800923243,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.5612226789398924,
            "range": 0.2807036453703772,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.24964300660764707,
            "range": 1.9053115746701577,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.9625198326569331,
            "range": 1.4596658318843023,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.9642819202369836,
            "range": 1.276982045224112,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.12808386092068225,
            "range": 0.258043480103867,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00018467876195032767,
            "range": 6.27791827120431e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00019475121527269188,
            "range": 0.0002303151283660089,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0001883694737886859,
            "range": 6.044991914480601e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.023920072781459977,
            "range": 0.0013542938785144377,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.288468666175369,
            "range": 20.8188590825832,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.1411618503955,
            "range": 29.024691050484325,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.938249555314913,
            "range": 25.334224475778836,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.429450614369255,
            "range": 11.558084832253455,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 5.458750510142784,
            "range": 22.74689184503886,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.340766512376184,
            "range": 12.756757958347274,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.896757163517469,
            "range": 13.292825202421342,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.15614458836269832,
            "range": 0.15366036514478915,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.984585041293178,
            "range": 60.09872965705939,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 20.177577418091165,
            "range": 36.498405552121184,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 20.545927459622366,
            "range": 40.57956397768553,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2596352119267042,
            "range": 3.319223377742423,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 16.733466867538564,
            "range": 118.01254187016897,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 21.154955189534935,
            "range": 46.81074346936461,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 21.657746979139393,
            "range": 59.08803580778916,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.3759155233046085,
            "range": 7.537654125836912,
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
          "id": "1a9c91396432d2394bec30e4f0a6387afcacde84",
          "message": "Add SQL Server Search support and rebuild operations (#127)\n\nShip SQL Server Search migrations, operational rebuild progress and retry semantics, author/committer filtering, deterministic structured pagination, real-container deletion and persistent Lucene restart evidence, and deployment/rollback documentation.\n\nRefs #126 and carstenartur/sandbox#1303.",
          "timestamp": "2026-07-29T06:53:46+02:00",
          "tree_id": "fce02902fbdbf0afc1a91e96d04777c4558f4533",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/1a9c91396432d2394bec30e4f0a6387afcacde84"
        },
        "date": 1785301246266,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 24.906340999999998,
            "range": 14.682249993530265,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 25.279266400000004,
            "range": 15.916189324272505,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 26.138929400000002,
            "range": 19.270735442950144,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 27.6298518,
            "range": 16.552686707479804,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 43.2110568,
            "range": 23.87177197373446,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 49.3376868,
            "range": 27.029186609260496,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 49.101577799999994,
            "range": 35.854604924710834,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 21.618410599999997,
            "range": 10.525509237346657,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 141.30814279999998,
            "range": 28.284895926689263,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 146.374968,
            "range": 47.846092846198204,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 145.61124420000002,
            "range": 34.91161822595506,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 168.67777519999999,
            "range": 51.3968838666937,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 176.96149659999998,
            "range": 74.48556647569269,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 188.0365998,
            "range": 38.04875698943718,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 193.6946348,
            "range": 57.93213525120357,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 150.66309940000002,
            "range": 52.97040323144205,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0026425223969542464,
            "range": 0.00023127921411815418,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0026452039059282743,
            "range": 0.00041359326177022417,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0026151868291699586,
            "range": 0.00032013636726315104,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012869311995367144,
            "range": 0.0014230053867670817,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003750108529099866,
            "range": 0.00013948042222573207,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00036664778649240864,
            "range": 5.54799240773166e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003730798956280493,
            "range": 8.422872512878741e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01066275100594156,
            "range": 0.0008023504406681651,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.0612374458751275,
            "range": 0.057538067702344715,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0728916326880087,
            "range": 0.3933428440582416,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0565225856535447,
            "range": 0.04721419368235099,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.4783695395241887,
            "range": 0.2880375478192323,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.27933180521658363,
            "range": 1.1774133186723135,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0366652503064209,
            "range": 1.1335333529630633,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0606920702092564,
            "range": 1.10720969797166,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.14292149711752136,
            "range": 0.5178349661505979,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00019341691280247517,
            "range": 0.00022459624168743417,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0001949851049012572,
            "range": 0.0003201823565868583,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00022917598628138404,
            "range": 0.00025716732330616573,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.023631106353496717,
            "range": 0.0033465200115222332,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.343039252922926,
            "range": 39.84447462986324,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.211640843232905,
            "range": 25.211478580741492,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 14.021009008349358,
            "range": 29.79059593292534,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 19.12337726231705,
            "range": 21.265415566316484,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 6.501598374848903,
            "range": 18.889862656536632,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.698415910845634,
            "range": 6.817995309284847,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.86916109301267,
            "range": 5.025002570037005,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.18339245578180038,
            "range": 0.19454725321573527,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.473674061991508,
            "range": 19.74515797384949,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 16.91880165854509,
            "range": 34.35551062495878,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 17.58429038781701,
            "range": 29.293299159658975,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3386601721489961,
            "range": 2.9271737595204668,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.588718945544244,
            "range": 46.51860371364285,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 19.918490135086653,
            "range": 34.642831656952715,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.027808700780366,
            "range": 32.86522721619198,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.044512506455031,
            "range": 13.186873480165897,
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
          "id": "847921e1dec6a9f2fac3c85b3efc4ab0f0319dcd",
          "message": "Attribute protocol storage transactions by operation (#132)\n\nAdd immutable categorized transaction and repository-lock diagnostics, reconcile them with the existing aggregate metrics, publish raw protocol-operation counters through JMH, and document correct per-invocation interpretation of event scores.\n\nRefs #131.",
          "timestamp": "2026-07-29T07:44:27+02:00",
          "tree_id": "80f3a9ad869219eda65abbe6b6af7161e6e9197a",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/847921e1dec6a9f2fac3c85b3efc4ab0f0319dcd"
        },
        "date": 1785304313080,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 27.056858,
            "range": 16.382491519098338,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 30.6760676,
            "range": 31.31872521257269,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 29.132730600000002,
            "range": 22.631309339714928,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 26.500878999999998,
            "range": 8.122668636855575,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 55.065484600000005,
            "range": 30.81336950466349,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 56.236321999999994,
            "range": 24.923261837980036,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 61.93717939999999,
            "range": 38.06784522712178,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 22.293009,
            "range": 7.709837459612203,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 148.374526,
            "range": 34.872044140804185,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 151.92367240000002,
            "range": 61.50979772988143,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 151.2371948,
            "range": 51.88248845301072,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 171.55713139999997,
            "range": 40.36405884544325,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 179.352788,
            "range": 48.76038816642978,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 195.82071879999998,
            "range": 44.557871887629965,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 203.1031324,
            "range": 96.2740526363784,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 151.19218700000002,
            "range": 45.011966573355544,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.002683900293571837,
            "range": 0.0009741954452348655,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.002759463483009333,
            "range": 0.00024280679519122123,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0028399820567927224,
            "range": 0.005320535077313597,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012857378058433913,
            "range": 0.0013312194575047093,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003483993767333666,
            "range": 2.4040570918661946e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00033313301957000187,
            "range": 7.27580351684985e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003713181048365867,
            "range": 5.544009792958181e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01063224294678404,
            "range": 0.0013615694084078017,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.0591710067409796,
            "range": 0.23109517308613167,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0626856812647067,
            "range": 0.04687084536567541,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0716404687052488,
            "range": 0.1742707791839655,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.4787197421547453,
            "range": 0.12178194666595545,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.25966736108019706,
            "range": 2.1769215036991674,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0549751036228332,
            "range": 1.446643023495129,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0709865414916206,
            "range": 1.045912954206282,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1427216786487099,
            "range": 0.3506971143350071,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00019912104749740954,
            "range": 0.00021145676712149448,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00017701115242806705,
            "range": 3.267402848440098e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018681298931650942,
            "range": 1.4458170944314344e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024996078613519823,
            "range": 0.002948682139669237,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 9.14263955618386,
            "range": 23.47218739897728,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.895963237197927,
            "range": 14.196584791956061,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.14433809379745,
            "range": 23.252164832327647,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 19.29665841289044,
            "range": 18.99317831371578,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 6.548199477843506,
            "range": 38.783003974773735,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.650689904996588,
            "range": 9.374373837226079,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 9.091353478046043,
            "range": 8.415459110792261,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.18748266307994546,
            "range": 0.09274039930528277,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.168543802086242,
            "range": 44.66237816903144,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 18.261924116040422,
            "range": 39.89201047572838,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.836186969771305,
            "range": 48.68299503727142,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3088901042198662,
            "range": 2.643950842814814,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.795447205074135,
            "range": 50.7538323557234,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 20.143437583110984,
            "range": 44.31848222887246,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 20.232720725084175,
            "range": 43.9816972821364,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.119773639498192,
            "range": 9.425552229415729,
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
          "id": "708815762c96016a1aebc533c6bacf2c66b37390",
          "message": "Publish staged pack extensions atomically (#134)\n\nStage completed pack extensions in bounded local files and persist/publish each logical pack under one repository-locked Hibernate transaction. Preserve inline/chunked and legacy uncommitted compatibility, add failure cleanup and rollback coverage, and document the operational model.\n\nMeasured PostgreSQL incremental push improves by 10.7-17.4% across independent runs while transactions fall 10→7, statements 25→17 and locks 5→2 per invocation.\n\nCloses #133.",
          "timestamp": "2026-07-29T08:49:09+02:00",
          "tree_id": "81f52043c8e5a82c14c72c89c47420f997ebfe54",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/708815762c96016a1aebc533c6bacf2c66b37390"
        },
        "date": 1785308097459,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 20.3045434,
            "range": 11.051394702705528,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 23.1851532,
            "range": 21.961558837465706,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 23.2900232,
            "range": 19.40381771210442,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 20.616043599999998,
            "range": 10.075737359058891,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 34.229215999999994,
            "range": 23.883214869767595,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 39.2695394,
            "range": 22.52051461745849,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 37.311021999999994,
            "range": 14.056026839130677,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 32.0210328,
            "range": 129.18162684720815,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 133.57432479999997,
            "range": 47.550894691182826,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 132.793159,
            "range": 32.27255069580537,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 135.5847342,
            "range": 46.6618355552078,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 153.6486242,
            "range": 75.76117956580057,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 158.2074122,
            "range": 60.97618937582473,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 186.31320820000002,
            "range": 89.75002224610273,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 168.0141302,
            "range": 63.463213974588356,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 133.9089778,
            "range": 29.39089754085251,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0037262627938345907,
            "range": 0.0006346522388547556,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0036916887362260335,
            "range": 0.0004182258344148527,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.003802246090005229,
            "range": 0.0003297661432301331,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010178113534635108,
            "range": 0.0015274330280990284,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00041567388347629026,
            "range": 1.971455268686915e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00038810940071571054,
            "range": 4.9203362057328626e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00041279344897050043,
            "range": 2.8277500259034752e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.006480043796106169,
            "range": 0.0017651053622516658,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.9682304637465876,
            "range": 0.04219268687302915,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.9679262294476819,
            "range": 0.039703303779595654,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.1908411843552587,
            "range": 2.0422225933820215,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.204430343249345,
            "range": 0.3348586719884443,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.11400723054316246,
            "range": 1.2033253823372667,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.7139181235485514,
            "range": 1.9361698269610488,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.7372309946784132,
            "range": 1.916751355176466,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.06978595879661871,
            "range": 0.11408202485069269,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00016553992627322373,
            "range": 2.2424128703963886e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0001735283608994681,
            "range": 4.700502068410102e-06,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00017167704763039202,
            "range": 9.783251257371784e-06,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014678851058515621,
            "range": 0.0008907774731504023,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.191952896557197,
            "range": 18.790482245678927,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.842078560285566,
            "range": 39.558309502080796,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 9.34707548603374,
            "range": 44.602311957072565,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 8.455802066329966,
            "range": 16.643668267011172,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 4.809619310720142,
            "range": 25.270389419756995,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 6.686829056927878,
            "range": 19.617885409784652,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 5.275489363219697,
            "range": 16.190622706012668,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.19886525209554037,
            "range": 1.2197092490863584,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.418275835467568,
            "range": 29.09464868153905,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 17.873522584454687,
            "range": 145.47530328955006,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 18.608830404377457,
            "range": 58.462049335013475,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.7074053240680612,
            "range": 1.635185533696522,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.52452807899794,
            "range": 43.23587354178783,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 16.402070163317493,
            "range": 60.06024333174152,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 11.571360674695775,
            "range": 18.196149913515633,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 5.1148359102702665,
            "range": 30.641538661738927,
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
          "id": "c74ca3ab6336d7f08b4e5da68edb9a2e91bd21eb",
          "message": "Enable JDBC batching for pack chunks (#137)\n\nEnable JDBC batching for pack chunks",
          "timestamp": "2026-07-29T10:24:13+02:00",
          "tree_id": "7404092da0098ded9bf238aad8c374ba948ec200",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/c74ca3ab6336d7f08b4e5da68edb9a2e91bd21eb"
        },
        "date": 1785313890889,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 26.222988999999995,
            "range": 15.840026482466032,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 28.312967000000004,
            "range": 15.843189716371656,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 29.644144600000004,
            "range": 24.675092552793387,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 28.558228800000002,
            "range": 17.001937861624754,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 42.2330624,
            "range": 27.972877455366902,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 46.3975028,
            "range": 16.810477789326328,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 48.2507704,
            "range": 29.48789988021344,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 22.172691399999998,
            "range": 7.120826951409501,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 146.023239,
            "range": 43.99769087996271,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 145.5899866,
            "range": 40.68006931374879,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 149.28444139999996,
            "range": 55.99236275038587,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 167.13785579999998,
            "range": 40.98158383401491,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 168.72806999999997,
            "range": 40.445544835896335,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 188.0610096,
            "range": 51.848357241941464,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 185.77459439999998,
            "range": 41.53070623466141,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 149.8221662,
            "range": 31.742941791031818,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 511.3882888,
            "range": 149.41681906979414,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 486.82185039999996,
            "range": 40.01512031942128,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 484.57495120000004,
            "range": 15.73045811493549,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0027861153598874246,
            "range": 0.0009344511154138748,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.002760121432475159,
            "range": 0.0012004174421537763,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002719933622262624,
            "range": 0.00042456025264866115,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.013079032089808063,
            "range": 0.0021977609336431684,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00033731978037557534,
            "range": 2.7987587266169496e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00036909718031101604,
            "range": 0.00012464049365301799,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00035031773115568347,
            "range": 1.1752647324125963e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01074076224750419,
            "range": 0.0009885236630142312,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.0771564000286127,
            "range": 0.19485756712208746,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0723600639902742,
            "range": 0.16010478364033148,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0666808468741582,
            "range": 0.2327120491645777,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.5015204625136862,
            "range": 0.3963254731963688,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.28856487247937684,
            "range": 1.907945200573523,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0739563396538678,
            "range": 1.4645072034734667,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0633654124469722,
            "range": 1.5518905655134363,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.142321675940873,
            "range": 0.39470381166549234,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00018659479297896447,
            "range": 0.00020574297711234443,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00018059027324199964,
            "range": 6.07418825261406e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018695070895309072,
            "range": 0.00016645083638198635,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024561072398041395,
            "range": 0.004451865771885926,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.367875591833147,
            "range": 2.3383043319221386,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 9.579422177692464,
            "range": 14.698972766734755,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 9.150749269795702,
            "range": 7.104916162173228,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 19.623729409753647,
            "range": 3.5291235157665213,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 4.028580153470382,
            "range": 9.458715295261936,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 5.848453745871704,
            "range": 4.043558637394221,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 5.712584464645768,
            "range": 5.166120160521547,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.18636665192279614,
            "range": 0.23856345684350314,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.093949923141674,
            "range": 21.376388647994123,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.534819313816712,
            "range": 12.680603203536304,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.753104439568823,
            "range": 19.5816825451474,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.315799629577721,
            "range": 3.253921557878968,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.602782723777421,
            "range": 30.078235645507544,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 14.002170074260006,
            "range": 12.491145620368037,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 14.445553362261444,
            "range": 16.995311134566577,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.1611779087523475,
            "range": 9.65657279427208,
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
          "id": "ad637ba8960ebaf24d9ec61ed75a37f15c06c5cb",
          "message": "Remove pack-extension insert preflight query (#139)\n\nRemove pack-extension insert preflight query",
          "timestamp": "2026-07-29T10:47:07+02:00",
          "tree_id": "ff46888f0de6186453fd9388a5f57c8400be0942",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/ad637ba8960ebaf24d9ec61ed75a37f15c06c5cb"
        },
        "date": 1785315220448,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 29.0671878,
            "range": 13.427825437036434,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 29.694157800000006,
            "range": 13.404801020697802,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 29.194786599999997,
            "range": 14.505830847702343,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 29.47312,
            "range": 14.957175633295048,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 45.262554,
            "range": 18.761045097302603,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 50.2927722,
            "range": 26.831406068669235,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 49.778895,
            "range": 22.612684762751613,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 22.510408,
            "range": 9.263659857342278,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 159.0267504,
            "range": 34.40414763760407,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 163.5564918,
            "range": 47.41447680704529,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 165.94975480000002,
            "range": 53.13224613060836,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 183.1183768,
            "range": 34.03801901460361,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 185.4576002,
            "range": 45.99447929866066,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 207.0815062,
            "range": 70.13321937034412,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 198.40478620000002,
            "range": 36.42930300993306,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 167.7030084,
            "range": 65.13037018176432,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 477.1408576,
            "range": 27.521972609497645,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 480.560567,
            "range": 32.66476720680144,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 468.4342646,
            "range": 23.190553089860774,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0025918967992899153,
            "range": 0.0051511032355887176,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0024075121600851835,
            "range": 0.0004268748348318845,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0024578264855171592,
            "range": 0.0008576941462531228,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014549838117087986,
            "range": 0.0035643531283053413,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003600398402595142,
            "range": 9.980391638529578e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00038051194991648953,
            "range": 5.875052622574388e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00037648637899986734,
            "range": 0.00010985343959868179,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012182416806512901,
            "range": 0.0014326055971567331,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.133411405136516,
            "range": 0.054552978657677546,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.1296861112866086,
            "range": 0.024407731414754313,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.133469847553383,
            "range": 0.22479032248677436,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.5761236237026939,
            "range": 0.12358434942611683,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.23638271361891708,
            "range": 2.0990902380811156,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.9810210452981103,
            "range": 1.3833464698975537,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.9997484761581491,
            "range": 1.4472852171114365,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13440986674299438,
            "range": 0.42827665774724283,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00018610873271386177,
            "range": 1.0595091433549816e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00018643999797500973,
            "range": 3.14719410771845e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0001881658433002276,
            "range": 0.00011012064214292313,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024302220835443183,
            "range": 0.0010854442393812803,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.498854556667403,
            "range": 1.2033204860849138,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.762089881391512,
            "range": 2.6978676214225104,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 9.046872854717813,
            "range": 3.6264297430437407,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.9217362169716,
            "range": 26.963579036251385,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 3.410056152280628,
            "range": 6.3841300900975675,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 5.327689311072054,
            "range": 0.5728435597288183,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 5.301531077149256,
            "range": 8.466837365336442,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.16071051510887346,
            "range": 0.20583435136468886,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 9.847910033639762,
            "range": 35.38983945142169,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.292663006100796,
            "range": 36.32228469464988,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.7018968447546,
            "range": 38.88360089479018,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2419381687456925,
            "range": 2.1601545039526573,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.752006457524402,
            "range": 36.56423874642405,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 15.009887421497075,
            "range": 24.79144409257667,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 14.916804331700348,
            "range": 17.63866243213454,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.394165013226856,
            "range": 10.06382502052441,
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
          "id": "7e83487f7fda3440a2bdbade680566503c5a941f",
          "message": "Merge pull request #141 from carstenartur/perf/committed-pack-catalog\n\nCache committed pack metadata per DFS generation",
          "timestamp": "2026-07-29T13:54:12+02:00",
          "tree_id": "f6028b680cb56b3af4b521d7ceab0ef32245a7b0",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/7e83487f7fda3440a2bdbade680566503c5a941f"
        },
        "date": 1785326432350,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 24.3639332,
            "range": 13.244566906065439,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 30.4048776,
            "range": 34.39776986475336,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 27.2075838,
            "range": 14.011724598970725,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 27.5402264,
            "range": 8.577220777981795,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 39.5824216,
            "range": 26.15044834358334,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 42.2733016,
            "range": 30.75897273391271,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 40.908513,
            "range": 25.236333926099917,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 23.028584000000002,
            "range": 9.654634437412856,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 158.98444320000004,
            "range": 27.6040157198205,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 157.65761400000002,
            "range": 38.84690657755281,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 159.985601,
            "range": 39.79360218496907,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 185.69825899999998,
            "range": 59.471980017705455,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 183.1195956,
            "range": 50.196788945000385,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 194.68957699999999,
            "range": 34.62857161143231,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 198.7283994,
            "range": 45.15211386133996,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 161.19889419999998,
            "range": 22.771848056094647,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 525.5069522,
            "range": 41.86484550187731,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 526.5234172,
            "range": 22.49579811561715,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 526.3449966000001,
            "range": 27.008876904496812,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0025294868166526635,
            "range": 0.0005199430494911282,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0026179194449417304,
            "range": 0.00015981968110432993,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002585549479204086,
            "range": 0.0017645467044804478,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014447122155876612,
            "range": 0.0012383716424115543,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003516044221639726,
            "range": 5.1498822762777356e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00038192116037211914,
            "range": 5.481311869664876e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003365575957770906,
            "range": 3.6606906160242445e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012308384416418844,
            "range": 0.0008594133791938448,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.139129522811287,
            "range": 0.024503856932229392,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.14017936617382,
            "range": 0.23019756033202068,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.1428935605841335,
            "range": 0.024519033470604037,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.570268494752609,
            "range": 0.2805159374985168,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.21693865216043462,
            "range": 2.2230243958471765,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.049371034498457,
            "range": 1.2922573123531333,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0502358699444365,
            "range": 1.12524837510244,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.12629730692751837,
            "range": 0.23703054374386592,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00018352002525465932,
            "range": 3.734816416800223e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00020081551457037144,
            "range": 0.0002774130238765982,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0001824073666761156,
            "range": 2.290163565907512e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024235092046265533,
            "range": 0.002778466329764408,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 6.422146981554547,
            "range": 4.917886631774448,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 7.651722973875606,
            "range": 6.692589068517323,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 7.5693166957017155,
            "range": 1.924667725398214,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.962717491332498,
            "range": 3.1153354545564875,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 3.4604918770853526,
            "range": 2.7259250482262387,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 4.904356400406978,
            "range": 3.1513061739708492,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 5.160235724545562,
            "range": 3.996578362229269,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.17101454387295287,
            "range": 0.15367555266471605,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.587332800275375,
            "range": 32.04731399430484,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.684016800757576,
            "range": 28.82071266071107,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 11.902166091178557,
            "range": 21.91832930076121,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2448803002783222,
            "range": 1.872422545380056,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.607674927368608,
            "range": 21.467437998677077,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.552275372661528,
            "range": 19.221306821787618,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.441124150739435,
            "range": 14.447888190885488,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.584006517178314,
            "range": 6.698579186630191,
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
          "id": "34d8fbf3c744fa8783c273707da92f60a4189cb0",
          "message": "Merge pull request #142 from carstenartur/perf/preserve-jgit-pack-list\n\nHand off committed pack list after publication",
          "timestamp": "2026-07-29T14:52:26+02:00",
          "tree_id": "ccbf4d3021c8a93df53c473ac172fa3cee645dc5",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/34d8fbf3c744fa8783c273707da92f60a4189cb0"
        },
        "date": 1785329930628,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 25.650536,
            "range": 21.19412900387357,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 27.6772964,
            "range": 17.371832548629516,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 27.6882246,
            "range": 25.388881239315232,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 26.720259200000005,
            "range": 18.830601750221444,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 34.880343599999996,
            "range": 11.906290312726146,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 42.431450999999996,
            "range": 21.921702964848052,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 41.4446344,
            "range": 24.599949019840178,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 21.6989622,
            "range": 15.019590542375822,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 151.85239959999998,
            "range": 16.794588933325254,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 152.37607739999999,
            "range": 52.69520358639307,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 149.3282592,
            "range": 32.036950377184354,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 170.23409299999997,
            "range": 32.91473397468484,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 171.8437546,
            "range": 27.594434833831684,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 177.67407079999998,
            "range": 28.511819913354586,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 184.093776,
            "range": 29.330349170362922,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 155.923722,
            "range": 58.4672038573501,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 486.89240140000004,
            "range": 54.13598228469301,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 487.8038014,
            "range": 29.036349310139475,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 475.8959946,
            "range": 19.628644007898117,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.004125074703888958,
            "range": 0.001282560971446548,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0042090704984660025,
            "range": 0.0005700485574860711,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.004284879692518638,
            "range": 0.0010380224678982672,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.011087099040959803,
            "range": 0.0023742787668755232,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00044776742605837013,
            "range": 9.773336833182528e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00047671376548632794,
            "range": 0.00014013436915147364,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0004447989524981113,
            "range": 0.00010575052534563586,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.00712047883642191,
            "range": 0.0010668888190981424,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.207703145747853,
            "range": 0.10083878691623978,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.205815880843046,
            "range": 0.10666485424909541,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.2054412761743822,
            "range": 0.17865210478829732,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.5194671613773325,
            "range": 0.2915219316530312,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.24731884225488907,
            "range": 1.646901509762578,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.886304687299318,
            "range": 1.8800794771200346,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.9295360310390796,
            "range": 1.4577418234023418,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.08951365225618362,
            "range": 0.12870123653262908,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0002476249587438584,
            "range": 0.00011890571593070319,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0002136317070507599,
            "range": 1.2271146587743832e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0002149362457241201,
            "range": 0.0001202099739673661,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01894791626807771,
            "range": 0.0033639574082664453,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.0135265144324235,
            "range": 2.5265794094189364,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 7.048838789351852,
            "range": 0.93674450066765,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 6.879300550901877,
            "range": 6.211033936332205,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 10.526898006917788,
            "range": 2.9814708503782734,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 3.920952179067772,
            "range": 10.694952021393819,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 3.543834820103946,
            "range": 1.2578327002030751,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 3.7356376437944014,
            "range": 7.976218330557335,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.10276899893332399,
            "range": 0.09120868726658382,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.01194947702349,
            "range": 36.371343214995456,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.066842452746554,
            "range": 20.339063994017238,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 11.97279281573413,
            "range": 27.274667581245872,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.8972493667504805,
            "range": 1.6160953635528639,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 11.762357752356058,
            "range": 35.490874153587335,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 13.253067967323444,
            "range": 27.147686752159125,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.44958565703182,
            "range": 15.588859815524067,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 4.509014313044875,
            "range": 13.411820002940773,
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
          "id": "0d2b6698102486d6ec364839c7a00477fc1aff40",
          "message": "Merge pull request #144 from carstenartur/issue-143\n\nAttribute pack-file database reads by extension",
          "timestamp": "2026-07-29T17:31:19+02:00",
          "tree_id": "7305f4d3b62cd605c41c8cd2fe9c1eeb39b7f08c",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/0d2b6698102486d6ec364839c7a00477fc1aff40"
        },
        "date": 1785339467326,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 23.7843572,
            "range": 14.61385433437366,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 27.9145794,
            "range": 25.119836564509185,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 27.812131,
            "range": 26.948787003325993,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 26.6308342,
            "range": 15.776362601051765,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 37.7883214,
            "range": 30.552841196956898,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 40.256715400000004,
            "range": 27.348485486269592,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 38.745235,
            "range": 23.159228246617953,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 22.649563399999998,
            "range": 11.01762777368635,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 146.3049348,
            "range": 38.174536890601324,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 147.1959756,
            "range": 61.432510149315405,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 145.4918342,
            "range": 35.956005237500825,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 168.1797264,
            "range": 55.23062124545379,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 162.5199368,
            "range": 66.13752723385794,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 184.0013032,
            "range": 102.5314088575471,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 180.21098220000002,
            "range": 34.291436737391166,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 150.608632,
            "range": 39.85850762880142,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 472.4243176,
            "range": 30.596472469735488,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 477.1159264,
            "range": 14.175399503855889,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 469.19334420000007,
            "range": 43.43256084499332,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0026487239905384485,
            "range": 0.0007631837139077601,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0027467036070072406,
            "range": 0.004270119780408079,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002644709554827831,
            "range": 0.0008819832349991765,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012880866489769249,
            "range": 0.002332863139465384,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003377321926129953,
            "range": 5.2214643630803614e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003874812666995792,
            "range": 4.370098697049367e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003625830746575962,
            "range": 9.026245891205531e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010570715295929711,
            "range": 0.0008354720662896716,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.0575928886803816,
            "range": 0.09424054367593092,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.1591054214217653,
            "range": 0.3627454304930301,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0599767378971932,
            "range": 0.06916309446752641,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.4820793325684993,
            "range": 0.1424177284594847,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.3234515272890834,
            "range": 2.4000490405662487,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.1235001799741047,
            "range": 1.3020018822740738,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.124886664314174,
            "range": 1.331404222758177,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1363755256480574,
            "range": 0.3021828548184282,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0001781676586827185,
            "range": 7.836560890185572e-06,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0001904348167547154,
            "range": 0.0002774195694130227,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018849412008436174,
            "range": 0.00027361534915816793,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02425559741636989,
            "range": 0.004836724946616458,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.159479221755668,
            "range": 3.654057403888705,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 6.673014376732525,
            "range": 5.980189163496076,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 6.97699021446397,
            "range": 8.57489478778097,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 18.328136029986524,
            "range": 8.971591127482029,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 2.6055812490928005,
            "range": 1.9242632123232164,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 3.3017226702261877,
            "range": 3.8617299288299236,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 3.3200958074354348,
            "range": 1.1735277149229513,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1773411985409021,
            "range": 0.04522835974824491,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 7.963921724364286,
            "range": 33.8376799017851,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 10.73170553191951,
            "range": 23.228587153810974,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 10.611880885150212,
            "range": 21.098560752371203,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2664925951129449,
            "range": 2.842247113610132,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.814734995692138,
            "range": 24.949560880631747,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.030452479251116,
            "range": 16.24992881233638,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.52250676122341,
            "range": 16.816921988434945,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 6.600264715267108,
            "range": 9.146134171615918,
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
          "id": "88b728f249e4c1e807a14e954db188699d46f92d",
          "message": "Hand off local inline payloads and remove redundant indexes (#149)\n\nEliminate the measured local PACK/Reftable fallback reads with a hard-bounded committed-identity handoff, remove redundant pack/chunk/reflog indexes, and add the ordered dialect-specific reflog access path.\n\nCloses #145.\nCloses #147.",
          "timestamp": "2026-07-29T19:23:11+02:00",
          "tree_id": "855ada511a63e92c35bd1220b255328a3277cd58",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/88b728f249e4c1e807a14e954db188699d46f92d"
        },
        "date": 1785346221902,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 21.0245618,
            "range": 5.5327462245940735,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 25.001309600000003,
            "range": 17.525312704143,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 26.135949200000006,
            "range": 25.876239176915554,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 26.488723399999998,
            "range": 10.344351310340114,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 32.2965916,
            "range": 15.401224049936634,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 37.6268994,
            "range": 22.795127454898648,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 35.993778400000004,
            "range": 26.571816363741128,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 23.0113334,
            "range": 13.045424896231635,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 148.8979536,
            "range": 70.63539413928324,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 144.6054296,
            "range": 40.66553413569346,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 149.72334,
            "range": 54.02851167261692,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 170.29034500000003,
            "range": 63.23453687936749,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 162.6899928,
            "range": 45.17594662157453,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 178.285756,
            "range": 41.75060598855078,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 182.4478108,
            "range": 74.83617138788377,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 151.29374839999997,
            "range": 70.39638733793502,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 473.727162,
            "range": 18.96904505540603,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 475.946226,
            "range": 23.883945897902755,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 472.9029152,
            "range": 23.001737545937782,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0026789761595489184,
            "range": 0.00033296706582956943,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0027415606567708795,
            "range": 0.0005791601596385013,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002715224295549172,
            "range": 0.0006430430696034355,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012887371952855857,
            "range": 0.0023377894171130445,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.00037609110414767237,
            "range": 9.390903272874623e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003746687956463622,
            "range": 7.961969899575814e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003861181997611909,
            "range": 8.440415995225386e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.01067977832304047,
            "range": 0.0012775177517555763,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.0633573212730807,
            "range": 0.043988304889274396,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.071348658614123,
            "range": 0.7002034021712968,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0682171699255416,
            "range": 0.3948431228781377,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.473380638073296,
            "range": 0.2056336491648868,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.361849635106739,
            "range": 1.6394909952827064,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.1540649176444218,
            "range": 1.5455852420407679,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.1386831081998947,
            "range": 1.644726122899078,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1372327674300662,
            "range": 0.3324796989922538,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0001829241340324903,
            "range": 2.24372911180339e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0001833475541508719,
            "range": 4.032187825131892e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018771611777988078,
            "range": 0.00010335312676803475,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02465230393187993,
            "range": 0.003380393243464426,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 6.744543500037366,
            "range": 5.898608640493375,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 6.8812608846595005,
            "range": 6.392169338606538,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 7.164005098132879,
            "range": 5.180503536354761,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 19.45897081983485,
            "range": 17.722184919655387,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 3.4862176633267765,
            "range": 7.846536148889963,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 3.2550886658714226,
            "range": 3.0641894593858137,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 3.4317886573433256,
            "range": 4.160806092086372,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.18108736127849312,
            "range": 0.23958549210419608,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.469059154581172,
            "range": 30.48735526231503,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 11.259861403808308,
            "range": 16.86425462694308,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 10.931327919195402,
            "range": 13.896752665493633,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3037339847189375,
            "range": 2.885169662462899,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 9.744989719949128,
            "range": 15.324860555742259,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.3892369933141,
            "range": 9.191486246098455,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 11.832773621184089,
            "range": 32.63865280342923,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.191137386470888,
            "range": 11.409476419688204,
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
          "id": "a9873da18017e4014a9086b2baff4c6c33d03413",
          "message": "Delete replaced packs through the database cascade (#151)\n\nCollapse replacement and legacy rollback cleanup to one repository-scoped parent bulk delete and rely on the established ON DELETE CASCADE contract for chunk rows. Cover multi-pack replacement and child cleanup explicitly.",
          "timestamp": "2026-07-31T19:50:01+02:00",
          "tree_id": "b5ef14806e3b591c452681215e01ef0537fe83e5",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/a9873da18017e4014a9086b2baff4c6c33d03413"
        },
        "date": 1785520640738,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 24.1316178,
            "range": 9.700049269695029,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 26.135245399999995,
            "range": 16.516308549358673,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 25.6157934,
            "range": 25.728276066453873,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 26.9212062,
            "range": 11.30058770578376,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 38.0063402,
            "range": 36.28161049575159,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 39.2585984,
            "range": 22.916440890538244,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 38.376285,
            "range": 33.092237626420896,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 21.795008799999998,
            "range": 14.866328243630909,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 161.2141438,
            "range": 39.50904858069268,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 161.987688,
            "range": 56.001055318335446,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 162.4969736,
            "range": 58.11658122488309,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 180.30914280000002,
            "range": 38.81767152482514,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 174.65474460000001,
            "range": 47.995250770617055,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 193.2592758,
            "range": 56.00107256021345,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 187.9091172,
            "range": 54.74735013088206,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 162.87573179999998,
            "range": 34.66652811090264,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 534.4829284,
            "range": 101.52483385310198,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 529.7927576,
            "range": 28.095040292533437,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 521.6286594000001,
            "range": 12.7937695224221,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.002440608868547479,
            "range": 0.00019456049249138297,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0024737376210028357,
            "range": 0.0015462715534533794,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0025856492493975818,
            "range": 0.005372414664724886,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.014336617303732951,
            "range": 0.00112726139566908,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003517160084031551,
            "range": 0.000202812330296528,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0003723182909689651,
            "range": 0.00012453955221548423,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003813838807528482,
            "range": 4.363758073843172e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.012241045285050734,
            "range": 0.00010532739783845286,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.1310832137644544,
            "range": 0.0924836960159096,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.1626084720720828,
            "range": 0.8894079692600558,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.1363184117462537,
            "range": 0.06246601598728446,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.5789542595123585,
            "range": 0.3473554198092089,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.32593833325451643,
            "range": 1.8972891773056744,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0582359212739136,
            "range": 1.6998278258788886,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0897720788370286,
            "range": 2.1955555986351443,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.13364514581326056,
            "range": 0.41815687663474205,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0001855139612042722,
            "range": 5.424363166572514e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00019125824683720037,
            "range": 0.00021839673790388992,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018914060807211064,
            "range": 0.0001826961637622444,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.024299344983831506,
            "range": 0.0016898864868080475,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.28661632301366,
            "range": 13.164473657572247,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.556752001847665,
            "range": 2.9467632601297082,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 7.457981842689772,
            "range": 6.8608068148449055,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 17.420081508045165,
            "range": 17.651785718093432,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 3.038749485259156,
            "range": 4.82175675690571,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 4.317058166419162,
            "range": 12.678952247809026,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 4.581885275597002,
            "range": 12.034458528933206,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.16975041768476706,
            "range": 0.062262251324966884,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.515016536411759,
            "range": 46.52337582344798,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 10.490955273294178,
            "range": 4.282046240476145,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.304806591116423,
            "range": 17.639024136640092,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.2195962039713875,
            "range": 2.0407219649563335,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 13.545915990727233,
            "range": 81.78414465122933,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.348146598445062,
            "range": 8.276590501237735,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 12.372836623773061,
            "range": 7.4888465940763265,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.3120555659841004,
            "range": 12.546516752905463,
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
          "id": "e09c2bcca9fc0472d7dd6cc21dcc1dde56abbaec",
          "message": "Persist complete JGit pack description metadata (#153)\n\nPersist logical DFS pack metadata across repository reopen, retain conservative legacy fallbacks, and verify clean and adopted schemas through Core 0.1.18.",
          "timestamp": "2026-07-31T22:17:31+02:00",
          "tree_id": "f850d5fea8665c0b8587c1b16107041227a56111",
          "url": "https://github.com/carstenartur/jgit-storage-hibernate/commit/e09c2bcca9fc0472d7dd6cc21dcc1dde56abbaec"
        },
        "date": 1785529440116,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "incrementalFetchViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 23.582828999999997,
            "range": 10.62422197682311,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 25.502536,
            "range": 15.705057600230607,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 25.1587688,
            "range": 24.11015178473891,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalFetchViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 29.624328400000003,
            "range": 16.073438919786504,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 35.3649804,
            "range": 29.338691484340075,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 36.945781999999994,
            "range": 34.95741092843356,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 38.488842399999996,
            "range": 32.91806639260161,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "incrementalPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 23.4546468,
            "range": 12.089915469120957,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 149.6861224,
            "range": 49.376817129581646,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 147.3388374,
            "range": 44.44782950880894,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 152.75305379999998,
            "range": 65.10797445414092,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialCloneViaUploadPack — JGit + filesystem",
            "unit": "ms/op",
            "value": 171.68579240000003,
            "range": 57.60219976762438,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 165.9673608,
            "range": 59.01125480934652,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 175.6556942,
            "range": 37.308882176041415,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 177.6663714,
            "range": 48.57488938581826,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "initialPushViaReceivePack — JGit + filesystem",
            "unit": "ms/op",
            "value": 156.0883738,
            "range": 88.06682132625903,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)",
            "unit": "ms/op",
            "value": 524.3949996,
            "range": 42.999140964938285,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching + rewrite)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)",
            "unit": "ms/op",
            "value": 529.269599,
            "range": 30.524597869218017,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching off)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)",
            "unit": "ms/op",
            "value": 559.7063740000001,
            "range": 257.46903726891827,
            "extra": "Backend: JGit + PostgreSQL (JDBC batching on)\nJDK: 21.0.11\nMode: ss\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.003457789176066575,
            "range": 0.002036553720922996,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.0027739422676572277,
            "range": 0.0006294469748504989,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.002757346313593237,
            "range": 0.0005952969678393805,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.013323509557265217,
            "range": 0.0008353536107635375,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0003681140302297249,
            "range": 4.247576989159459e-05,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00037570121117254645,
            "range": 1.7054046777184007e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.0003901580323220415,
            "range": 4.968072741238834e-05,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readBlobFromWarmCache — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.010743672598701232,
            "range": 0.001504461139966193,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 1.081802351321261,
            "range": 0.17282755380581358,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.0691691314313296,
            "range": 0.17415224735610343,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.0663370773268932,
            "range": 0.12657838062693416,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "readLargeBlobSequentiallyAfterJGitCacheReset — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.5062829611349589,
            "range": 0.12013943646212964,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.3260103130190228,
            "range": 1.470788164811126,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 1.2180379614767511,
            "range": 1.4547495200455582,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 1.2227088612549453,
            "range": 1.6143645827041426,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "reopenAndResolveMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.14120549859819254,
            "range": 0.4876354754500689,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 0.0001937745534281659,
            "range": 0.0002203112564382696,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 0.00018817632204345727,
            "range": 7.704571865640696e-05,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 0.00018443971571060363,
            "range": 0.0001191186855356655,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "resolveMainOnOpenRepository — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.02478000705813745,
            "range": 0.013959873479029241,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 8.265992480501575,
            "range": 6.219234169996145,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 8.394580681307822,
            "range": 4.603184939331982,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 8.359244702374061,
            "range": 3.7721684881023463,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBatchOf100Blobs — JGit + filesystem",
            "unit": "ms/op",
            "value": 18.975126371160613,
            "range": 37.80723551343592,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 3.745444163527896,
            "range": 8.411220673569211,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 4.240186739328955,
            "range": 5.853300557276799,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 3.849776531489526,
            "range": 5.22772994826237,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeBlob — JGit + filesystem",
            "unit": "ms/op",
            "value": 0.1871251209669643,
            "range": 0.19880158552844934,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 10.598770688088353,
            "range": 7.084963071260883,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 12.757599434052715,
            "range": 22.726227612463905,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.475062266387601,
            "range": 21.627801674606317,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitAndUpdateRef — JGit + filesystem",
            "unit": "ms/op",
            "value": 1.3640417299159893,
            "range": 2.4938906013314566,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + HSQLDB (in-memory)",
            "unit": "ms/op",
            "value": 12.12932619788352,
            "range": 25.83880392256741,
            "extra": "Backend: JGit + HSQLDB (in-memory)\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL",
            "unit": "ms/op",
            "value": 14.744237662059097,
            "range": 18.497336539125467,
            "extra": "Backend: JGit + PostgreSQL\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + PostgreSQL + HikariCP",
            "unit": "ms/op",
            "value": 13.744268205768279,
            "range": 9.87311797911834,
            "extra": "Backend: JGit + PostgreSQL + HikariCP\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          },
          {
            "name": "writeCommitSeries10AndUpdateMain — JGit + filesystem",
            "unit": "ms/op",
            "value": 7.213877885595035,
            "range": 10.543335697514172,
            "extra": "Backend: JGit + filesystem\nJDK: 21.0.11\nMode: avgt\nForks: 1"
          }
        ]
      }
    ]
  }
}
