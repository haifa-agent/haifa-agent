# Persistence and recovery

Haifa Agent separates durable facts from transient process state. The goal is not to serialize the entire runtime, but to persist the facts required by explicit product guarantees.

## Current durable reference

SQLite is the current single-node durable reference implementation. It provides versioned codecs/migrations and persistence ports for Runtime facts and selected product capabilities.

JSONL is a safe transcript projection. It is useful for inspection and export, but it is not the authoritative recovery source.

## Intentional continuation

Normal pauses and blocking interactions can continue across process restarts when their authoritative persisted facts are present and still valid.

Checkpoint data is deliberately small. It does not duplicate Tool results, Memory, Skill contents, model continuation, or every external capability state.

## Interrupted execution

Unexpected loss of an executing owner is not treated as transparent failover.

Recovery settles an abandoned execution attempt safely. If a side-effecting Tool outcome is unknown, the Run fails with an unknown-outcome classification rather than dispatching the Tool again and guessing that replay is safe.

## Authoritative facts

Examples of authoritative facts include:

- Run, Session, Step, and Attempt state;
- ToolCall result state;
- pending Interaction state;
- durable Runtime events;
- frozen configuration references/digests required to interpret the Run.

Transient assistant text streaming and process-local diagnostics are not recovery facts.

## Product capabilities

A product can add persistence for Memory, Artifacts, Mission state, or other product-owned facts without forcing every capability into a universal snapshot protocol.

This is a deliberate boundary: persistence follows concrete recovery promises, not the word "enterprise".
