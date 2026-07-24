# Haifa Agent Context

Pure Java context intermediate representation, prompt layering, single-call token budgeting,
deterministic selection, derived-asset text references, and redacted context tracing.
Conversation summaries remain rebuildable compression products. Governed long-term Memory stays in
the Memory capability and reaches this module only through the closed `MemoryReferenceContent` IR.

The module depends only on Common, Core, and Model API. It never emits provider DTOs and does not
invoke a model. Runtime is the sole owner of the `AgentContext` to `ModelMessage` conversion.

`PromptLayer.SKILL` 是低于 Identity、Safety、Policy、Tool Protocol 和 Runtime Instructions 的最弱可移除
指令层。Context 不依赖 Skill API；Runtime/Application Adapter 只在 Skill 已冻结并受控激活后把内容映射为
现有 `PromptComponent`。
