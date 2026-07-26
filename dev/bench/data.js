window.BENCHMARK_DATA = {
  "lastUpdate": 1785052169354,
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
      }
    ]
  }
}