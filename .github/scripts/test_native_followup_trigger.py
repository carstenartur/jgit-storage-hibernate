#!/usr/bin/env python3
"""Check the event-selection behavior of the explicit native follow-up push."""

import json
from pathlib import Path
import re
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).parents[2]
REF = "refs/heads/codex/performance-followup-20261108"


def workflow(name):
    return (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


def section(text, marker, next_marker):
    if marker not in text:
        raise AssertionError(f"Missing workflow section {marker!r}")
    return re.split(next_marker, text.split(marker, 1)[1], maxsplit=1, flags=re.MULTILINE)[0]


def condition(text, indent):
    match = re.search(r"^" + " " * indent + r"if: (.+)$", text, re.MULTILINE)
    if match is None:
        return "True"
    if match[1] not in (">-", ">", "|"):
        return match[1]
    continuation = []
    for line in text[match.end():].splitlines():
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        continuation.append(line.strip())
    return " ".join(continuation)


def evaluate(expression, event, ref=REF, deleted=False, profile="", mode=""):
    # These trusted, repository-owned selection expressions use only boolean
    # comparisons and the JSON matrix helper; this is not a general Actions VM.
    expression = expression.removeprefix("${{").removesuffix("}}").strip()
    expression = re.sub(r"!(?!=)", " not ", expression).replace("&&", " and ").replace("||", " or ")
    return eval(expression.strip(), {"__builtins__": {}}, {
        "github": SimpleNamespace(event_name=event, ref=ref, event=SimpleNamespace(deleted=deleted)),
        "inputs": SimpleNamespace(profile=profile, mode=mode), "fromJSON": json.loads,
        "always": lambda: True,
        "True": True, "False": False,
    })


def job_enabled(name, job, event, **kwargs):
    body = section(workflow(name), "  " + job + ":\n", r"^  [a-zA-Z0-9_-]+:\s*$")
    return bool(evaluate(condition(body, 4), event, **kwargs))


def step_enabled(name, step, event, **kwargs):
    body = section(workflow(name), "      - name: " + step + "\n", r"^      - ")
    return bool(evaluate(condition(body, 8), event, **kwargs))


class NativeFollowupTriggerTest(unittest.TestCase):
    def test_controlled_reftable_comparison_selects_only_the_eight_fork_job(self):
        name = "performance-investigations.yml"
        ref = "refs/heads/codex/publication-reftable-paired-20261108"
        for event in ("push", "workflow_dispatch"):
            self.assertTrue(job_enabled(name, "publication-reftable-comparison", event, ref=ref,
                                        profile="publication-reftable-comparison"))
            for job in ("investigate", "repository-aging-smoke", "repository-aging-full-shard",
                        "stateless-threshold", "publication-nonregression", "publication-gc-diagnostic"):
                self.assertFalse(job_enabled(name, job, event, ref=ref,
                                             profile="publication-reftable-comparison"), job)
        for event, candidate in (("push", ref + "-extra"), ("push", "refs/heads/main"),
                                 ("pull_request", ref), ("schedule", ref)):
            self.assertFalse(job_enabled(name, "publication-reftable-comparison", event, ref=candidate))
        self.assertFalse(job_enabled(name, "publication-reftable-comparison", "push", ref=ref, deleted=True))

    def test_each_required_workflow_accepts_only_the_exact_named_branch(self):
        for name in ("performance-investigations.yml", "search-runtime-performance.yml",
                     "consumer-taxonomy-postgres.yml", "consumer-compatibility.yml"):
            push = section(workflow(name), "  push:\n", r"^  [a-zA-Z0-9_-]+:\s*$|^\S")
            with self.subTest(workflow=name):
                self.assertIn(REF.removeprefix("refs/heads/"), push)
                self.assertNotIn("performance-followup-*", push)

    def test_named_push_selects_only_the_paired_performance_cells(self):
        name = "performance-investigations.yml"
        for job in ("investigate", "repository-aging-smoke", "repository-aging-full-shard", "stateless-threshold"):
            with self.subTest(job=job):
                self.assertFalse(job_enabled(name, job, "push"))
        self.assertTrue(job_enabled(name, "publication-nonregression", "push"))

    def test_named_push_selects_only_the_sustained_search_cells(self):
        name = "search-runtime-performance.yml"
        self.assertFalse(job_enabled(name, "select-runtime-runs", "push"))
        self.assertTrue(job_enabled(name, "sustained-diagnostic", "push"))

    def test_named_push_selects_complete_native_consumer_comparison(self):
        name = "consumer-taxonomy-postgres.yml"
        self.assertTrue(step_enabled(name, "Verify and measure bounded native publications", "push"))
        self.assertFalse(step_enabled(name, "Verify lifecycle and measure queries and rebuild", "push"))
        self.assertTrue(job_enabled(name, "compare-publication", "push"))
        matrix = re.search(r"^        library: (.+)$", workflow(name), re.MULTILINE)[1]
        self.assertEqual(evaluate(matrix, "push"), ["release", "main", "proposed"])
        mode = re.search(r"^      FOLLOWUP_MODE: (.+)$", workflow(name), re.MULTILINE)[1]
        self.assertEqual(evaluate(mode, "push"), "publication-followup")

    def test_branch_deletion_and_other_branches_do_not_start_targeted_studies(self):
        for name, job in (("performance-investigations.yml", "publication-nonregression"),
                          ("search-runtime-performance.yml", "sustained-diagnostic"),
                          ("consumer-taxonomy-postgres.yml", "compare-publication")):
            with self.subTest(workflow=name):
                self.assertFalse(job_enabled(name, job, "push", deleted=True))
                self.assertFalse(job_enabled(name, job, "push", ref=REF + "-extra"))
                self.assertFalse(job_enabled(name, job, "push", ref="refs/heads/main"))
        self.assertFalse(job_enabled("consumer-taxonomy-postgres.yml", "measure", "push", deleted=True))
        for job in ("prepare-candidate", "candidate", "prepare-baseline", "baseline", "report"):
            with self.subTest(consumer_contract=job):
                self.assertFalse(job_enabled("consumer-compatibility.yml", job, "push", deleted=True))
                self.assertTrue(job_enabled("consumer-compatibility.yml", job, "push"))

    def test_manual_modes_on_the_named_branch_keep_their_original_meaning(self):
        self.assertFalse(job_enabled("performance-investigations.yml", "publication-nonregression",
                                     "workflow_dispatch", profile="smoke"))
        self.assertTrue(job_enabled("performance-investigations.yml", "investigate",
                                    "workflow_dispatch", profile="smoke"))
        self.assertTrue(job_enabled("performance-investigations.yml", "publication-nonregression",
                                    "workflow_dispatch", profile="publication-nonregression"))
        self.assertFalse(job_enabled("search-runtime-performance.yml", "sustained-diagnostic",
                                     "workflow_dispatch", mode="matrix"))
        self.assertTrue(job_enabled("search-runtime-performance.yml", "select-runtime-runs",
                                    "workflow_dispatch", mode="matrix"))
        self.assertTrue(job_enabled("search-runtime-performance.yml", "sustained-diagnostic",
                                    "workflow_dispatch", mode="sustained"))
        self.assertFalse(step_enabled("consumer-taxonomy-postgres.yml", "Verify and measure bounded native publications",
                                      "workflow_dispatch", mode="lifecycle"))
        self.assertTrue(step_enabled("consumer-taxonomy-postgres.yml", "Verify lifecycle and measure queries and rebuild",
                                     "workflow_dispatch", mode="lifecycle"))

    def test_main_pr_and_scheduled_performance_behavior_is_preserved(self):
        name = "performance-investigations.yml"
        for event, ref in (("push", "refs/heads/main"), ("pull_request", "refs/pull/123/merge")):
            self.assertTrue(job_enabled(name, "investigate", event, ref=ref))
            self.assertTrue(job_enabled(name, "repository-aging-smoke", event, ref=ref))
            self.assertFalse(job_enabled(name, "publication-nonregression", event, ref=ref))
        self.assertTrue(job_enabled(name, "repository-aging-full-shard", "schedule", ref="refs/heads/main"))
        self.assertTrue(job_enabled(name, "stateless-threshold", "schedule", ref="refs/heads/main"))

    def test_gc_diagnostic_mode_is_separate_from_all_qualification_workloads(self):
        name = "performance-investigations.yml"
        ref = "refs/heads/codex/publication-gc-diagnostic-20261108"
        unrelated = ("investigate", "repository-aging-smoke", "repository-aging-full-shard",
                     "stateless-threshold", "publication-nonregression")
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assertTrue(job_enabled(name, "publication-gc-diagnostic", event,
                                            ref=ref, profile="publication-gc-diagnostic"))
                for job in unrelated:
                    self.assertFalse(job_enabled(name, job, event,
                                                 ref=ref, profile="publication-gc-diagnostic"), job)
        for event, candidate in (("push", "refs/heads/main"), ("push", ref + "-extra"),
                                 ("pull_request", ref), ("schedule", ref)):
            self.assertFalse(job_enabled(name, "publication-gc-diagnostic", event, ref=candidate))
        self.assertFalse(job_enabled(name, "publication-gc-diagnostic", "push", ref=ref, deleted=True))
        for mode in ("smoke", "full", "publication-nonregression"):
            self.assertFalse(job_enabled(name, "publication-gc-diagnostic", "workflow_dispatch",
                                         ref=ref, profile=mode))

    def test_reftable_counterfactual_has_its_own_exact_trigger(self):
        name = "performance-investigations.yml"
        ref = "refs/heads/codex/publication-reftable-diagnostic-20261108"
        variant = re.search(r"^      PUBLICATION_DIAGNOSTIC_VARIANT: (.+)$", workflow(name), re.MULTILINE)[1]
        for event in ("push", "workflow_dispatch"):
            self.assertTrue(job_enabled(name, "publication-gc-diagnostic", event, ref=ref,
                                        profile="publication-gc-counterfactual"))
            self.assertEqual(evaluate(variant, event, ref=ref, profile="publication-gc-counterfactual"),
                             "reftable")
            for job in ("investigate", "repository-aging-smoke", "repository-aging-full-shard",
                        "stateless-threshold", "publication-nonregression"):
                self.assertFalse(job_enabled(name, job, event, ref=ref,
                                             profile="publication-gc-counterfactual"), job)
        self.assertFalse(job_enabled(name, "publication-gc-diagnostic", "push", ref=ref + "-extra"))
        self.assertFalse(job_enabled(name, "publication-gc-diagnostic", "push", ref=ref, deleted=True))
        self.assertEqual(evaluate(variant, "push", ref="refs/heads/codex/publication-gc-diagnostic-20261108"),
                         "original")
        self.assertEqual(evaluate(variant, "workflow_dispatch", ref=ref, profile="publication-gc-diagnostic"),
                         "original")


if __name__ == "__main__":
    unittest.main()
