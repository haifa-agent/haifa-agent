import csv
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path


@dataclass(frozen=True)
class Transaction:
    transaction_id: str
    posted_on: date
    category: str
    amount: Decimal


@dataclass(frozen=True)
class LoadResult:
    transactions: tuple[Transaction, ...]
    invalid_row_count: int


def load_transactions(path: str | Path) -> LoadResult:
    transactions: list[Transaction] = []
    invalid_rows = 0
    with Path(path).open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            transaction = Transaction(
                transaction_id=row["id"].strip(),
                posted_on=date.fromisoformat(row["date"]),
                category=row["category"].strip(),
                amount=Decimal(float(row["amount"])),
            )
            transactions.append(transaction)
    return LoadResult(tuple(transactions), invalid_rows)
