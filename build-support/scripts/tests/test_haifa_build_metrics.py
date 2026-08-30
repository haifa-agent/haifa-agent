import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "haifa_build_metrics.py"
SPEC = importlib.util.spec_from_file_location("haifa_build_metrics", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class HaifaBuildMetricsTest(unittest.TestCase):
    def test_resource_aware_default_threads_and_explicit_override(self):
        arguments, threads = MODULE.effective_arguments("L1", 0, ["test"])
        self.assertEqual(1, threads)
        self.assertEqual(["--batch-mode", "--no-transfer-progress", "-T", "1", "test"], arguments)

        arguments, threads = MODULE.effective_arguments("L3", 0, ["verify"])
        self.assertEqual(2, threads)
        self.assertEqual(["--batch-mode", "--no-transfer-progress", "-T", "2", "verify"], arguments)

        arguments, threads = MODULE.effective_arguments("L3", 0, ["-T", "2", "verify"])
        self.assertEqual(0, threads)
        self.assertIn("2", arguments)

    def test_sensitive_maven_properties_are_redacted(self):
        self.assertEqual(
            ["-DapiKey=<redacted>", "-Dnormal=value"],
            MODULE.redacted(["-DapiKey=do-not-store", "-Dnormal=value"]),
        )

    def test_failure_classes_are_distinct(self):
        self.assertEqual("PASS", MODULE.classify(0, "BUILD SUCCESS", False, False, False))
        self.assertEqual(
            "HOST_RESOURCE_OOM",
            MODULE.classify(1, "Native memory allocation failed", False, False, False),
        )
        self.assertEqual(
            "OUTER_TIMEOUT_PROCESS_EXIT_UNKNOWN",
            MODULE.classify(124, "", True, False, False),
        )

    def test_reactor_and_maven_timings_are_parsed(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "build.log"
            log.write_text(
                "\n".join(
                    [
                        "[INFO] Building Haifa Agent Common 0.1.0 [2/4]",
                        "[INFO] Haifa Agent Common ............... SUCCESS [  1.500 s]",
                        "[INFO] Haifa Agent Core ................ SUCCESS [  2.250 s]",
                        "[INFO] Total time:  3.900 s",
                    ]
                ),
                encoding="utf-8",
            )
            parsed = MODULE.parse_maven_log(log)
            self.assertEqual(4, parsed["reactorProjects"])
            self.assertEqual(3900, parsed["mavenReportedMillis"])
            self.assertEqual("Haifa Agent Core", parsed["slowestModules"][0]["module"])

    def test_single_module_build_is_counted_without_reactor_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "build.log"
            log.write_text("[INFO] Building Haifa Agent Common 0.1.0-SNAPSHOT\n", encoding="utf-8")
            self.assertEqual(1, MODULE.parse_maven_log(log)["reactorProjects"])

    def test_console_output_replaces_unencodable_characters(self):
        output = io.TextIOWrapper(io.BytesIO(), encoding="ascii")
        with mock.patch.object(MODULE.sys, "stdout", output):
            self.assertTrue(MODULE.safe_console_write("bad: \ufffd\n"))
            output.seek(0)
            self.assertEqual("bad: ?\n", output.read())

    def test_test_report_facts_discovers_reports_and_computes_totals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            surefire = root / "module-a" / "target" / "surefire-reports"
            surefire.mkdir(parents=True)
            (surefire / "TEST-io.haifa.FastTest.xml").write_text(
                '<testsuite name="io.haifa.FastTest" time="0.150" tests="5" failures="0" errors="0" skipped="1"/>',
                encoding="utf-8",
            )
            failsafe = root / "module-b" / "target" / "failsafe-reports"
            failsafe.mkdir(parents=True)
            (failsafe / "TEST-io.haifa.SlowIT.xml").write_text(
                '<testsuite name="io.haifa.SlowIT" time="2.500" tests="2" failures="1" errors="0" skipped="0"/>',
                encoding="utf-8",
            )

            totals, classes = MODULE.test_report_facts(root, started_epoch=0.0)

            self.assertEqual(2, totals["forkReportFiles"])
            self.assertEqual(7, totals["tests"])
            self.assertEqual(1, totals["failures"])
            self.assertEqual(0, totals["errors"])
            self.assertEqual(1, totals["skipped"])
            self.assertEqual("io.haifa.SlowIT", classes[0]["testClass"])
            self.assertEqual(2500, classes[0]["millis"])

    def test_test_report_facts_skips_noisy_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            node_modules_surefire = root / "frontend" / "node_modules" / "pkg" / "surefire-reports"
            node_modules_surefire.mkdir(parents=True)
            (node_modules_surefire / "TEST-ignored.xml").write_text(
                '<testsuite name="ignored" time="1.0" tests="10" failures="0" errors="0" skipped="0"/>',
                encoding="utf-8",
            )
            git_surefire = root / ".git" / "surefire-reports"
            git_surefire.mkdir(parents=True)
            (git_surefire / "TEST-git.xml").write_text(
                '<testsuite name="git" time="1.0" tests="10" failures="0" errors="0" skipped="0"/>',
                encoding="utf-8",
            )

            totals, classes = MODULE.test_report_facts(root, started_epoch=0.0)

            self.assertEqual(0, totals["forkReportFiles"])
            self.assertEqual(0, totals["tests"])
            self.assertEqual([], classes)

    def test_test_report_facts_handles_corrupt_and_stale_reports_gracefully(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            surefire = root / "target" / "surefire-reports"
            surefire.mkdir(parents=True)
            corrupt = surefire / "TEST-corrupt.xml"
            corrupt.write_text("<not-valid-xml", encoding="utf-8")

            totals, classes = MODULE.test_report_facts(root, started_epoch=0.0)

            self.assertEqual(0, totals["forkReportFiles"])
            self.assertEqual(0, totals["tests"])


if __name__ == "__main__":
    unittest.main()
