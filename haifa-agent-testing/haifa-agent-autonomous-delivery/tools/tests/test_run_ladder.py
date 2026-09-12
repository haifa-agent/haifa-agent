import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from argparse import Namespace
from contextlib import redirect_stdout
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

    def test_a_short_credential_is_redacted_too(self):
        os.environ["TINY_KEY"] = "s3cr3t"
        try:
            secrets = MODULE.secret_values(settings(credential_env="TINY_KEY"))
            line = MODULE.redact("authorization: s3cr3t", secrets)
        finally:
            os.environ.pop("TINY_KEY", None)

        self.assertNotIn("s3cr3t", line)

    def test_an_empty_credential_is_not_redacted(self):
        os.environ["TINY_KEY"] = "   "
        try:
            self.assertEqual([], MODULE.secret_values(settings(credential_env="TINY_KEY")))
        finally:
            os.environ.pop("TINY_KEY", None)


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


class RunRecordTest(unittest.TestCase):
    PROVENANCE = None

    def setUp(self):
        self.PROVENANCE = MODULE.AssetProvenance(manifest_sha256="b" * 64, pinned=False, asset_version="2026.09.11.2")

    def test_every_record_identifies_its_benchmark(self):
        fields = MODULE.provenance_fields(settings(model="glm-5.3-flash"), self.PROVENANCE)

        self.assertEqual(
            {
                "mode": "agent",
                "model": "glm-5.3-flash",
                "modelSource": "override",
                "approval": "auto",
                "assetVersion": "2026.09.11.2",
                "assetManifestSha256": "b" * 64,
                "assetsPinned": False,
            },
            fields,
        )

    def case_directory(self, root: Path) -> Path:
        case_dir = root / "L1-01"
        (case_dir / "base-workspace").mkdir(parents=True)
        (case_dir / "reference").mkdir()
        (case_dir / "base-workspace" / "module.py").write_text("value = 1" + chr(10), encoding="utf-8")
        (case_dir / "reference" / "module.py").write_text("value = 2" + chr(10), encoding="utf-8")
        (case_dir / "case.yaml").write_text(CASE_YAML, encoding="utf-8")
        (case_dir / "prompt.txt").write_text("Fix it." + chr(10), encoding="utf-8")
        return case_dir

    def test_an_acceptance_timeout_fails_only_its_own_case(self):
        def timing_out(*_arguments, **_keywords):
            raise subprocess.TimeoutExpired(cmd="acceptance.py", timeout=900)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case_dir = self.case_directory(root)
            original = MODULE.run_case.run_acceptance
            MODULE.run_case.run_acceptance = timing_out
            try:
                outcome, record = MODULE.evaluate_case(
                    settings(rehearse=True, output_dir=root / "out"), case_dir, 1, "  [1/1] L1-01", self.PROVENANCE
                )
            finally:
                MODULE.run_case.run_acceptance = original

        self.assertEqual("INCOMPLETE_BUDGET", record["status"])
        self.assertFalse(record["accepted"])
        self.assertEqual(["acceptance exceeded 900s"], record["failures"])
        self.assertEqual("b" * 64, record["assetManifestSha256"])
        self.assertEqual("INCOMPLETE_BUDGET", outcome.status)

    def test_an_acceptance_that_cannot_start_fails_only_its_own_case(self):
        def not_startable(*_arguments, **_keywords):
            raise FileNotFoundError("python is gone")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case_dir = self.case_directory(root)
            original = MODULE.run_case.run_acceptance
            MODULE.run_case.run_acceptance = not_startable
            try:
                _, record = MODULE.evaluate_case(
                    settings(rehearse=True, output_dir=root / "out"), case_dir, 1, "  [1/1] L1-01", self.PROVENANCE
                )
            finally:
                MODULE.run_case.run_acceptance = original

        self.assertEqual("FAILED", record["status"])
        self.assertFalse(record["accepted"])
        self.assertIn("FileNotFoundError", record["failures"][0])


class ModelResolutionTest(unittest.TestCase):
    def distribution(self, directory: str, default_model: str) -> Path:
        root = Path(directory)
        (root / "haifa-coding.yaml").write_text(
            "models:" + chr(10) + "  default: " + default_model + chr(10) + "  providers: []" + chr(10),
            encoding="utf-8",
        )
        launcher = root / "haifa-coding.cmd"
        launcher.write_text("@echo off" + chr(10), encoding="utf-8")
        return launcher

    def test_the_configured_default_is_used_when_no_override_is_given(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = self.distribution(directory, "glm-5.3-flash")

            self.assertEqual(("glm-5.3-flash", "agent configuration"), MODULE.effective_model(settings(agent=str(launcher))))

    def test_an_override_wins_over_the_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = self.distribution(directory, "glm-5.3-flash")

            resolved = MODULE.effective_model(settings(agent=str(launcher), model="kimi-k3"))

        self.assertEqual(("kimi-k3", "override"), resolved)

    def test_an_unknown_configuration_stays_unknown(self):
        self.assertEqual((None, "unknown"), MODULE.effective_model(settings(agent="haifa-coding")))

    def test_the_configured_default_drives_the_credential_check(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = self.distribution(directory, "glm-5.3-flash")
            os.environ.pop("BIGMODEL_API_KEY", None)

            problems = MODULE.missing_environment(settings(agent=str(launcher)))

        self.assertTrue(any("BIGMODEL_API_KEY is empty" in problem for problem in problems))


class StoredConnectionTest(unittest.TestCase):
    def store(self, directory: str, references: list[str]) -> Path:
        path = Path(directory) / "auth.json"
        path.write_text(
            json.dumps({"version": 1, "credentials": {reference: {"kind": "api_key"} for reference in references}}),
            encoding="utf-8",
        )
        return path

    def test_model_auth_providers_are_recognized(self):
        self.assertEqual("deepseek", MODULE.model_auth_provider("deepseek-responses-flash"))
        self.assertEqual("openai-codex", MODULE.model_auth_provider("gpt-5.6-sol"))
        self.assertIsNone(MODULE.model_auth_provider("glm-5.3-flash"))

    def test_a_present_connection_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            original = MODULE.AUTH_STORE
            MODULE.AUTH_STORE = self.store(directory, ["model-auth://deepseek/default"])
            try:
                self.assertIsNone(MODULE.stored_connection_problem("deepseek"))
            finally:
                MODULE.AUTH_STORE = original

    def test_a_missing_connection_stops_the_run_before_it_starts(self):
        with tempfile.TemporaryDirectory() as directory:
            original = MODULE.AUTH_STORE
            MODULE.AUTH_STORE = self.store(directory, ["model-auth://openai-codex/default"])
            try:
                problem = MODULE.stored_connection_problem("deepseek")
                problems = MODULE.missing_environment(settings(model="deepseek-responses-flash"))
            finally:
                MODULE.AUTH_STORE = original

        self.assertIn("no stored connection for deepseek", problem)
        self.assertTrue(any("no stored connection for deepseek" in entry for entry in problems))

    def test_a_missing_store_is_reported(self):
        original = MODULE.AUTH_STORE
        MODULE.AUTH_STORE = Path(tempfile.gettempdir()) / "no-such-haifa-auth.json"
        try:
            self.assertIn("does not exist", MODULE.stored_connection_problem("deepseek"))
        finally:
            MODULE.AUTH_STORE = original


class CredentialScrubbingTest(unittest.TestCase):
    def test_the_acceptance_environment_has_no_provider_credential(self):
        os.environ["BIGMODEL_API_KEY"] = "secret-value"
        try:
            with MODULE.without_credentials(settings()):
                inside = os.environ.get("BIGMODEL_API_KEY")
            outside = os.environ.get("BIGMODEL_API_KEY")
        finally:
            os.environ.pop("BIGMODEL_API_KEY", None)

        self.assertIsNone(inside)
        self.assertEqual("secret-value", outside)


class InterruptTest(unittest.TestCase):
    def test_an_unwinding_run_kills_the_agent(self):
        killed = []
        original_kill, original_heartbeat, original_say = MODULE.kill_process_tree, MODULE.HEARTBEAT_SECONDS, MODULE.say

        def spy(process):
            killed.append(process)
            original_kill(process)

        def exploding_say(_message=""):
            raise RuntimeError("interrupted")

        MODULE.kill_process_tree, MODULE.HEARTBEAT_SECONDS, MODULE.say = spy, 0, exploding_say
        try:
            with tempfile.TemporaryDirectory() as directory:
                argv = [sys.executable, "-c", "import time; time.sleep(30)"]
                with self.assertRaises(RuntimeError):
                    MODULE.run_agent(settings(), argv, Path(directory), Path(directory) / "agent.log", 600, "  [1/1] L1-01")
        finally:
            MODULE.kill_process_tree, MODULE.HEARTBEAT_SECONDS, MODULE.say = original_kill, original_heartbeat, original_say

        self.assertEqual(1, len(killed))
        self.assertIsNotNone(killed[0].poll(), "the agent process must be gone")


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


class GateGuardTest(unittest.TestCase):
    def test_a_stuck_gate_acceptance_fails_the_gate_instead_of_preflight(self):
        def timing_out(*_arguments, **_keywords):
            raise subprocess.TimeoutExpired(cmd="acceptance.py", timeout=900)

        original = MODULE.run_case.run_once
        MODULE.run_case.run_once = timing_out
        try:
            with redirect_stdout(io.StringIO()):
                ok, detail = MODULE.run_gate(Path("cases"), "nop", ["L1-01"], 1)
        finally:
            MODULE.run_case.run_once = original

        self.assertFalse(ok)
        self.assertIn("ACCEPTANCE_TIMEOUT", detail)


class MalformedDiagnosticsTest(unittest.TestCase):
    def test_a_non_object_payload_is_ignored(self):
        self.assertEqual(([], {}), MODULE.parse_diagnostics("DIAGNOSTICS []"))

    def test_wrongly_typed_fields_are_ignored(self):
        changed, details = MODULE.parse_diagnostics('DIAGNOSTICS {"changedSources": 1, "details": "boom"}')

        self.assertEqual(([], {}), (changed, details))

    def test_values_are_coerced_to_strings(self):
        changed, details = MODULE.parse_diagnostics('DIAGNOSTICS {"changedSources": [1], "details": {"a": 2}}')

        self.assertEqual((["1"], {"a": "2"}), (changed, details))


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
