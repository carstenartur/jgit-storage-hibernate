#!/usr/bin/env python3
"""Verify retained consumer evidence offline; never rerun a workload or mutate source."""
from pathlib import Path
import csv
import gzip
import hashlib
import importlib.util
import json
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load(path):
    return json.loads(path.read_text())


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def unpack(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zipped:
        for member in zipped.infolist():
            assert (destination / member.filename).resolve().is_relative_to(destination.resolve())
            assert member.external_attr >> 16 & 0o170000 != 0o120000
        zipped.extractall(destination)


def junit(directory):
    totals = dict(tests=0, errors=0, failures=0, skipped=0)
    for path in directory.rglob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        assert suite.tag == "testsuite"
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    return totals


def check_contracts(directory, descriptor, source_sha, candidate_digest, logs, candidate_id):
    assert source_sha in logs["build"]
    assert candidate_digest in logs["build"]
    total = 0
    for consumer in descriptor["consumers"]:
        name = consumer["id"]
        assert source_sha in logs[name] and candidate_digest in logs[name]
        assert str(candidate_id) in logs[name]
        for mode in ("baseline", "candidate"):
            artifact = directory / ("consumer-" + mode + "-" + name)
            evidence = artifact / "consumer-evidence" / name
            assert (evidence / "consumer-commit.txt").read_text().strip() == consumer["ref"]
            result = load(evidence / "result.json")
            assert result["mode"] == mode
            expected_version = "0.12.1-SNAPSHOT" if mode == "candidate" else "0.11.8"
            if mode == "candidate":
                assert result["candidateVersion"] == expected_version
                substitution = load(evidence / "substitution.json")
                assert set(substitution["modules"]) == set(consumer["expectedModules"])
                assert all(path.endswith("pom.xml") for path in substitution["changedFiles"])
            dependencies = re.findall(r"io.github.carstenartur:(jgit-storage-hibernate-[^:]+):jar:([^:\s]+):",
                                      (evidence / "dependency-tree.txt").read_text())
            assert {name for name, _ in dependencies} == set(consumer["expectedModules"])
            assert all(version == expected_version for _, version in dependencies)
            counts = junit(artifact)
            assert counts == dict(tests={"audio-analyzer": 1096, "taxonomy": 41, "sandbox": 210}[name],
                                  errors=0, failures=0, skipped=0)
            if mode == "candidate":
                total += counts["tests"]
    return total


def main():
    manifest = load(ROOT / "manifest.json")
    report = load(ROOT / "qualified-evidence.json")
    for relative, expected in manifest["filesSha256"].items():
        path = ROOT / relative
        assert path.resolve().is_relative_to(ROOT.resolve())
        assert digest(path.read_bytes()) == expected, relative
    for item in manifest["provenance"]:
        assert digest(gzip.decompress((ROOT / item["file"]).read_bytes())) == item["uncompressedSha256"]
    for item in manifest["sources"]:
        assert digest((ROOT / item["file"]).read_bytes()) == item["sha256"]
    meta = json.loads(gzip.decompress((ROOT / "provenance/final-api-evidence.json.gz").read_bytes()))
    source = json.loads(gzip.decompress((ROOT / "provenance/source-api-evidence.json.gz").read_bytes()))
    for key in ("nativeRun", "contractRun"):
        run = meta[key]
        assert run["head_sha"] == manifest["sourceSha"] and run["event"] == "push"
        assert run["run_attempt"] == 1 and run["conclusion"] == "success"
    for key, count in (("nativeJobs", 7), ("contractJobs", 9)):
        assert len(meta[key]) == count and all(job["conclusion"] == "success" for job in meta[key])
    gate = module("publication_gate", ROOT / "sources/consumer-publication-evidence.py")
    summarizer = module("contract_summary", ROOT / "sources/summarize-consumer-compatibility.py")
    descriptor_path = ROOT / "sources/consumer-compatibility.json"
    descriptor = load(descriptor_path)
    candidate = next(a for a in manifest["artifacts"] if not a["retained"])
    assert candidate["name"] == "jgit-storage-hibernate-consumer-candidate"
    assert candidate["sha256"] == "5bcd755e2e345b48c6a95c82f783e0135b5cd75f9ee78c624753fe12906940eb"
    retained = 0
    with tempfile.TemporaryDirectory(prefix="consumer-evidence-") as temporary:
        work = Path(temporary)
        for artifact in manifest["artifacts"]:
            if not artifact["retained"]:
                continue
            path = ROOT / artifact["file"]
            assert path.stat().st_size == artifact["sizeBytes"]
            assert digest(path.read_bytes()) == artifact["sha256"]
            kind = "native" if artifact["runId"] == manifest["nativeRunId"] else "contracts"
            unpack(path, work / kind / artifact["name"])
            retained += 1
        assert retained == 14
        native_observations = 0
        source_sets = []
        for fixture_path in (work / "native").rglob("fixture.json"):
            fixture, observations = gate.verify_artifact(fixture_path.parent)
            assert fixture["harnessSha"] == manifest["sourceSha"]
            assert fixture["librarySha"] == manifest["roles"][fixture["role"]]
            assert fixture["workflowRunId"] == str(manifest["nativeRunId"])
            for module_name, expected in fixture["libraryProductionTreeHashes"].items():
                entries = source["rootTrees"][fixture["role"]]["tree"]
                assert next(e["sha"] for e in entries if e["path"] == module_name) == expected
            snapshots = fixture_path.parent / "harness-source"
            source_sets.append({str(p.relative_to(snapshots)): digest(p.read_bytes())
                                for p in snapshots.rglob("*.java")})
            gate.privacy([fixture_path.parents[2]])
            native_observations += len(observations)
        assert len(source_sets) == 6 and all(len(s) == 21 and s == source_sets[0] for s in source_sets)
        assert native_observations == 54
        comparison = gate.compare(work / "native")
        assert comparison == load(work / "native/consumer-publication-comparison/comparison.json")
        assert comparison["mainProductionModulesIdenticalToProposed"]
        assert junit(work / "native") == dict(tests=72, errors=0, failures=0, skipped=0)
        logs = {name: gzip.decompress((ROOT / "provenance" / ("consumer-candidate-" + name + ".log.gz")).read_bytes()).decode()
                for name in ("build", "audio-analyzer", "taxonomy", "sandbox")}
        for name in ("audio-analyzer", "taxonomy", "sandbox"):
            assert "SHA256 digest of downloaded artifact is " + candidate["sha256"] in logs[name]
        assert "SHA256 digest of uploaded artifact is " + candidate["sha256"] in logs["build"]
        assert check_contracts(work / "contracts", descriptor, manifest["sourceSha"], candidate["sha256"], logs, candidate["id"]) == 1347
        summary = summarizer.build_summary(descriptor_path, work / "contracts",
            "https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42431132838#artifacts")
        assert summary == (work / "contracts/consumer-compatibility-summary/summary.md").read_text()
        for contract in report["compatibilityContracts"]:
            for check in contract["checks"].values():
                for item in check["junitFiles"]:
                    assert digest((work / item["path"]).read_bytes()) == item["sha256"]

        previous = ROOT / "previous-integrated"
        old = load(previous / "consumer-211-final-ci-review.json")
        assert old["checkoutSha"] == manifest["priorQualifiedSource"]
        assert old["checkoutTree"] == manifest["priorQualifiedTree"]
        old_root = work / "previous"
        for artifact in old["artifacts"]:
            archive = previous / artifact["localFile"]
            assert "sha256:" + digest(archive.read_bytes()) == artifact["digest"]
            unpack(archive, old_root / artifact["name"])
        old_observations = 0
        for path in old_root.rglob("fixture.json"):
            fixture = load(path)
            assert fixture["harnessSha"] == old["checkoutSha"]
            assert fixture.get("checkoutSha", old["checkoutSha"]) == old["checkoutSha"]
            for name, expected in fixture.get("filesSha256", fixture.get("sha256", {})).items():
                assert digest((path.parent / name).read_bytes()) == expected
            for result in path.parent.glob("*.json"):
                raw = load(result)
                if isinstance(raw, list) and raw and "benchmark" in raw[0]:
                    old_observations += sum(len(values) for row in raw for values in row["primaryMetric"]["rawData"])
        assert old_observations == 23
        old_logs = {name: gzip.decompress((previous / (filename + ".gz")).read_bytes()).decode() for name, filename in {
            "build": "candidate-build-provenance.log", "audio-analyzer": "candidate-audio-provenance.log",
            "taxonomy": "candidate-taxonomy-provenance.log", "sandbox": "candidate-sandbox-provenance.log"}.items()}
        for name in ("audio-analyzer", "taxonomy", "sandbox"):
            old_logs[name] += gzip.decompress((previous / ("candidate-" + name + "-full-checkout-provenance.log.gz")).read_bytes()).decode()
        old_candidate = old["candidateArtifact"]
        assert check_contracts(old_root, descriptor, old["checkoutSha"], old_candidate["digest"].split(":", 1)[1], old_logs, old_candidate["id"]) == 1347

    with (ROOT / "observations.csv").open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    assert len(rows) == 54 and len({(r["role"], r["operation"], r["requested_rtt_ms"], r["coordinate"], r["publication_workers"], r["measurement"]) for r in rows}) == 54
    assert all(r["harness_sha"] == manifest["sourceSha"] for r in rows)
    print(json.dumps(dict(retainedCurrentArchives=retained, priorArchives=len(old["artifacts"]),
                          nativeObservations=native_observations, priorQualifiedObservations=old_observations,
                          nativeJunitTests=72, candidateTestsPerSource=1347, baselineTestsPerSource=1347,
                          qualification="passed", candidateBinaryRetained=False)))


if __name__ == "__main__":
    main()
