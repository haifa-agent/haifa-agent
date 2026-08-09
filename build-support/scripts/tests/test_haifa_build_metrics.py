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


if __name__ == "__main__":
    unittest.main()
