from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from .loader import load_transactions


def _money(value: Decimal) -> str:
    return str(value.quantize(Decimal("0.01")))


def analyze_csv(path: str | Path) -> dict[str, object]:
    loaded = load_transactions(path)
    totals: dict[str, float] = defaultdict(float)
    total = 0.0
    for transaction in loaded.transactions:
        amount = float(transaction.amount)
        totals[transaction.category] += amount
        total += amount
    return {
        "transaction_count": len(loaded.transactions),
        "invalid_row_count": loaded.invalid_row_count,
        "total": _money(Decimal(total)),
        "by_category": {
            category: _money(Decimal(amount))
            for category, amount in sorted(totals.items())
        },
    }
