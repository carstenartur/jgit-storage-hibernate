window.BENCHMARK_DATA = {
  "lastUpdate": 1785273849231,
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
      }
    ]
  }
}
