#!/usr/bin/env python3

import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from analyze_coding_runtime import analyze, connect_read_only, resolve_paths, validate_schema, write_report


def payload(values):
    return json.dumps(values, separators=(",", ":")).encode("utf-8")


class AnalyzeCodingRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.data_root = self.root / "data"
        self.data_root.mkdir()
        self.database = self.data_root / "runtime.db"
        self._create_schema()

    def tearDown(self):
        self.temporary.cleanup()

    def _create_schema(self):
        with closing(sqlite3.connect(self.database)) as connection:
            connection.executescript(
                """
                CREATE TABLE runtime_event (
                  event_id TEXT PRIMARY KEY,
                  run_id TEXT NOT NULL,
                  sequence INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  data_payload BLOB NOT NULL,
                  occurred_at INTEGER NOT NULL
                );
                CREATE TABLE run (
                  run_id TEXT PRIMARY KEY,
                  session_id TEXT NOT NULL,
                  status TEXT NOT NULL,
                  error_payload BLOB
                );
                CREATE TABLE tool_call (
                  tool_call_id TEXT PRIMARY KEY,
                  run_id TEXT NOT NULL,
                  tool_name TEXT NOT NULL,
                  arguments_payload BLOB NOT NULL,
                  status TEXT NOT NULL,
                  result_payload BLOB,
                  error_payload BLOB,
                  requested_at INTEGER NOT NULL
                );
                """
            )
            connection.commit()

    def _insert_window(self):
        end = 20_000_000
        with closing(sqlite3.connect(self.database)) as connection:
            connection.executemany(
                "INSERT INTO run(run_id,session_id,status,error_payload) VALUES(?,?,?,?)",
                [
                    ("run-a", "session-a", "COMPLETED", None),
                    (
                        "run-b",
                        "session-a",
                        "FAILED",
                        payload({"code": "MODEL_RESPONSE_INVALID", "category": "MODEL"}),
                    ),
                    ("run-old", "session-old", "COMPLETED", None),
                ],
            )
            connection.commit()
            connection.executemany(
                "INSERT INTO runtime_event(event_id,run_id,sequence,type,data_payload,occurred_at) VALUES(?,?,?,?,?,?)",
                [
                    ("event-a", "run-a", 1, "run.completed", payload({"values": {}}), end),
                    (
                        "event-b",
                        "run-b",
                        1,
                        "tool.recovery-strategy-required",
                        payload({"values": {"attempts": 2, "directive": "CHANGE_STRATEGY"}}),
                        end - 1,
                    ),
                    ("event-old", "run-old", 1, "run.completed", payload({"values": {}}), end - 3_600_000),
                ],
            )
            connection.executemany(
                """
                INSERT INTO tool_call(
                  tool_call_id,run_id,tool_name,arguments_payload,status,result_payload,error_payload,requested_at
                ) VALUES(?,?,?,?,?,?,?,?)
                """,
                [
                    (
                        "tool-a",
                        "run-a",
                        "execution_run",
                        payload(
                            {
                                "values": {
                                    "command": "git status && git diff",
                                    "operationFamily": "INSPECT",
                                }
                            }
                        ),
                        "COMPLETED",
                        payload(
                            {
                                "structuredData": {
                                    "commandTarget": "GIT",
                                    "commandRisk": "LOCAL_READ",
                                    "commandOperation": "INSPECT",
                                }
                            }
                        ),
                        None,
                        end,
                    ),
                    (
                        "tool-b",
                        "run-b",
                        "execution_run",
                        payload(
                            {
                                "values": {
                                    "command": "git push || echo failed",
                                    "operationFamily": "MUTATE",
                                }
                            }
                        ),
                        "FAILED",
                        None,
                        payload(
                            {
                                "code": "TOOL_BUSINESS_FAILURE",
                                "category": "TOOL",
                                "attributes": {
                                    "stableFailureCode": "COMMAND_CLASSIFICATION_REJECTED",
                                    "failureCategory": "POLICY",
                                    "operationFamily": "MUTATE",
                                },
                            }
                        ),
                        end - 1,
                    ),
                    (
                        "tool-old",
                        "run-old",
                        "file_read",
                        payload({"values": {"path": "old.txt"}}),
                        "COMPLETED",
                        payload({"structuredData": {}}),
                        None,
                        end - 3_600_000,
                    ),
                ],
            )
            connection.commit()
        return end

    def test_analyzes_latest_event_window_without_raw_command_or_identifier(self):
        end = self._insert_window()
        with closing(connect_read_only(self.database)) as connection:
            report = analyze(connection, 1)

        self.assertEqual(report["window"]["endEpochMsInclusive"], end)
        self.assertEqual(report["scope"], {"sessions": 1, "runs": 2, "toolCalls": 2})
        self.assertEqual(report["toolStatuses"], {"COMPLETED": 1, "FAILED": 1})
        self.assertEqual(report["failureClasses"], {"POLICY_OR_CLASSIFICATION": 1})
        self.assertEqual(report["recovery"]["maximumAttempts"], 2)
        self.assertEqual(report["schemaVersion"], "1.1.0")
        self.assertEqual(report["requiredMetrics"]["rawToolFailureRate"]["ratePercent"], 50.0)
        self.assertEqual(report["requiredMetrics"]["policyDenialRate"]["denied"], 1)
        self.assertEqual(
            report["requiredMetrics"]["riskEscalationDistribution"]["counts"],
            {"LOW": 1, "MEDIUM": 0, "HIGH": 0},
        )
        self.assertEqual(
            report["requiredMetrics"]["compositeCommandAdmissionCompletionRate"]["total"], 2
        )
        self.assertEqual(
            report["requiredMetrics"]["sameFingerprintRetryAmplification"]["amplifiedAttempts"], 1
        )
        self.assertEqual(
            report["requiredMetrics"]["approvalAskAllowRateByThreshold"]["status"], "UNAVAILABLE"
        )
        serialized = json.dumps(report)
        self.assertNotIn("git status", serialized)
        self.assertNotIn("git push", serialized)
        self.assertNotIn("run-a", serialized)
        self.assertNotIn(str(self.data_root), serialized)
        self.assertRegex(report["failedToolCalls"][0]["commandDigest"], r"^[0-9a-f]{64}$")

    def test_returns_an_empty_report_when_runtime_event_is_empty(self):
        with closing(connect_read_only(self.database)) as connection:
            report = analyze(connection, 4)
        self.assertIsNone(report["window"])
        self.assertEqual(report["scope"], {"sessions": 0, "runs": 0, "toolCalls": 0})
        self.assertEqual(len(report["requiredMetrics"]), 18)
        self.assertEqual(report["requiredMetrics"]["costKnownUnknown"]["status"], "UNKNOWN")

    def test_rejects_non_finite_or_out_of_range_windows(self):
        with closing(connect_read_only(self.database)) as connection:
            for invalid in (0, -1, float("nan"), float("inf"), 8761):
                with self.subTest(invalid=invalid):
                    with self.assertRaisesRegex(ValueError, "latest-hours"):
                        analyze(connection, invalid)

    def test_rejects_an_old_schema_with_a_stable_message(self):
        with closing(sqlite3.connect(self.database)) as connection:
            connection.execute("ALTER TABLE runtime_event RENAME TO runtime_event_current")
            connection.execute("CREATE TABLE runtime_event(run_id TEXT, occurred_at INTEGER)")
            connection.commit()
        with closing(connect_read_only(self.database)) as connection:
            with self.assertRaisesRegex(ValueError, "runtime_event is missing"):
                validate_schema(connection)

    def test_reads_committed_wal_state_while_a_writer_has_uncommitted_data(self):
        writer = sqlite3.connect(self.database)
        try:
            writer.execute("PRAGMA journal_mode = WAL")
            writer.execute(
                "INSERT INTO run(run_id,session_id,status,error_payload) VALUES(?,?,?,?)",
                ("run-committed", "session-a", "COMPLETED", None),
            )
            writer.execute(
                "INSERT INTO runtime_event(event_id,run_id,sequence,type,data_payload,occurred_at) VALUES(?,?,?,?,?,?)",
                ("event-committed", "run-committed", 1, "run.completed", payload({"values": {}}), 50_000),
            )
            writer.commit()
            writer.execute("BEGIN IMMEDIATE")
            writer.execute(
                "INSERT INTO run(run_id,session_id,status,error_payload) VALUES(?,?,?,?)",
                ("run-uncommitted", "session-a", "COMPLETED", None),
            )
            with closing(connect_read_only(self.database)) as reader:
                report = analyze(reader, 1)
            self.assertEqual(report["scope"]["runs"], 1)
        finally:
            writer.rollback()
            writer.close()

    def test_refuses_output_inside_the_data_root(self):
        with self.assertRaisesRegex(ValueError, "outside the data root"):
            resolve_paths(str(self.data_root), str(self.data_root / "report.json"))

    def test_refuses_to_overwrite_an_existing_report(self):
        output = self.root / "report.json"
        output.write_text("existing", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "already exists"):
            write_report({"schemaVersion": "test"}, output)

    def test_replay_fixture_is_synthetic_and_privacy_bounded(self):
        fixture = (
            Path(__file__).resolve().parent.parent
            / "haifa-agent-testing"
            / "haifa-agent-test-fixtures"
            / "src"
            / "main"
            / "resources"
            / "fixtures"
            / "coding-runtime-reliability"
            / "replay-v1.json"
        )
        value = json.loads(fixture.read_text(encoding="utf-8"))
        self.assertEqual(value["schemaVersion"], "1.0.0")
        self.assertEqual(len(value["cases"]), 12)
        serialized = json.dumps(value).lower()
        for forbidden in ("api_key", "authorization:", "bearer ", "reasoning_content", "sk-"):
            self.assertNotIn(forbidden, serialized)
        self.assertFalse(value["privacy"]["containsProviderResponses"])
        self.assertNotRegex(serialized, r"[a-z]:\\")
        self.assertNotRegex(serialized, r"/(users|home)/")


if __name__ == "__main__":
    unittest.main()
