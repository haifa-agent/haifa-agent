import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from expense_report import analyze_csv


class ExpenseReportTest(unittest.TestCase):
    def make_csv(self, rows):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "transactions.csv"
        with path.open("w", newline="", encoding="utf-8") as target:
            writer = csv.writer(target)
            writer.writerow(["id", "date", "category", "amount"])
            writer.writerows(rows)
        return path

    def test_summarizes_categories_and_total(self):
        path = self.make_csv(
            [
                ["t-1", "2026-07-01", "food", "0.10"],
                ["t-2", "2026-07-02", "travel", "10.25"],
                ["t-3", "2026-07-03", "food", "0.20"],
            ]
        )
        self.assertEqual(
            {
                "transaction_count": 3,
                "invalid_row_count": 0,
                "total": "10.55",
                "by_category": {"food": "0.30", "travel": "10.25"},
            },
            analyze_csv(path),
        )

    def test_skips_duplicate_ids(self):
        path = self.make_csv(
            [
                ["same", "2026-07-01", "food", "2.00"],
                ["same", "2026-07-02", "travel", "99.00"],
            ]
        )
        self.assertEqual("2.00", analyze_csv(path)["total"])
        self.assertEqual(1, analyze_csv(path)["transaction_count"])

    def test_counts_and_skips_invalid_rows(self):
        path = self.make_csv(
            [
                ["ok", "2026-07-01", "food", "2.00"],
                ["bad-date", "yesterday", "food", "3.00"],
                ["bad-money", "2026-07-02", "food", "NaN"],
                ["too-precise", "2026-07-03", "food", "1.999"],
                ["", "2026-07-04", "food", "1.00"],
            ]
        )
        report = analyze_csv(path)
        self.assertEqual(1, report["transaction_count"])
        self.assertEqual(4, report["invalid_row_count"])

    def test_cli_emits_json(self):
        path = self.make_csv([["t-1", "2026-07-01", "办公", "12.50"]])
        completed = subprocess.run(
            [sys.executable, "-m", "expense_report", str(path)],
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual("12.50", json.loads(completed.stdout)["total"])


if __name__ == "__main__":
    unittest.main()
