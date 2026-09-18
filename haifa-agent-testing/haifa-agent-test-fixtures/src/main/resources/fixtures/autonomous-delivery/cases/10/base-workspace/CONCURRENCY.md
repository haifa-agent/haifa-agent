# Ledger consistency incident

Production occasionally loses credits when several workers settle the same
account. Race-enabled builds also report concurrent map access.

Stable API:

```go
New(initial map[string]int64) *Ledger
(*Ledger).Balance(account string) int64
(*Ledger).ApplyBatch(entries []Entry) error
```

Required semantics:

- `New` copies valid non-negative initial balances;
- `Balance` and `ApplyBatch` are safe for concurrent use;
- a batch aggregates all deltas per account and is atomic;
- empty account names and any final negative balance reject the entire batch;
- rejection leaves every balance unchanged;
- successful concurrent calls are linearizable—no accepted update is lost;
- input maps and entry slices are never mutated.

Use the standard library. The implementation must pass `go test -race`.
