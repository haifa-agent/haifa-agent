# Coding Evaluation Reliability Baseline

`baseline-v1.json` freezes the aggregate, privacy-bounded facts from the first primary 30-task Coding Agent
evaluation. It references the independently archived dataset, primary result and archive manifest by SHA-256; raw
SQLite databases, JSONL traces, prompts, provider responses and host paths remain outside this repository.

`contracts-v1.json` contains only synthetic model classification, physical Attempt lifecycle, Tool outcome and
Completion evidence cases. It freezes the target semantics before production behavior changes and contains no
language-specific answer, command output or real execution record.

The fixture is a comparison input, not a production policy and not a benchmark answer set. Tests may assert that
future reports preserve the metric dimensions and identities, but production code must never branch on its dataset
ID, language mix or observed failure counts.

`OBSERVED` values were recomputed from the frozen primary CSV and the selected 30 authoritative SQLite databases.
`firstEffectiveWrite` is deliberately `INFERRED`: the historical data proves that nine failed trials had no completed
dedicated file mutation tool call, but cannot rule out a side effect issued through `execution.run`. Consumers must
retain that evidence grade instead of presenting the value as an exact execution fact.
