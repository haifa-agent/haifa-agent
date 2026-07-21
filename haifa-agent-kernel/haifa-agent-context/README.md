# Haifa Agent Context

Pure Java context intermediate representation, prompt layering, single-call token budgeting,
deterministic selection, derived-asset text references, and redacted context tracing.

The module depends only on Common, Core, and Model API. It never emits provider DTOs and does not
invoke a model. Runtime is the sole owner of the `AgentContext` to `ModelMessage` conversion.
