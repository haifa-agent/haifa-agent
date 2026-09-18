# Expense Report

Small Python 3.11 library and CLI for summarizing exported card transactions.

Input CSV columns are `id,date,category,amount`. A valid amount is a finite
decimal value with at most two fractional digits, and a valid date uses ISO
`YYYY-MM-DD`. The first occurrence of a transaction ID wins; later duplicates
are ignored. Invalid non-header rows are counted and skipped.

Public API:

```python
from expense_report import analyze_csv

report = analyze_csv("transactions.csv")
```

The returned mapping has `transaction_count`, `invalid_row_count`, `total`, and
`by_category`. Monetary values are JSON strings with exactly two fractional
digits. Categories are emitted in sorted order.

CLI:

```bash
python3 -m expense_report transactions.csv
```

It prints the report as JSON and exits non-zero for unreadable input.
