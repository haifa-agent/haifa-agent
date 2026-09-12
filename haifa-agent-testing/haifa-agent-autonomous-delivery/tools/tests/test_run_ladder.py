import importlib.util
import json
import os
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "run_ladder.py"
SPEC = importlib.util.spec_from_file_location("run_ladder", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

CASE_YAML = 'caseId: L1-01\nlevel: L1\ntitle: Fixture case\nlabels:\n  localization: 1\n  modificationSpan: 1\n  acceptance: 1\nvariants: []\ncaseVersion: 2.0.0\nrunnerBudget:\n  timeoutSeconds: 60\n'

PROMPT = "First line of the statement.\n\n- a bullet\n- another bullet\n"


def arguments(**overrides):
    defaults = {
        "action": "check",
        "agent": "haifa-coding",
        "model": None,
        "credential_env": None,
        "approval": None,
        "cases": None,
        "repeat": None,
        "timeout_scale": None,
        "output": None,
        "cache_dir": None,
        "assets_dir": None,
        "skip_gates": True,
        "gate_repeat": 1,
        "rehearse": False,
        "keep_workdir": False,
        "allow_unpinned_assets": False,
    }
    defaults.update(overrides)
    return Namespace(**defaults)


def settings(**overrides):
    defaults = {
        "action": "run",
        "allow_real_provider": True,
        "agent": "haifa-coding",
        "model": None,
        "credential_env": None,
        "approval": "auto",
        "case_patterns": [],
        "repeat": 1,
        "timeout_scale": 1.0,
        "output_dir": Path("out"),
        "cache_dir": Path("cache"),
        "assets_dir": None,
        "skip_gates": True,
        "gate_repeat": 1,
        "rehearse": False,
        "keep_workdir": False,
        "allow_unpinned_assets": False,
    }
    defaults.update(overrides)
    return MODULE.Settings(**defaults)


class CredentialTest(unittest.TestCase):
    def test_model_prefix_selects_the_provider_variable(self):
        self.assertEqual("BIGMODEL_API_KEY", MODULE.credential_variable("glm-5.3-flash", None)[0])
        self.assertEqual("KIMI_API_KEY", MODULE.credential_variable("kimi-k3", None)[0])

    def test_model_auth_providers_need_no_variable(self):
        variable, reason = MODULE.credential_variable("deepseek-responses-flash", None)

        self.assertIsNone(variable)
        self.assertIn("auth.json", reason)

    def test_explicit_variable_wins(self):
        self.assertEqual("MY_KEY", MODULE.credential_variable("glm-5.3-flash", "MY_KEY")[0])

    def test_secret_values_are_redacted(self):
        os.environ["BIGMODEL_API_KEY"] = "super-secret-value"
        try:
            secrets = MODULE.secret_values(settings())
            line = MODULE.redact("calling provider with super-secret-value", secrets)
        finally:
            os.environ.pop("BIGMODEL_API_KEY", None)

        self.assertNotIn("super-secret-value", line)
        self.assertIn("***", line)


class LauncherTest(unittest.TestCase):
    def test_batch_launcher_is_replaced_by_the_packaged_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            distribution = Path(directory)
            launcher = distribution / "haifa-coding.cmd"
            launcher.write_text("@echo off\n", encoding="utf-8")
            (distribution / "haifa-agent.jar").write_text("", encoding="utf-8")
            (distribution / "haifa-coding.yaml").write_text("models: {}\n", encoding="utf-8")

            argv, note = MODULE.launcher_argv(str(launcher))

        self.assertEqual(["-jar", str(distribution / "haifa-agent.jar"), "--config", str(distribution / "haifa-coding.yaml")], argv[1:])
        self.assertIn("haifa-agent.jar", note)

    def test_batch_launcher_without_a_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = Path(directory) / "haifa-coding.cmd"
            launcher.write_text("@echo off\n", encoding="utf-8")

            with self.assertRaises(SystemExit) as raised:
                MODULE.launcher_argv(str(launcher))

        self.assertIn("truncate", str(raised.exception))

    def test_plain_executable_is_used_as_is(self):
        self.assertEqual((["haifa-coding"], "haifa-coding"), MODULE.launcher_argv("haifa-coding"))

    def test_statement_stays_one_multi_line_argument(self):
        argv = MODULE.agent_argv(settings(model="glm-5.3-flash"), Path("ws"), PROMPT, 600)

        self.assertEqual(PROMPT, argv[-1])
        self.assertEqual("-m", argv[-2])
        self.assertIn("--workspace", argv)
        self.assertIn("--model", argv)
        self.assertIn("PT540S", argv)


class SelectionTest(unittest.TestCase):
    def test_patterns_filter_the_available_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            cases_root = Path(directory)
            for case_id in ("L1-01", "L1-02", "L4-03", "notes"):
                (cases_root / case_id).mkdir()

            self.assertEqual(["L1-01", "L1-02", "L4-03"], MODULE.select_cases(cases_root, []))
            self.assertEqual(["L1-01", "L1-02"], MODULE.select_cases(cases_root, ["L1-*"]))
            self.assertEqual(["L1-02", "L4-03"], MODULE.select_cases(cases_root, ["L1-02", "L4-03"]))

    def test_missing_environment_requires_the_cost_acknowledgement(self):
        problems = MODULE.missing_environment(settings(allow_real_provider=False))

        self.assertTrue(any("HAIFA_LADDER_ALLOW_REAL_PROVIDER" in problem for problem in problems))

    def test_rehearsal_needs_no_agent_or_credential(self):
        self.assertEqual([], MODULE.missing_environment(settings(agent=None, rehearse=True, allow_real_provider=False)))


class AssetLockTest(unittest.TestCase):
    def checkout(self, directory: str, digest_source: str) -> Path:
        checkout = Path(directory)
        (checkout / "assets-manifest.json").write_text(digest_source, encoding="utf-8")
        return checkout

    def test_matching_manifest_digest_is_the_locked_asset_set(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = self.checkout(directory, '{"schemaVersion": 1}')
            digest = MODULE.fetch_assets.sha256_file(checkout / "assets-manifest.json")

            self.assertIsNone(MODULE.lock_mismatch(checkout, {"manifestSha256": digest}))

    def test_other_checkouts_are_reported_as_unpinned(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = self.checkout(directory, '{"schemaVersion": 1, "assetVersion": "other"}')

            mismatch = MODULE.lock_mismatch(checkout, {"manifestSha256": "0" * 64})

        self.assertIn("not the pinned asset set", mismatch)


class ExitCodeTest(unittest.TestCase):
    def record(self, case_id: str, status: str, accepted: bool, contract=()):
        return {"caseId": case_id, "level": case_id[:2], "status": status, "accepted": accepted, "contractProblems": list(contract)}

    def test_only_accepted_runs_count(self):
        records = [
            self.record("L1-01", "PASSED", True),
            self.record("L1-02", "PASSED", False, ["passed must equal 'failures is empty'"]),
            self.record("L1-03", "FAILED", False),
        ]

        self.assertEqual(1, MODULE.accepted_runs(records))

    def test_a_contract_violation_keeps_the_run_unaccepted(self):
        records = [self.record("L1-01", "PASSED", False, ["checks must be a non-empty boolean map"])]

        self.assertEqual(0, MODULE.accepted_runs(records))


class LauncherEnvironmentTest(unittest.TestCase):
    def test_distribution_data_paths_are_restored(self):
        variables = ("HAIFA_SQLITE_DATABASE_PATH", "HAIFA_TRANSCRIPT_ROOT", "HAIFA_LOG_DIR")
        previous = {name: os.environ.pop(name, None) for name in variables}
        try:
            with tempfile.TemporaryDirectory() as directory:
                distribution = Path(directory)
                launcher = distribution / "haifa-coding.cmd"
                launcher.write_text("@echo off" + chr(10), encoding="utf-8")

                applied = MODULE.prepare_launcher_environment(str(launcher))

                self.assertEqual(sorted(variables), sorted(applied))
                self.assertEqual(str(distribution / "data" / "runtime.db"), os.environ["HAIFA_SQLITE_DATABASE_PATH"])
                self.assertTrue((distribution / "data" / "transcripts").is_dir())
                self.assertTrue((distribution / "logs").is_dir())
        finally:
            for name, value in previous.items():
                if value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = value

    def test_a_plain_executable_needs_no_data_paths(self):
        self.assertEqual([], MODULE.prepare_launcher_environment("haifa-coding"))


class RunGuardTest(unittest.TestCase):
    def test_a_repeat_below_one_would_evaluate_nothing(self):
        problems = MODULE.missing_environment(settings(repeat=0))

        self.assertTrue(any("repeat must be at least 1" in problem for problem in problems))

    def test_a_non_positive_timeout_scale_is_rejected(self):
        problems = MODULE.missing_environment(settings(timeout_scale=0.0))

        self.assertTrue(any("timeout scale must be a positive finite number" in problem for problem in problems))

    def test_interactive_approval_is_rejected_before_any_provider_call(self):
        problems = MODULE.missing_environment(settings(approval="ask"))

        self.assertTrue(any("stdin closed" in problem for problem in problems))

    def test_a_rehearsal_needs_no_approval_mode(self):
        self.assertEqual([], MODULE.missing_environment(settings(approval="ask", rehearse=True, allow_real_provider=False)))

    def test_a_zero_flag_is_not_replaced_by_the_environment_default(self):
        os.environ["HAIFA_LADDER_REPEAT"] = "3"
        try:
            resolved = MODULE.resolve_settings(arguments(repeat=0))
        finally:
            os.environ.pop("HAIFA_LADDER_REPEAT", None)

        self.assertEqual(0, resolved.repeat)

    def test_a_malformed_numeric_variable_is_reported(self):
        os.environ["HAIFA_LADDER_REPEAT"] = "many"
        try:
            with self.assertRaises(SystemExit) as raised:
                MODULE.resolve_settings(arguments())
        finally:
            os.environ.pop("HAIFA_LADDER_REPEAT", None)

        self.assertIn("must be an integer", str(raised.exception))


class ResultNormalizationTest(unittest.TestCase):
    def test_malformed_checks_and_failures_do_not_abort_the_run(self):
        checks, failures = MODULE.normalized_result({"checks": ["a", "b"], "failures": [1, 2]})

        self.assertEqual({}, checks)
        self.assertEqual(["1", "2"], failures)

    def test_valid_payloads_are_kept(self):
        checks, failures = MODULE.normalized_result({"checks": {"functional.x": True}, "failures": ["boundary.y"]})

        self.assertEqual({"functional.x": True}, checks)
        self.assertEqual(["boundary.y"], failures)

    def test_missing_fields_become_empty(self):
        self.assertEqual(({}, []), MODULE.normalized_result({}))


class ReportModeTest(unittest.TestCase):
    def test_a_rehearsal_is_never_reported_as_an_agent_run(self):
        self.assertEqual("rehearse", MODULE.run_mode(settings(rehearse=True)))
        self.assertEqual("agent", MODULE.run_mode(settings(rehearse=False)))

    def test_the_aggregate_report_carries_the_rehearsal_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_root = root / "cases"
            (cases_root / "L1-01").mkdir(parents=True)
            (cases_root / "L1-01" / "case.yaml").write_text(CASE_YAML, encoding="utf-8")
            records = [
                {
                    "caseId": "L1-01",
                    "level": "L1",
                    "mode": "rehearse",
                    "verdict": "OK",
                    "accepted": True,
                    "expected": "PASSED",
                    "status": "PASSED",
                    "durationMillis": 10,
                    "checks": {"functional.x": True},
                    "failures": [],
                    "contractProblems": [],
                }
            ]

            report_path = MODULE.write_reports(
                settings(rehearse=True, output_dir=root / "out", model="glm-5.3-flash"),
                records,
                cases_root,
                MODULE.AssetProvenance(manifest_sha256="a" * 64, pinned=False, asset_version="2026.09.11.2"),
            )
            report = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual("rehearse", report["mode"])
        self.assertEqual(1, report["totals"]["passedRuns"])
        self.assertEqual("rehearse", report["evaluation"]["mode"])
        self.assertEqual("glm-5.3-flash", report["evaluation"]["model"])
        self.assertEqual(
            {"assetVersion": "2026.09.11.2", "manifestSha256": "a" * 64, "pinned": False},
            report["evaluation"]["assets"],
        )


class PathResolutionTest(unittest.TestCase):
    """The agent runs with cwd set to its workspace, so every path must be absolute beforehand."""

    def test_a_relative_output_directory_becomes_absolute(self):
        resolved = MODULE.resolve_settings(arguments(output="results", cache_dir="cache"))

        self.assertTrue(resolved.output_dir.is_absolute())
        self.assertTrue(resolved.cache_dir.is_absolute())
        self.assertEqual("results", resolved.output_dir.name)

    def test_a_relative_assets_directory_becomes_absolute(self):
        resolved = MODULE.resolve_settings(arguments(assets_dir="assets"))

        self.assertTrue(resolved.assets_dir.is_absolute())

    def test_a_relative_launcher_becomes_absolute(self):
        with tempfile.TemporaryDirectory() as directory:
            distribution = Path(directory) / "dist"
            distribution.mkdir()
            launcher = distribution / "haifa-coding"
            launcher.write_text("#!/bin/sh" + chr(10), encoding="utf-8")
            previous = os.getcwd()
            os.chdir(directory)
            try:
                resolved = MODULE.resolve_settings(arguments(agent=str(Path("dist") / "haifa-coding")))
            finally:
                os.chdir(previous)

        self.assertTrue(Path(resolved.agent).is_absolute())
        self.assertEqual("haifa-coding", Path(resolved.agent).name)

    def test_an_unknown_launcher_is_kept_for_the_preflight_message(self):
        resolved = MODULE.resolve_settings(arguments(agent="no-such-launcher"))

        self.assertEqual("no-such-launcher", resolved.agent)
        self.assertTrue(any("does not resolve" in problem for problem in MODULE.missing_environment(resolved)))


class LauncherGuardTest(unittest.TestCase):
    def test_a_non_finite_timeout_scale_is_rejected(self):
        for scale in (float("nan"), float("inf")):
            problems = MODULE.missing_environment(settings(timeout_scale=scale))

            self.assertTrue(any("positive finite number" in problem for problem in problems), scale)

    @unittest.skipIf(os.name == "nt", "POSIX permission bits only")
    def test_a_launcher_without_the_execute_permission_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = Path(directory) / "haifa-coding"
            launcher.write_text("#!/bin/sh" + chr(10), encoding="utf-8")
            launcher.chmod(0o644)

            self.assertFalse(MODULE.executable_launcher(str(launcher)))
            problems = MODULE.missing_environment(settings(agent=str(launcher)))

            launcher.chmod(0o755)
            self.assertTrue(any("chmod +x" in problem for problem in problems))
            self.assertTrue(MODULE.executable_launcher(str(launcher)))

    def test_a_command_on_path_needs_no_permission_check(self):
        self.assertTrue(MODULE.executable_launcher("haifa-coding"))


class DiagnosticsTest(unittest.TestCase):
    def test_changed_sources_and_reasons_are_extracted(self):
        stderr = 'noise\nDIAGNOSTICS {"changedSources": ["a.py"], "details": {"functional.x": "returned 3"}}\n'

        changed, reasons = MODULE.parse_diagnostics(stderr)

        self.assertEqual(["a.py"], changed)
        self.assertEqual({"functional.x": "returned 3"}, reasons)

    def test_missing_diagnostics_is_not_an_error(self):
        self.assertEqual(([], {}), MODULE.parse_diagnostics("acceptance crashed"))

    def test_duration_is_human_readable(self):
        self.assertEqual("45s", MODULE.duration(45))
        self.assertEqual("2m05s", MODULE.duration(125))
        self.assertEqual("1h01m", MODULE.duration(3700))


class SettingsTest(unittest.TestCase):
    def test_flags_win_over_environment(self):
        os.environ["HAIFA_LADDER_MODEL"] = "from-environment"
        try:
            resolved = MODULE.resolve_settings(
                Namespace(
                    action="check",
                    agent="agent",
                    model="from-flag",
                    credential_env=None,
                    approval=None,
                    cases="L2-*",
                    repeat=2,
                    timeout_scale=None,
                    output=None,
                    cache_dir=None,
                    assets_dir=None,
                    skip_gates=True,
                    gate_repeat=1,
                    rehearse=False,
                    keep_workdir=False,
                    allow_unpinned_assets=False,
                )
            )
        finally:
            os.environ.pop("HAIFA_LADDER_MODEL", None)

        self.assertEqual("from-flag", resolved.model)
        self.assertEqual(["L2-*"], resolved.case_patterns)
        self.assertEqual(2, resolved.repeat)
        self.assertEqual("auto", resolved.approval)


if __name__ == "__main__":
    unittest.main()
