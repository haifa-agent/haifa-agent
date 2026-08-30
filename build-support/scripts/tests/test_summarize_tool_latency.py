import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "summarize_tool_latency.py"
SPEC = importlib.util.spec_from_file_location("summarize_tool_latency", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SummarizeToolLatencyTest(unittest.TestCase):
    def test_summarizes_only_completed_tool_timing_without_rendering_sensitive_fields(self):
        grouped, count = MODULE.summarize([
            {"operation": "tool.execute", "attributes": {"toolName": "file.write"}},
            {
                "operation": "tool.persisted",
                "attributes": {
                    "toolName": "file.write",
                    "toolElapsedMs": 90,
                    "providerInvocationMs": 70,
                    "arguments": "must-not-render",
                    "path": "must-not-render",
                },
            },
            {
                "operation": "tool.persisted",
                "attributes": {"toolName": "file.write", "toolElapsedMs": 110, "providerInvocationMs": 80},
            },
        ])

        self.assertEqual(count, 2)
        self.assertEqual(grouped["file.write"]["toolElapsedMs"], [90, 110])
        rendered = MODULE.render_markdown(grouped, count, Path("trace.jsonl"))
        self.assertIn("| file.write | end-to-end | 2 | 90 | 110 | 110 | 100.0 |", rendered)
        self.assertNotIn("must-not-render", rendered)


if __name__ == "__main__":
    unittest.main()
