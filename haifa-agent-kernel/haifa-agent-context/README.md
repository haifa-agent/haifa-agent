# Haifa Agent Context

Pure Java context intermediate representation, prompt layering, single-call token budgeting,
deterministic selection, derived-asset text references, and redacted context tracing.
Conversation summaries remain rebuildable compression products. Governed long-term Memory stays in
the Memory capability and reaches this module only through the closed `MemoryReferenceContent` IR.

The module depends only on Common, Core, and Model API. It never emits provider DTOs and does not
invoke a model. Runtime is the sole owner of the `AgentContext` to `ModelMessage` conversion.
