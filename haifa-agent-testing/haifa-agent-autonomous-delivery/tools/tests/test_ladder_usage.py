import importlib.util
import io
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


USAGE = load("ladder_usage")
BASE = 1_789_000_000_000


def event(connection, run_id, kind, **values):
    connection.execute(
        "insert into runtime_event (run_id, type, data_payload, occurred_at) values (?, ?, ?, ?)",
        (run_id, kind, json.dumps({"values": values}), BASE),
    )


def create_database(path: Path) -> None:
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        create table run (
            run_id text primary key, parent_run_id text, status text, termination_reason text, error_payload text,
            created_at integer, started_at integer, completed_at integer, updated_at integer,
            usage_input_tokens integer, usage_cached_input_tokens integer, usage_output_tokens integer,
            usage_model_calls integer, usage_tool_calls integer);
        create table runtime_event (run_id text, type text, data_payload text, occurred_at integer);
        create table tool_call (
            run_id text, tool_name text, status text, requested_at integer, started_at integer, completed_at integer);
        """
    )

    def run(run_id, created, status, reason=None, error=None, tokens=(0, 0, 0), calls=(0, 0)):
        connection.execute(
            "insert into run values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (run_id, status, reason, error, created, created, created + 1000, created + 1000, *tokens, *calls),
        )

    # L1-01: completed normally, one failed and retried model attempt, one failed tool call.
    run("run-completed", BASE + 2000, "COMPLETED", tokens=(100_000, 40_000, 5_000), calls=(3, 4))
    event(connection, "run-completed", "model.attempt.scheduled", attempt=1)
    event(connection, "run-completed", "model.call.failed", attempt=1, durationMillis=5000, providerCode="stream_response_too_large")
    event(connection, "run-completed", "model.attempt.scheduled", attempt=2)
    event(connection, "run-completed", "model.call.succeeded", attempt=2, durationMillis=20000, finishReason="tool_calls")
    event(connection, "run-completed", "model.call.succeeded", attempt=1, durationMillis=30000, finishReason="tool_calls")
    event(connection, "run-completed", "model.call.succeeded", attempt=1, durationMillis=10000, finishReason="stop")
    for name, status, millis in (
        ("file_read", "COMPLETED", 1000),
        ("file_patch", "COMPLETED", 500),
        ("execution_run", "COMPLETED", 3000),
        ("execution_run", "FAILED", 200),
    ):
        connection.execute(
            "insert into tool_call values ('run-completed', ?, ?, ?, ?, ?)",
            (name, status, BASE + 10_000, BASE + 10_000, BASE + 10_000 + millis),
        )
    # A manual run just after L1-01 ended must not be attributed to any case.
    run("run-stray", BASE + 100_500, "COMPLETED", tokens=(999_999, 0, 999_999), calls=(99, 99))
    # L1-02: cancelled exactly at the CLI self-limit of a 300 s budget, recorded as USER_CANCELLED.
    run("run-deadline", BASE + 202_000, "CANCELLED", reason="USER_CANCELLED", tokens=(50_000, 0, 2_000), calls=(2, 3))
    # L6-01: failed with a stable error code.
    run("run-failed", BASE + 502_000, "FAILED", error=json.dumps({"code": "MODEL_RESPONSE_INVALID"}), tokens=(10_000, 0, 500), calls=(1, 1))
    connection.commit()
    connection.close()


def record(case_id, level, status, accepted, start, wall, budget, **extra):
    value = {
        "caseId": case_id,
        "level": level,
        "attempt": 1,
        "status": status,
        "accepted": accepted,
        "mode": "agent",
        "model": "glm-5.3-flash",
        "modelSource": "override",
        "approval": "auto",
        "assetVersion": "2026.09.11.2",
        "assetsPinned": True,
        "durationMillis": wall + 5000,
        "agentDurationMillis": wall,
        "budgetSeconds": budget,
    }
    if start is not None:
        value["agentStartedAtEpochMillis"] = start
        value["agentEndedAtEpochMillis"] = start + wall
    value.update(extra)
    return value


def write_run(directory: Path, records) -> Path:
    run_dir = directory / "20260913T000000"
    run_dir.mkdir(parents=True)
    (run_dir / "run-records.jsonl").write_text(
        "".join(json.dumps(entry) + "\n" for entry in records), encoding="utf-8"
    )
    return run_dir


STANDARD_RECORDS = [
    record("L1-01", "L1", "PASSED", True, BASE, 100_000, 300),
    record("L1-02", "L1", "PASSED", True, BASE + 200_000, 270_000, 300),
    record("L6-01", "L6", "FAILED", False, BASE + 500_000, 100_000, 900),
    record("L6-02", "L6", "FAILED", False, BASE + 700_000, 1_000, 900),
]


class UsageReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.database = self.root / "runtime.db"
        create_database(self.database)

    def tearDown(self):
        self.directory.cleanup()

    def report(self, records):
        run_dir = write_run(self.root, records)
        return USAGE.build_usage_report(run_dir, self.database, None, 0.9)

    def case(self, report, case_id):
        return next(entry for entry in report["cases"] if entry["caseId"] == case_id)

    def test_every_case_is_matched_to_its_own_agent_run(self):
        report = self.report(STANDARD_RECORDS)

        self.assertEqual("COMPLETED", self.case(report, "L1-01")["internalEnd"])
        self.assertEqual("DEADLINE?", self.case(report, "L1-02")["internalEnd"])
        self.assertEqual("FAILED:MODEL_RESPONSE_INVALID", self.case(report, "L6-01")["internalEnd"])
        self.assertEqual("NO_RUN", self.case(report, "L6-02")["internalEnd"])
        self.assertEqual(["L6-02"], report["totals"]["unmatched"])

    def test_the_stray_run_between_cases_is_never_attributed(self):
        report = self.report(STANDARD_RECORDS)

        self.assertEqual(3, report["totals"]["matchedRuns"])
        self.assertEqual(167_500, report["totals"]["tokens"]["total"])

    def test_per_case_usage(self):
        entry = self.case(self.report(STANDARD_RECORDS), "L1-01")

        self.assertEqual({"total": 105_000, "input": 100_000, "cachedInput": 40_000, "output": 5_000}, entry["tokens"])
        self.assertEqual(40, entry["cacheHitPercent"])
        self.assertEqual((3, 4), (entry["modelCalls"], entry["toolCalls"]))
        self.assertEqual((1, 1), (entry["modelCallFailures"], entry["modelRetries"]))
        self.assertEqual((65_000, 16_250, 30_000), (entry["modelMillis"], entry["modelAvgMillis"], entry["modelMaxMillis"]))
        self.assertEqual({"stream_response_too_large": 1}, entry["modelFailureCodes"])
        self.assertEqual({"tool_calls": 2, "stop": 1}, entry["finishReasons"])
        self.assertEqual({"FAILED": 1}, entry["toolFailures"])
        self.assertEqual((4_700, 1), (entry["toolMillis"], entry["fileEdits"]))
        self.assertEqual(33, entry["budgetUsedPercent"])
        self.assertEqual(12, len(entry["runRef"]))
        self.assertNotIn("run-completed", json.dumps(entry))

    def test_totals(self):
        totals = self.report(STANDARD_RECORDS)["totals"]

        self.assertEqual((2, 4, 50), (totals["passed"], totals["cases"], totals["passRatePercent"]))
        self.assertEqual(83_750, totals["tokensPerPassedCase"])
        self.assertEqual(["L1-02"], totals["nearBudgetCases"])
        self.assertEqual({"cases": 2, "passed": 2, "agentWallMillis": 370_000, "tokens": 157_000}, totals["levels"]["L1"])
        self.assertEqual({"COMPLETED": 1, "DEADLINE?": 1, "FAILED:MODEL_RESPONSE_INVALID": 1, "NO_RUN": 1}, totals["internalEnds"])

    def test_older_records_fall_back_to_the_log_window(self):
        legacy = record("L1-01", "L1", "PASSED", True, None, 100_000, 300)
        run_dir = write_run(self.root, [legacy])
        log = run_dir / "logs" / "L1-01-1.log"
        log.parent.mkdir()
        log.write_text("agent output\n", encoding="utf-8")
        end = (BASE + 100_000) / 1000
        os.utime(log, (end, end))

        report = USAGE.build_usage_report(run_dir, self.database, None, 0.9)

        self.assertEqual("COMPLETED", report["cases"][0]["internalEnd"])
        self.assertEqual(105_000, report["cases"][0]["tokens"]["total"])

    def test_a_missing_database_still_reports_results_and_times(self):
        run_dir = write_run(self.root, STANDARD_RECORDS)

        report = USAGE.build_usage_report(run_dir, self.root / "absent.db", None, 0.9)

        self.assertFalse(report["runtimeDatabaseAvailable"])
        self.assertEqual({"UNAVAILABLE"}, {entry["internalEnd"] for entry in report["cases"]})
        self.assertEqual(2, report["totals"]["passed"])

    def test_a_rehearsal_has_no_agent_usage(self):
        rehearsal = record("L1-01", "L1", "PASSED", True, None, 0, 300, mode="rehearse")

        entry = self.report([rehearsal])["cases"][0]

        self.assertEqual("REHEARSAL", entry["internalEnd"])
        self.assertIsNone(entry["tokens"])

    def test_the_table_renders_every_case_and_the_summary(self):
        table = USAGE.render_table(self.report(STANDARD_RECORDS))

        for text in ("LADDER_USAGE run=", "DEADLINE?", "FAILED:MODEL_RESPONSE_INVALID", "passed 2/4 (50%)", "near budget", "no agent run found: L6-02"):
            self.assertIn(text, table)


class InputResolutionTest(unittest.TestCase):
    def test_the_latest_evaluation_is_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, mtime in (("20260912T000000", BASE / 1000), ("20260913T000000", BASE / 1000 + 60)):
                run_dir = root / name
                run_dir.mkdir()
                records = run_dir / "run-records.jsonl"
                records.write_text("{}\n", encoding="utf-8")
                os.utime(records, (mtime, mtime))
            (root / "not-a-run").mkdir()

            self.assertEqual("20260913T000000", USAGE.resolve_run_dir("latest", root).name)
            self.assertEqual("20260912T000000", USAGE.resolve_run_dir(str(root / "20260912T000000"), root).name)
            with self.assertRaises(SystemExit):
                USAGE.resolve_run_dir(str(root / "not-a-run"), root)

    def test_the_database_path_is_read_from_the_distribution_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            configuration = Path(directory) / "haifa-coding.yaml"
            configuration.write_text(
                "models:\n  default: x\npersistence:\n  mode: SQLITE_WITH_JSONL\n  databasePath: 'D:/data/runtime.db'\n",
                encoding="utf-8",
            )
            self.assertEqual(Path("D:/data/runtime.db"), USAGE.database_path_from_configuration(configuration))

            configuration.write_text("persistence:\n  databasePath: ${LADDER_TEST_DB:/fallback/runtime.db}\n", encoding="utf-8")
            self.assertEqual(Path("/fallback/runtime.db"), USAGE.database_path_from_configuration(configuration))

            configuration.write_text("persistence:\n  databasePath: __HAIFA_SQLITE_DATABASE_PATH__\n", encoding="utf-8")
            self.assertIsNone(USAGE.database_path_from_configuration(configuration))


class StatsActionTest(unittest.TestCase):
    def test_stats_needs_no_provider_setup_and_writes_the_usage_report(self):
        ladder = load("run_ladder")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "runtime.db"
            create_database(database)
            run_dir = write_run(root, STANDARD_RECORDS)
            output = io.StringIO()
            previous = os.environ.pop("HAIFA_LADDER_ALLOW_REAL_PROVIDER", None)
            try:
                with redirect_stdout(output):
                    exit_code = ladder.main(["stats", "--run", str(run_dir), "--runtime-db", str(database), "--json"])
            finally:
                if previous is not None:
                    os.environ["HAIFA_LADDER_ALLOW_REAL_PROVIDER"] = previous

            printed = json.loads(output.getvalue())
            written = json.loads((run_dir / "usage-report.json").read_text(encoding="utf-8"))

        self.assertEqual(0, exit_code)
        self.assertEqual(4, len(printed["cases"]))
        self.assertEqual(printed["totals"], written["totals"])
        self.assertNotIn(str(root), json.dumps(written))


if __name__ == "__main__":
    unittest.main()
