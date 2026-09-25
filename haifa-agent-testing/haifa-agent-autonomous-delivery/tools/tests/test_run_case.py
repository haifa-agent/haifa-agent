import argparse
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "run_case.py"
SPEC = importlib.util.spec_from_file_location("run_case", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

CASE_YAML = """caseId: L1-01
level: L1
title: Fixture case
labels:
  localization: 1
  modificationSpan: 1
  acceptance: 1
variants: []
language: PYTHON
promptLanguage: en
caseVersion: 2.0.0
runnerBudget:
  timeoutSeconds: 60
  maxModelCalls: 10
  maxToolCalls: 10
acceptance:
  entry: acceptance.py
  workspaceArg: positional
"""

# Writes the ladder result on stdout and a byte that is invalid in UTF-8 on stderr.
ACCEPTANCE = r"""import json
import sys

sys.stderr.buffer.write(b"DIAGNOSTICS \xaf\xff not utf-8\n")
sys.stderr.buffer.flush()
print(json.dumps({
    "schemaVersion": 1,
    "caseId": "L1-01",
    "caseVersion": "2.0.0",
    "status": "PASSED",
    "passed": True,
    "checks": {"functional.smoke": True},
    "failures": [],
}))
"""


def write_case(root: Path) -> Path:
    case_dir = root / "L1-01"
    (case_dir / "base-workspace").mkdir(parents=True)
    (case_dir / "reference").mkdir()
    (case_dir / "base-workspace" / "module.py").write_text("value = 1\n", encoding="utf-8")
    (case_dir / "reference" / "module.py").write_text("value = 2\n", encoding="utf-8")
    (case_dir / "case.yaml").write_text(CASE_YAML, encoding="utf-8")
    (case_dir / "prompt.txt").write_text("Fix the bundled module.\n", encoding="utf-8")
    (case_dir / "acceptance.py").write_text(ACCEPTANCE, encoding="utf-8")
    return case_dir


def arguments(**overrides) -> argparse.Namespace:
    defaults = {
        "mode": "agent",
        "agent_command": None,
        "case_set": MODULE.DEFAULT_CASE_SET,
        "repeat": 1,
        "timeout_seconds": 60,
        "acceptance_timeout": 120,
        "work_root": None,
        "keep_workdir": False,
        "report": None,
        "json": False,
    }
    defaults.update(overrides)
    return argparse.Namespace(**defaults)


def write_assets(root: Path, case_ids: list[str], **manifest_overrides) -> Path:
    """Materialize a minimal but valid asset checkout and return its root."""
    case_root = root / "cases"
    for case_id in case_ids:
        case_dir = case_root / case_id
        (case_dir / "base-workspace").mkdir(parents=True)
        (case_dir / "base-workspace" / "module.py").write_text("value = 1\n", encoding="utf-8")
        (case_dir / "case.yaml").write_text(CASE_YAML.replace("L1-01", case_id), encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "assetVersion": "test",
        "caseRoot": "cases",
        "caseTreeSha256": MODULE.case_tree_sha256(case_root),
        "cases": sorted(case_ids),
    }
    manifest.update(manifest_overrides)
    (root / MODULE.ASSET_MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
    return root


class RunCaseTest(unittest.TestCase):
    def test_acceptance_output_is_decoded_as_utf8_not_the_host_code_page(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case_dir = write_case(root)

            exit_code, result, stderr = MODULE.run_acceptance(case_dir, case_dir / "base-workspace", 120)

        self.assertEqual(0, exit_code)
        self.assertIsNotNone(result)
        self.assertEqual("L1-01", result["caseId"])
        self.assertIn("not utf-8", stderr)

    def test_prompt_is_handed_to_the_agent_outside_the_case_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case_dir = write_case(root)
            marker = root / "seen-prompt-path.txt"
            os.environ["LADDER_TEST_MARKER"] = str(marker)
            command = (
                f'"{sys.executable}" -c '
                '"import os,sys;open(os.environ[\'LADDER_TEST_MARKER\'],\'w\',encoding=\'utf-8\').write(sys.argv[1])" '
                "{prompt_file}"
            )
            try:
                record = MODULE.run_once(case_dir, arguments(agent_command=command), 1)
            finally:
                os.environ.pop("LADDER_TEST_MARKER", None)

            seen = Path(marker.read_text(encoding="utf-8").strip())
            self.assertEqual("prompt.txt", seen.name)
            self.assertEqual("Fix the bundled module.\n", (case_dir / "prompt.txt").read_text(encoding="utf-8"))
            self.assertNotIn(case_dir.resolve(), seen.resolve().parents)
            self.assertFalse(seen.exists(), "the isolated prompt copy must be removed after the run")

        self.assertEqual("OK", record["verdict"])
        self.assertEqual([], record["contractProblems"])


class CaseAxesTest(unittest.TestCase):
    def test_a_ladder_case_groups_by_its_level_and_has_no_tier(self):
        self.assertEqual(("L3", None), MODULE.case_axes("L3-02"))
        self.assertEqual(("L6", None), MODULE.case_axes("L6-01"))

    def test_a_hard_case_groups_by_dimension_and_carries_a_tier(self):
        self.assertEqual(("H1", "T1"), MODULE.case_axes("H11-01"))
        self.assertEqual(("H1", "T2"), MODULE.case_axes("H12-01"))
        self.assertEqual(("H4", "T3"), MODULE.case_axes("H43-02"))


class ReportTest(unittest.TestCase):
    def record(self, case_id: str, accepted: bool) -> dict:
        group, tier = MODULE.case_axes(case_id)
        record = {
            "caseId": case_id,
            "level": group,
            "verdict": "OK" if accepted else "UNEXPECTED",
            "accepted": accepted,
            "durationMillis": 10,
            "contractProblems": [],
        }
        if tier:
            record["tier"] = tier
        return record

    def test_hard_cases_are_summarized_per_dimension_and_per_tier(self):
        with tempfile.TemporaryDirectory() as directory:
            cases_root = write_assets(Path(directory), ["H11-01", "H12-01", "H21-01"]) / "cases"
            records = [self.record("H11-01", True), self.record("H12-01", False), self.record("H21-01", True)]

            report = MODULE.build_report(records, arguments(case_set="hard-v1"), cases_root)

        self.assertEqual("hard-v1", report["caseSet"])
        self.assertEqual({"cases": 2, "runs": 2, "passedRuns": 1}, report["levels"]["H1"])
        self.assertEqual({"cases": 1, "runs": 1, "passedRuns": 1}, report["levels"]["H2"])
        self.assertEqual({"cases": 2, "runs": 2, "passedRuns": 2}, report["tiers"]["T1"])
        self.assertEqual({"cases": 1, "runs": 1, "passedRuns": 0}, report["tiers"]["T2"])

    def test_a_ladder_report_keeps_its_shape_and_grows_no_tier_block(self):
        with tempfile.TemporaryDirectory() as directory:
            cases_root = write_assets(Path(directory), ["L1-01", "L1-02"]) / "cases"
            records = [self.record("L1-01", True), self.record("L1-02", True)]

            report = MODULE.build_report(records, arguments(), cases_root)

        self.assertEqual("ladder-v1", report["caseSet"])
        self.assertNotIn("tiers", report)
        self.assertNotIn("tier", report["cases"][0])
        self.assertEqual({"cases": 2, "runs": 2, "passedRuns": 2}, report["levels"]["L1"])


class CaseSetTest(unittest.TestCase):
    LADDER = ["L1-01", "L1-02"]
    HARD = ["H11-01", "H43-01"]

    def case_sets(self) -> dict:
        return {"ladder-v1": self.LADDER, "hard-v1": self.HARD}

    def manifest_v2(self, root: Path, **overrides) -> Path:
        return write_assets(
            root,
            self.LADDER + self.HARD,
            schemaVersion=2,
            caseSets=overrides.pop("caseSets", self.case_sets()),
            **overrides,
        )

    def test_a_schema_version_2_manifest_publishes_named_case_sets(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = self.manifest_v2(Path(directory))

            case_root, manifest = MODULE.verify_assets_root(assets)

            self.assertTrue(case_root.is_dir())
            self.assertEqual(self.LADDER, MODULE.case_set_members(manifest, "ladder-v1"))
            self.assertEqual(self.HARD, MODULE.case_set_members(manifest, "hard-v1"))

    def test_a_schema_version_1_manifest_still_verifies_and_holds_one_implicit_set(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = write_assets(Path(directory), self.LADDER)

            _, manifest = MODULE.verify_assets_root(assets)

            self.assertEqual(self.LADDER, MODULE.case_set_members(manifest, MODULE.DEFAULT_CASE_SET))
            with self.assertRaises(SystemExit) as raised:
                MODULE.case_set_members(manifest, "hard-v1")
            self.assertIn("predates case sets", str(raised.exception))

    def test_overlapping_case_sets_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            overlapping = {"ladder-v1": self.LADDER, "hard-v1": self.HARD + ["L1-02"]}
            assets = self.manifest_v2(Path(directory), caseSets=overlapping)

            with self.assertRaises(SystemExit) as raised:
                MODULE.verify_assets_root(assets)

            self.assertIn("overlap on: L1-02", str(raised.exception))

    def test_a_case_outside_every_set_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            partial = {"ladder-v1": self.LADDER, "hard-v1": ["H11-01"]}
            assets = self.manifest_v2(Path(directory), caseSets=partial)

            with self.assertRaises(SystemExit) as raised:
                MODULE.verify_assets_root(assets)

            self.assertIn("union of its case sets", str(raised.exception))

    def test_a_case_set_name_must_be_kebab_case(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = self.manifest_v2(Path(directory), caseSets={"Ladder_V1": self.LADDER + self.HARD})

            with self.assertRaises(SystemExit) as raised:
                MODULE.verify_assets_root(assets)

            self.assertIn("kebab-case", str(raised.exception))

    def test_an_unknown_case_set_names_the_published_ones(self):
        with tempfile.TemporaryDirectory() as directory:
            _, manifest = MODULE.verify_assets_root(self.manifest_v2(Path(directory)))

            with self.assertRaises(SystemExit) as raised:
                MODULE.case_set_members(manifest, "hard-v2")

            self.assertIn("hard-v1, ladder-v1", str(raised.exception))

    def test_an_unknown_manifest_schema_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = write_assets(Path(directory), self.LADDER, schemaVersion=3)

            with self.assertRaises(SystemExit) as raised:
                MODULE.verify_assets_root(assets)

            self.assertIn("schemaVersion must be 1 or 2", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
