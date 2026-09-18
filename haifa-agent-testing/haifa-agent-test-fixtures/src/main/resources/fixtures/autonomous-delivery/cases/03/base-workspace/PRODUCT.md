# Local Event Auditor

Build a dependency-free local command-line tool that turns security event
JSONL into a compact, deterministic JSON audit report. Python 3.11 is available
on the target machine.

## Invocation

```text
python3 audit.py INPUT [--out OUTPUT] [--min-risk NON_NEGATIVE_INTEGER]
```

`--min-risk` defaults to `1`. Without `--out`, the report is written to stdout.
With `--out`, create or replace that file and keep stdout empty.

Unreadable input, an unwritable output, unknown options, and invalid option
values print a concise message to stderr and exit with status 2. Individual bad
JSONL records do not fail the command; they contribute to `invalidLines`.

## Input contract

Each non-empty line is one JSON object with these required fields:

- `timestamp`: ISO-8601 timestamp with a UTC `Z` or explicit numeric offset;
- `actor`: non-empty string;
- `action`: one of `LOGIN`, `READ`, `WRITE`, `DELETE`;
- `resource`: non-empty string;
- `outcome`: one of `SUCCESS`, `DENIED`, `FAILED`;
- `ip`: valid IPv4 or IPv6 address.

Blank lines and any record failing the contract are invalid. Extra object
fields are ignored.

## Risk rules

- failed login (`LOGIN` + `FAILED`): 5
- any denied event (`DENIED`): 3
- successful delete (`DELETE` + `SUCCESS`): 2
- everything else: 0

Exactly one rule applies, in the order above.

## Report contract

The top-level object has exactly these keys in this order:

```text
schemaVersion, totalLines, validEvents, invalidLines, riskScore,
byAction, byOutcome, topActors, suspicious
```

- `schemaVersion` is the number `1`.
- `totalLines` includes blank lines.
- `validEvents` and `invalidLines` partition `totalLines`.
- `riskScore` is the sum of valid-event risk.
- `byAction` and `byOutcome` contain only observed values, with keys sorted
  lexicographically and integer counts.
- `topActors` contains at most five objects with keys `actor`, `events`, sorted
  by event count descending and actor ascending.
- `suspicious` contains valid events whose risk is at least `--min-risk`. Each
  object has keys `line`, `actor`, `action`, `outcome`, `risk`; it is sorted by
  risk descending and then original one-based line number ascending.

The JSON must preserve non-ASCII actor names and be byte-for-byte stable for
the same arguments and input. A trailing newline is allowed.

## Delivery

Include a README with usage and examples plus meaningful automated tests.
`python3 -m unittest discover -s tests -v` must run those tests using only the
standard library. Do not edit anything under `fixtures/`.
