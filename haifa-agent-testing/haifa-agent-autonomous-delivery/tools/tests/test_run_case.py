import argparse
import importlib.util
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


if __name__ == "__main__":
    unittest.main()
