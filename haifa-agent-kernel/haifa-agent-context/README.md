# Haifa Agent Context

Semantic summary generation constrains `schemaVersion` to the same `v1` value accepted by validation,
both in the output JSON Schema and the compiler instructions. Unsupported versions still fail closed.
When a mandatory carry-forward item is already present in the previous semantic summary, the compiler
prompt labels its text and sources there and lists only its stable ID in the carry-forward block.
For complete-source compaction, the prompt can index repeated historical source references when
the index reduces its estimated size. The aliases are scoped to that request and resolve to durable
references before summary validation.

## Source and representation boundaries

`ContextProvenance` carries source kind, source ID, source version and content hash. The source
identity and an excerpt's hash are different facts: equal projected text does not merge different
versions. A reference is not an authorization grant. `ContextItem` keeps representation (`ContextContent`),
priority, retention and security beside provenance; it does not create another message store.

Message identity is the persisted `AgentMessageId` within its Session/Run and cursor boundary.
A Tool occurrence uses `ToolCallId` and its owning Run; a Provider correlation ID alone is insufficient.
An `ArtifactRef` includes its persisted version. A current workspace file path is not a historical
version, and a missing saved version cannot be reconstructed by rereading the current file.

Run-local assembly remains bounded by the selected input cursor, frozen configuration/model,
policy/compressor versions and the selected valid summary version. Selecting source items does
not advance `ConversationSummary.coveredThrough`; only a valid summary commit changes coverage.
Read operations and derived projections must not mutate original messages or business state.
Continuous history remains the Runtime default. Its optional Run working-set selector uses
`HistoricalExcerptContent` for sparse cross-Run data, with Run/message/version/range identity and
without protected continuation or assistant/tool replay. Runtime revalidates source identity and
permission at projection. The projection digest is distinct from the original source version.
`WorkingSetCostPolicy` is a pure three-lane predictor with explicit unavailable prices, retention,
quality and reuse exits. A predicted saving is not actual Provider cache evidence.

Pure Java context intermediate representation, prompt layering, single-call token budgeting,
deterministic selection, derived-asset text references, and redacted context tracing.
Conversation summaries remain rebuildable compression products. The default compression policy uses
versioned token-tail percentages; fixed message-group limits are only compaction safety bounds, not the
normal trigger for a rolling window. Governed long-term Memory stays in the Memory capability and reaches
this module only through the closed `MemoryReferenceContent` IR.

Semantic summaries use the normalized `SemanticConversationSummaryV1` schema. Mandatory carry-forward
items retain stable identity, text, and provenance unless a terminal decision cites new batch evidence.
`ConversationSummaryRepository` requires atomic snapshot and source-validating compare-and-set operations;
adapters may not synthesize those operations from separate reads and writes.

The module depends only on Common, Core, and Model API. It never emits provider DTOs and does not
invoke a model. Runtime is the sole owner of the `AgentContext` to `ModelMessage` conversion.

The fallback estimator is conservative for mixed-script text and accounts for prompt wire wrappers,
tool schemas, and recursively structured Tool arguments/results. Its version is recorded in context traces.

`PromptLayer.SKILL` 是低于 Identity、Safety、Policy、Tool Protocol 和 Runtime Instructions 的最弱可移除
指令层。Context 不依赖 Skill API；Runtime/Application Adapter 只在 Skill 已冻结并受控激活后把内容映射为
现有 `PromptComponent`。

`SemanticSummaryValidationException.code()` classifies structural, provenance, stable-identity,
carry-forward and safety failures at their owning rule. Its messages and `validationErrors()` may contain
source details and are not safe telemetry; Runtime emits only bounded codes. Existing constructors remain
available with the structural default, without changing validation or reconciliation rules.

`ContextWindowBudget` shares capacity and safety-margin calculations with Runtime admission and semantic
compaction. `MessageGroupContextContent` may carry occurrence-specific, versioned `HistoricalExcerptContent`
result views; Runtime confirms readability and scope before projection. Views do not mutate source records or
summary coverage. Token policy remains an estimate, with unknown Provider lanes kept distinct.

Complete-source projection refuses unreadable media and asset/Artifact bodies with
`ContextBuildException(UNSUPPORTED_CONTEXT_CONTENT)`. Runtime records source refusal separately from model
failure and cannot turn it into coverage through deterministic fallback.
