# Agent Runtime

The Runtime is the pure Java execution kernel that coordinates Runs, context construction, frozen model invocation, Tool execution, interactions, persistence, and completion.

## Runtime responsibilities

At a high level, an active Run repeatedly performs:

~~~text
control/budget checks
        ↓
build Context IR
        ↓
invoke the frozen model binding
        ↓
normalize model response
        ↓
final answer | Tool call | interaction/pause
        ↓
persist authoritative facts
        ↓
continue or converge to a terminal state
~~~

Runtime does not maintain a second product-specific planner or progress state machine. The model interprets ordinary Tool failures and chooses whether to retry, change strategy, ask for help, or finish. Runtime enforces hard safety and correctness boundaries.

## Frozen execution

A Run freezes the model/runtime configuration it needs at creation time. Provider adapters are resolved by exact adapter identity/version and model snapshot. Runtime does not silently switch a Run to a newer model binding.

## Tool execution

Tool calls go through schema validation, policy/approval, journal/unknown-outcome protection, credential handling, provider dispatch, and result persistence.

A side-effecting Tool whose outcome becomes unknown is not blindly replayed.

## Context and compaction

Runtime builds structured Context IR rather than concatenating one shared mutable prompt string. Session history remains authoritative. Semantic compaction can produce a bounded conversation summary while preserving the source messages.

## Completion

The Runtime owns lifecycle convergence and resource limits. Product completion policies may require evidence, but ordinary model strategy remains model-owned rather than a second Runtime reasoning layer.

## Output versus durable events

Transient assistant text deltas are process-local output. Durable Runtime events contain bounded, provider-neutral operational facts and intentionally exclude prompt text, model reasoning, credentials, and raw provider responses.
