# Changelog

- `DefaultMemoryRetriever` refines recall by removing the zero-score candidate filter: memories matching query terms
  take priority, and remaining slots are filled by recency up to `MAX_ITEMS` (increased from 8 to 16) within the token
  budget. Selected snippets are stably ordered by creation timestamp and ID to maximize LLM prompt cache hit rates.

- Memory collapses to direct CRUD with an `AGENT` scope; the candidate/approval path is removed with no compatibility
  layer. `MemoryService` is now `put` / `update(id, expectedRevision, content)` / `delete(id, expectedRevision)` /
  `find` / `list(MemoryQuery)` / `clear(scope)`, bounded by a trusted `MemoryActor(tenant, principal)`; `put` replaces
  the memory with the same scope, kind and subject and leaves identical content untouched, and `MemoryQuery` offers a
  bounded case-insensitive text match. `MemoryScopeType` is `USER` / `AGENT` / `SESSION` (`RUN` had no writer and is
  removed); `AGENT` targets an Agent Definition id and Runtime recalls it from the Run's definition. `Memory` holds
  plain text content, a `revision`, an optional `MemorySourceRef` and timestamps only. A `MemoryDraft.observedAt`
  older than a scope clear or the deletion of the same subject is refused with `MEMORY_WRITE_STALE`, so late
  asynchronous writes cannot resurrect content. `SensitiveMemoryFilter` rejects credential, payment and precise
  identity content with `MEMORY_CONTENT_SENSITIVE` instead of labelling it. `MemoryRetriever` keeps only
  `contextFor`, plus `none()` and `onlyWhen(predicate)` to switch recall off per Run or Agent;
  `MemoryPlatformContribution` gains `withRecallWhen` and `withoutRecall`. Removed public types: every
  `MemoryCandidate*` type, `MemoryStatus`, `MemoryVersion`, `MemoryRef`, `MemoryRecordQuery`, `MemoryContent` and its
  `TextMemoryContent`/`StructuredMemoryContent`/`DerivedTextMemoryContent`/`DerivedTextType` implementations,
  `MemoryPolicy`, `MemoryPolicyDecision`, `MemorySecurityLabel`, `MemoryVisibility`, `MemoryRetentionPolicy`,
  `MemoryEvidenceRef`, `MemoryEvidenceVerifier`, `MemoryAuditEvent`, `MemoryAuditStore`, `MemoryUnitOfWork`,
  `MemoryDerivedDataInvalidator`, `MemoryRetrieval`, `MemorySearchResult`, `DefaultMemoryPolicy`,
  `DeterministicMemoryCandidateExtractor`, `MemoryObservation`, `InMemoryMemoryEvidenceVerifier`,
  `SqliteMemoryEvidenceVerifier`, and the SDK `ProposeMemoryCommand`, `ReviseMemoryCandidateCommand`,
  `ReviewMemoryCandidateCommand`, `RejectMemoryCandidateCommand`, `InvalidateMemoryCommand` and
  `MemoryCandidateListQuery`. `AgentMemories` exposes `put(PutMemoryCommand)`, `update`, `delete`, `find`,
  `list(MemoryListQuery)` and `clear(MemoryScopeSpec)`; `MemoryScopeSpec` offers `user()`, `agent(id)` and
  `session(id)` and never carries a tenant or owner. `ProductMemoryPolicy` becomes
  `(maxContentChars, maxQueryLimit)` with content capped at 4096 characters. `RuntimeCoreBuilder.memory(service,
  retriever)`, `MemoryService.invalidateSource`, `MemoryRepository.allMemories` and the message-redaction Memory
  listener are removed (nothing in production redacted messages); Runtime defaults to `MemoryRetriever.none()`.
  `SqliteSdkContributions.memory(policy)` provides the SQLite Memory component over the same file. SQLite migration
  V15 is a clean cut: it drops `memory_candidate`, `memory_audit_event` and the old `memory_record`, clears the
  derived `memory_selection` rows, and creates a single `memory_record` table plus `memory_scope_clear` watermarks;
  it aborts instead of dropping data when the old tables still hold ACTIVE memories or PENDING candidates (every
  audited Personal Assistant database held none). Personal Assistant removes the candidate REST endpoints
  (`/memory/candidates*`) and `/memory/{id}/versions/{version}/invalidate`, adds `PATCH` and `DELETE
  /memory/{memoryId}` (If-Match revision) and `POST /memory/clear`, and its Memory dialog drops the pending-candidate
  column in favour of edit, delete and clear on the active memories.

- Memory mutations are idempotent by intent instead of by key. Personal Assistant `PATCH /memory/{memoryId}`,
  `DELETE /memory/{memoryId}` and `POST /memory/clear` no longer take an `Idempotency-Key` (it was only
  format-checked, so a retried delete returned 404); the web client stops sending it. Retrying an update with the same
  If-Match revision and content after the first attempt committed returns the current memory and ETag, retrying a
  delete with the same revision returns 204, and a retried clear reports `deleted: 0`; any other stale revision still
  returns 409 (404 for a deleted memory). `DefaultMemoryService` applies these rules for every caller, identical
  content now only counts as already applied at the expected revision or the one after it, and
  `MemoryRepository` gains `deletedFrom(id, expectedRevision)` to recognise the tombstone a delete left behind.
  `SqliteMemoryStore` text queries now fold case like the in-memory store (`Locale.ROOT`, including `Ä/ä` and
  `Σ/σ`) by scanning candidate rows in bounded keyset batches and matching in Java, instead of the ASCII-only SQLite
  `lower()` that silently dropped non-ASCII matches.

- Memory drops governance entry points that no product called. Removed public types: `MemoryConflict`,
  `MemoryConflictResolution`, `MemoryTombstone` and `MemoryAuditSink` (`MemoryAuditStore` now declares `record`
  itself). Removed methods: `MemoryService.resolveConflict`, `evaluateExpiry`, `requestPurge` and `executePurge`;
  `MemoryRepository.saveConflict`, `conflictFor`, `conflicts`, `saveTombstone` and `tombstones`;
  `MemoryCandidateRepository.allCandidates` and `purgeScope`; `MemoryPolicy.canPurge`; `Memory.transition` and
  `Memory.expiredAt`; `MemoryCandidate.expire`; and the `DefaultMemoryService` constructor that took a bare sink
  (pass a `MemoryAuditStore` and a `MemoryUnitOfWork`). Every removed operation either always failed
  (`resolveConflict`) or was rejected by the SQLite store with `MEMORY_DEFERRED_OPERATION`, so SDK and Personal
  Assistant behavior, the SQLite schema and stored payloads are unchanged. `MemoryRetentionPolicy` and the
  `EXPIRED`/`PURGE_PENDING`/`PURGED` status values remain until the Memory record is reshaped.
- SDK applications can steer an active Run. `AgentRuns.submitInput(RunInputCommand)` returns an SDK-owned
  `RunInputResult` with `RunInputStatus` (`ACCEPTED`, `DUPLICATE`, `APPLIED`, `REJECTED`); the caller identity still
  comes only from the SDK caller provider, and the input reaches the model at the next `BEFORE_ITERATION`, never
  inside Tool execution or model request construction. Accepted input is no longer silently lost when the model
  finishes first: input acceptance and the final commit now share one Unit of Work, a final answer produced while
  input is pending is deferred (`completion.deferred` with `PENDING_RUN_INPUT`) until the model has seen it, and any
  terminal transition, including recovery after a restart, rejects still-pending input with a lower-kebab reason such
  as `run-cancelled` and a new public `run.input.rejected` event. A Run that is completing or terminal returns
  `REJECTED` (`run-not-accepting-input`) through the SDK. Runtime behavior change: steer retries are matched by
  intent (target Run and contents), so retrying the same idempotency key with a fresh submission time or Run version
  is a duplicate instead of `IDEMPOTENCY_CONFLICT`, and a retry after settlement reports `APPLIED` or `REJECTED`.
  No SQLite migration is needed; the existing `run_input` `REJECTED` state is now used.
- Minimal Parent–Child Delegation (Agent-as-Tool): parent runs can delegate to child agents with a Tool Call,
  mirroring DeerFlow's `task`; this is not a parent–child communication protocol (no agent messages, child steer or
  event-driven waiting). When the Product Profile
  lists `allowedChildAgents`, the Runtime discloses one model-visible `task` Tool (`agent`, `objective`, optional
  `context` and `expected_output`); every call in a response becomes one ordinary child `AgentRun` in its own
  ephemeral session, the calls run in parallel within `maxParallelChildren` and a process cap (default 3,
  `HaifaAgentBuilder.maxConcurrentChildRuns`), and each Tool Result returns after its child is terminal with the child
  Run ID, Runtime status, bounded summary, the child's own usage and artifact references. When a response mixes `task`
  with ordinary Tools, the delegations run first and the ordinary Tools then run in model order. The child Run ID is
  derived from the parent Run ID and the Tool Call ID, so a retried call re-attaches instead of creating a second
  child; `maxChildRuns` converges through the existing budget-limited completion; each child enforces its own
  `maxWallTimeMillis`; delegation depth is fixed at one; waiting for children no longer counts as parent idle time but
  still counts toward the parent's wall time; a child that needs approval keeps the parent waiting and is approved
  through `pendingInteraction(childRunId)`/`respond`; parent cancel, timeout or recovery terminates its children, and
  recovery settles unfinished children as interrupted without replay. Children neither recall nor write long-term
  Memory, and the parent's usage only counts `childRuns`, never child tokens. Public API changes: new
  `ChildAgentSpec`, `ProductProfile.allowedChildAgents` (the previous ten-argument constructor and `create` keep an
  empty set) and `withAllowedChildAgents`, `HaifaAgentBuilder.childAgent`, `AgentRuns.children` returning the new
  `ChildRunView`, `AgentRuntime.children`, and parent events `child.run.started`, `child.run.completed`,
  `child.run.failed`, `child.run.cancelled` and `child.run.timed-out` carrying `RunEventPayloads.ChildRunLifecycle`.
  Child sessions never appear in the Conversation list. `AgentInvocationMode` keeps only `ROOT` and `AGENT_AS_TOOL`
  (`HANDOFF`, `FORK_JOIN`, `SUBGRAPH`, `SCHEDULED` and `EVENT_TRIGGERED` had no producer). Runtime internals:
  `DelegationPort.executeChild` is replaced by the batch `executeChildren`, `DelegationDecision` now carries every Tool
  request of the response, `RunStateRepository` gains `children(parentRunId)`, and `ResolvedDefinition` gains a
  description and an optional child run profile. No Store or relation table is added; SQLite migration V14 adds
  the `run(parent_run_id, created_at)` index used to list children. A child inherits an immutable snapshot of the
  non-text references in the parent's `AgentRunRequest.inputs` (stored images and audio, image URLs, asset and artifact
  references) but not its free text; the model cannot add or widen them. A process slot is held from child creation
  until the child is terminal and its execution tasks (including one resumed after approval) have ended, so a parent
  that stops waiting never lets more than `maxConcurrentChildRuns` children execute; a child the scheduler rejects is
  failed at once with `RUNTIME_EXECUTION_FAILED` (`CHILD_NOT_SCHEDULED`) instead of staying QUEUED. The parent's
  terminal `child.run.*` event is written by the child's own terminal transition, exactly once, including when the
  child ends after the parent stopped or is settled by recovery. The HTTP transport does not project `child.run.*`
  (`ChildRunLifecycle`) payloads in 0.1.2; SDK event consumers receive them.

- The Personal Assistant real environment starts again. The catalog migration hardcoded
  `haifa.personal.execution.trusted-host-enabled: false` and dropped the `HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED`
  override, so the fail-closed guard rejected every startup; `application.yml` reads the variable again, and
  `scripts/real_environment.py` opts the loopback launch into the trusted-host boundary. The Codex inference binding also
  defaults `codex_originator` to `haifa` (overridable with `HAIFA_CODEX_ORIGINATOR`) instead of failing startup when the
  optional provider ships without an explicit originator, matching the CLI and packaged-client default.

- Coding Agent and Personal Assistant no longer lose a Run when semantic compaction cannot produce an accepted
  summary. Both product policies now set `allowDeterministicDegradedFallback`, so a rejected or unusable summary
  degrades to the deterministic compressor and the Run continues with lower-fidelity history instead of failing with
  `RUNTIME_EXECUTION_FAILED`; `session.compaction-failed` still records the category and the degraded flag. A caller
  that passes its own `CompressionPolicy` keeps it, and `ProjectPersistenceAssembly.configure` now adopts the full
  Coding Agent default only when no policy was set. The deterministic acceptance model answers the compaction and
  Mission Task normalization protocols it had never implemented, and the deep research acceptance test asserts that
  no Run failed and that no Task fell back to a conservative recovery, so a broken fixture fails loudly instead of
  hiding behind the new degradation path.

- Personal Assistant persists model continuations in the trusted-local plaintext format instead of AES-GCM, so the
  product no longer requires a continuation key: `haifa.personal.continuation-key-base64` is removed from
  `PersonalAssistantProperties` and `application.yml`, and the portable Windows package stops generating and reading
  `data/continuation-key.env`. Reasoning payloads stay readable at rest and are guarded only by format, binding and
  content digests. Upgrade action: an existing `data/personal-assistant.sqlite` still holds AES-protected continuation
  rows that this build cannot reveal, so resolving one fails with `CROSS_MODEL_CONTINUATION_INVALID`; clear the
  database, or its `model_continuation` rows, before the first start on this build. The IDE-only
  `PersonalAssistantRealEnvironmentMain` launcher is deleted and `scripts/real_environment.py` collapses to one
  cross-platform lifecycle with three options (`--rebuild`, `--backend-jar`, `--startup-timeout-seconds`): provider,
  model and credential facts come from `application.yml` and the model panel, the launcher stays in the foreground so
  Ctrl+C stops only what it started, and loopback health checks bypass any configured HTTP proxy. The Personal
  Assistant test profiles now set the `model-max-response-bytes` host setting that has been required since the bounded
  streaming change, which restores the `slow-tests` Spring contexts.

- Pure Java applications now consume remote MCP servers by declaration instead of by assembling the MCP Integration
  themselves. `McpServerSpec` (named connection, Streamable HTTP endpoint, explicit Tool allowlist, stable
  `toolNamePrefix`, `readOnly()` governance preset, `required()`/`optional()`, timeouts, environment-backed or dynamic header
  credentials, and lightweight `McpOAuthClientCredentials` refresh with scoped redaction lifecycle) plus `HaifaAgentStarter.builder().mcpServer(...)`
  replace the previous 100+ lines of `McpServerDefinition`/`McpConnectionManager`/`McpToolDiscoveryService`/`McpToolDefinitionMapper`/`McpToolProvider`
  wiring; the MCP Integration itself is unchanged and stays the only MCP Runtime. The SDK's `JavaToolAssembly` is
  renamed to `ToolAssembly` and now registers Java Tools and Integration `ToolRegistration`s on one
  `ToolCatalogBuilder` with one freeze, so a Tool name contributed twice (MCP ↔ MCP or MCP ↔ Java Tool) fails the build
  with `TOOL_ALIAS_CONFLICT` instead of merging frozen catalogs; `HaifaAgentBuilder` gains `toolRegistrations`,
  `managedResource` and `diagnostic`, and `JavaToolAssembly.Prepared.javaToolAliases()` becomes
  `ToolAssembly.Prepared.contributedAliases()`. There is no allow-all import mode, `readOnly()` is a local trust
  declaration rather than a remote claim, a required server fails closed while an optional one degrades to no Tools plus
  a safe diagnostic, and the Agent owns the MCP client lifecycle so `close()` and failed builds both release every
  connection. This release covers MCP Client / Tool consumption only: Haifa still publishes no MCP Server, Tool,
  Resource or Prompt, and a future Spring AI MCP Client Adapter is kept to an independent `haifa-agent-spring` seam
  reading `ToolCallbackProvider` — Core, Runtime, SDK, SDK Starter and the MCP Integration are now held Spring AI free
  by Maven Enforcer and ArchUnit. `haifa-agent-sdk-starter` consequently depends on `haifa-agent-mcp`, so its banned
  dependency list narrows from all `io.modelcontextprotocol.sdk` artifacts to the Spring-bound MCP transports. The
  standalone `examples/haifa-agent-example` build moves to `0.1.1-SNAPSHOT` and adds `PureJavaMcpApplication`, which
  skips itself with an explanation unless `PARTNER_MCP_URL` names a Streamable HTTP MCP endpoint, so offline
  verification stays deterministic.

- OpenAI-compatible Chat, OpenAI Responses, Gemini and Anthropic streaming response limits now distinguish local
  transport boundaries: each SSE event is capped at 1 MiB and each raw stream has a fixed 64 MiB final fallback. The
  semantic response limit remains independently configurable and provider token parameters remain the primary output
  bound. The adapters expose only bounded byte diagnostics, retry at most once before any visible output, and never replay after text or a
  Tool Call was observed. CLI and Personal Assistant share a validated 1 MiB–32 MiB host setting (4 MiB default) across
  their model adapters. Runtime cancellation now distinguishes `USER_REQUEST` from `DEADLINE_EXCEEDED` in snapshots,
  terminal events and storage; CLI deadline expiry writes a stable stderr message and exits with code 124, while
  Personal Assistant exposes the termination reason and maps Mission deadlines explicitly.

- Coding Terminal 移除应用级鼠标事件接管，恢复宿主终端（Windows Terminal、VS Code Terminal、iTerm2 等）原生文本划词选择与剪贴板复制能力。`Tui4jTerminalIo` 禁用 SGR cell-motion 鼠标上报，保持启动与退出时的防御性 mouse reset；删除 `TerminalScreenCells`、`TerminalTextSelection` 及应用层拖拽高亮/自动滚动/剪贴板传输逻辑；历史内容继续由 `PageUp`/`PageDown` 键盘导航视口。

- Review follow-ups for the SDK reduction baseline. SDK Conversation `submit` now re-checks the conversation revision inside the persistence transaction, so two concurrent submits on the same revision start exactly one Run instead of double-dispatching, and a `submit` replay with a changed request fails closed with `CONVERSATION_IDEMPOTENCY_CONFLICT` instead of silently returning the old Run. Status-filtered conversation lists page through the store until the requested statuses fill the limit, `ConversationStore.changeStatus` drops its dead `expected`/`target` parameters, `SkillPlatformContribution` rejects non-empty `scriptExecutionGrants` instead of silently ignoring them, and `JavaToolSpec` rejects `NETWORK_ACCESS` because Java Tools cannot constrain target hosts. `PersonalAssistantAssembler.productDigest` serializes the Memory/Artifact policies deterministically and folds in the Web provider bindings plus the shell runtime identity, `runtime_applied_command` is now `STRICT` (the V1.0 init artifact is regenerated; upgrade action: rebuild local SQLite databases as V13 already requires, and note that rename/archive commands applied before V13 lose their applied-command de-dup ledger and can re-apply once after upgrade), and the SDK Starter declares its policy dependencies explicitly.

- Documentation and standalone consumer examples align with the simplified SDK. Root `README.md`, `haifa-agent-sdk/README.md`, and the architecture baseline update their concept tables, capability summaries, and assembly guides to reflect explicit typed component assembly, single authoritative runtime configuration snapshots, and direct Java Tool registration, removing obsolete references to capability requirements, product contributions, assembly resolvers, and assembly digests. `examples/haifa-agent-example` migrates its assertions from the removed `agent.assembly()` to `agent.profile()` and `agent.diagnostics()`, and offline verification of its pure Java and Spring Boot quickstart modules passes cleanly against the simplified SDK baseline.

- SDK Conversation collapses to a Runtime-owned, display-only layer. `ConversationRecord` is now a read model of `sessionId`, `displayName`, `createdAt`, `lastActivityAt`, `revision` and `status` derived from the Runtime `AgentSession` (CLOSED/DELETED are treated as unavailable), while `tenant`, `principal`, `activeRunId`, `activeRunVersion` and `activeDispatchKey` are removed. `ConversationService.start`/`submit` return the new `ConversationRun` (`ConversationRecord` plus `runId`/`runVersion`), `rename`/`archive`/`unarchive` keep returning `ConversationRecord`, and `ConversationCommandBinding` plus the `sdk_conversation_command` ledger and all reservation/activation/reconciliation code are deleted. `start` recovers an already-bound Run through the Runtime idempotency binding, and `rename`/`archive`/`unarchive` use the Runtime applied-command record for exactly-once; authorization always reads the Runtime session, and `archive`/`unarchive` mutate the Runtime `AgentSession`. The SQLite conversation table (`V13__sdk_conversation_metadata_only.sql`) drops `status`, `active_run_id`, `active_run_version`, `active_dispatch_key` and the command ledger while keeping only display/index metadata plus the immutable tenant/principal authorization index, and the shared `haifa-agent-v1.0-init.sql` artifact is regenerated. Upgrade action: rebuild local SQLite databases and migrate callers from `ConversationRecord.activeRunId()` to `ConversationRun.runId()`.

- Java SDK stops owning Policy semantics and stops auto-approving trusted Skill scripts. `HaifaAgentBuilder.defaultSdkPolicyRules()` is deleted, so the builder never constructs rules on a caller's behalf: the standard rules move to the new Policy preset `PolicyPresets.standardApproval()` (`PRESET_CRITICAL_RISK_DENY`, `PRESET_SIDE_EFFECT_APPROVAL_REQUIRED`, `PRESET_DEFAULT_ALLOW`, `ApprovalMode.ASK`), the SDK Starter selects that preset explicitly, and an assembly that configures Tools without an explicit `policy` component now fails closed inside `RuntimeCoreBuilder` (non-empty tool catalog requires an explicit product policy) instead of inheriting hidden SDK rules. `TrustedSkillScriptPublicToolPolicy` and its test are deleted together with the builder branch that wrapped `PublicToolPolicy` whenever a Skill platform carried script execution grants; no shipped product produced such grants, so registering Skills can no longer change Tool approval behavior and `HAIFA-ADR-020` is superseded. `publicToolPolicyDecorator` now only applies the decorator the caller supplied. This is a source-incompatible clean cut with no compatibility shim; consumers that relied on the implicit rules must pass `PolicyPlatformContribution(PolicyPresets.standardApproval(), new DefaultPolicyDecisionService())` or their own product rules.

- Java SDK simplifies Java Tool registration to one pass and one freeze. `JavaToolAssembly` converts each `JavaTool` into a Tool Core `ToolDefinition` plus `ToolProvider`, registers it on the unified `ToolCatalogBuilder`, freezes once and then uses Tool Core's catalog, invoker and schema validator. The SDK's private `MergedCatalog`/`MergedInvoker`, the catalog-digest rewrite of every frozen binding, the multiplexed schema validator and the base-platform merge are deleted, so `.toolPlatform(...)` and `.tool(...)` can no longer be combined: they fail closed with `JAVA_TOOL_PLATFORM_UNSUPPORTED` instead of silently rewriting the host platform, and a host that owns a Tool platform registers its Tools there. `JavaToolSpec` shrinks to the ordinary Java Tool surface (name, `version`, input/output records, title, description, timeout plus `pure()`/`sideEffects(...)`); `providerId`, `concurrencyPolicy`, `resources`, `credentialRequirements`, `approvalRequirement`, `provenance`, `tags`, `idempotency` and `risk` are no longer mirrored from `ToolDefinition`, and the SDK Tool provider identity is derived as `java.<name>`. `pure()` is the only way to lower risk/idempotency/approval, declaring a side effect revokes the pure declaration, and any other Tool keeps medium risk, unknown idempotency and policy-decided approval; Tools that need the removed fields register through the Tool API. This is a source-incompatible clean cut with no compatibility shim; Java Tool side-effect certainty (`NOT_DISPATCHED → DISPATCHED → ACKNOWLEDGED`), Tool Call/Result correlation and unknown-outcome no-replay are unchanged.

- Java SDK shrinks `ProductProfile` to product selection and defaults. `schemaVersion`, `configurationDigest`, `capabilityRequirements` and the `ProductPolicies`/`ProductMemoryPolicy`/`ProductArtifactPolicy`/`ProductExecutionPolicy` container are removed from the profile; the two default Run Profile fields collapse into a single `ProductRunProfileRef defaultRunProfile`. Memory governance now belongs to `MemoryPlatformContribution`, Artifact governance to `ArtifactPlatformContribution`, and Execution governance is expressed through Policy rules plus Approval verification. `HaifaAgent.profile()` exposes only product identity, Agent Definition version, instructions, default Run Profile reference, budget, limits and Tool/Skill allowlists, and the Runtime Configuration Snapshot stays the only authoritative frozen execution fact. This is a source-incompatible clean cut with no compatibility shim; consumers must construct `ProductProfile` with the new field set and pass governance policies to the owning components.

- Java SDK replaces generic capability resolution with explicit typed assembly. `ProductAssemblyResolver`, `ProductAssembly`, `ProductAssemblyDiagnostic`, `ProductAssemblyException`, `ProductCapabilityMode`, `ProductCapabilityRequirement`, `ProductContributionCoordinate`, `ProductProviderSuitability`, `ResolvedProductContribution`, `SdkContributionMetadata`, `AbstractSdkContribution`, `ProductContribution`, `ProductCapabilities` and `ProductCapabilityId` are deleted. `HaifaAgentBuilder` now takes explicit typed components (`model`, `persistence`, `conversation`, plus optional `toolPlatform`, `skillPlatform`, `memory`, `artifacts`, `policy`, `approval`, `credentials`): missing required components fail closed with stable codes, missing optional components mean the capability is absent, and `ProductProfile` no longer carries capability requirements or coordinates. `HaifaAgent` exposes `profile()` and `AgentDiagnostic` diagnostics instead of `ProductAssembly`/assembly digest, typed components no longer wrap capability metadata, and `JavaToolAssembly` returns the effective Tool platform plus added aliases without rewriting the profile or creating a synthetic contribution. This is a source-incompatible clean cut with no compatibility shim; SDK consumers must migrate from `contribute`/`contributeAll` to the typed component methods.

- Java SDK removes the metadata-only product capabilities. `ProductCapabilities.PROJECT/WORKSPACE/GIT/SHELL/MCP/EXECUTION`, the `ShellPlatformContribution`, `ExecutionPlatformContribution` and SDK `McpToolCatalogContribution` types, the `EXECUTION_POLICY_DISABLED` and `MCP_TOOL_BINDING_INVALID` build guards, and the `ProductProfile.allowedExtensions` field are deleted. Personal Assistant keeps its shared `execution_run` Tool, local MCP Tools and exact-approval behavior, but registers them only through the unified Tool Catalog; the Deterministic Acceptance model now reads host script-runtime identity from the product-owned `PersonalShellRuntime` instead of an SDK contribution. This is a source-incompatible clean cut with no compatibility shim or migration.

- Coding Agent follow-up removes the remaining completion-judge tails. The unused product `PublishedArtifactRequiredChecker` `CompletionPolicy` is deleted while Artifact store/export/query and Runtime's generic `CompletionPolicy` SPI stay intact; the base prompt drops the workspace-change/validation-attempt completion requirement and the "manufacture completion evidence" diff wording (`coding-agent-v1.txt` is now prompt 1.8.4); the stale coding-agent README text about `WORKTREE_ONLY/LOCAL_COMMIT/REMOTE_PUSH/PULL_REQUEST` delivery intent and the artifact checker is removed; and generic Runtime/Terminal tests now use neutral product-requirement blocker examples.

- Coding Agent removes the product judge that decided whether an open-ended coding task was business-complete. Task-mode metadata (`CHANGE/CREATE/ANALYZE/REVIEW`), `CodingDeliveryEvidenceLedger`, `CodingCompletionPolicy`, the `CodingVerificationProfile`/`CodingSessionVerificationConfiguration` frozen session metadata and prompt middleware, validation-candidate matching, and the `validationEvidence`/`validationAttemptRef` tool-result schema are deleted together with their tests. `execution_run` now exposes only real execution facts (process state, raw exit code, bounded output, failure/action codes and optional diff observation counts), and edits made through the shell, generators, customer scripts or direct `git` no longer need a specific mutation marker. Runtime continues to block false success on pending ToolCalls/Interactions/Child Runs, dispatched unknown outcomes, cancellation, timeout, budget/resource exhaustion, and final structured-output protocol; a model that reports partial completion or skipped checks is passed through truthfully instead of being force-repaired. `CodingSessionClient.findOutcome` now projects only the authoritative `CLEAN/PARTIAL/UNCLEAN/IN_PROGRESS` protocol status plus safe diagnostics. This is a source- and schema-incompatible cutover with no migration or compatibility reader, and `CodingSessionCreateOptions` and several constructors change shape.

- Workspace authorization collapses to a single durable authorized-directory fact. The shared `WorkspaceBinding`/`WorkspaceBindingStore`/`WorkspaceBindingResolver`, `WorkspaceLocationRef`/`WorkspaceLocationStore`, `WorkspaceMount`, `WorkspacePermission`/`WorkspacePermissionSet`/`WorkspaceCapabilitySet`, `WorkspaceRoot`/`WorkspacePurpose` containers are deleted; `HostWorkspaceLocationStore` now maps a `WorkspaceId` directly to its canonical root and re-verifies the canonical path, link status and physical directory identity on every file/mutation/execution root resolution. `DefaultExecutionBroker`, `HostGuardedSandboxProvider`, Project Core/Host, Personal Assistant and the MCP stdio test all migrate to the direct `WorkspaceId` + host-location model. The Coding Agent replaces its separate `WorkspaceAccess` persistence and `HostWorkspaceRegistry*` row with one `AuthorizedDirectoryEntry`/`AuthorizedDirectoryStore` (`owner` + `WorkspaceId` + canonical protected root + `READ`/`DEVELOP` + physical fingerprint) and its SQLite table is renamed to `coding_authorized_directory`; the V1007/init schema clean-cuts to this single table with no compatibility reader, so existing dev/test databases must be rebuilt and no `coding_workspace_registry` or `coding_workspace_access` table remains. Explicit new-root authorization (`workspace_attach`), enclosing-root reuse, disjoint roots, real-path containment/link-escape checks, revocation and restart recovery remain, while physical replacement at the same canonical path now fails closed on restart and requires explicit re-authorization instead of silently refreshing the fingerprint. The production-unreachable Workspace Snapshot service/API and the read-only Admin workspace query/view batch are deleted, and documentation no longer describes `host-guarded` as an isolation container or mount. Upgrade action: rebuild local SQLite databases and remove any local code that referenced the deleted binding/location/access types.

- Coding Agent deletes the dedicated Worktree control plane. The `workspace_worktree_create` Tool, its approval/prompt/canonicalization paths, the `GitWorktreeIsolationProvider` Sandbox SPI and Host Provider, the orphaned `GitWorktreeCoordinator`/`RunWorkspaceBinding`/`WorktreeMergeRequest`/`WorktreePatchApplier` Git types, the `APPROVED_WORKTREE_CREATE` registry source, and the worktree-only child binding types (`WorkspacePurpose.CHILD`, `WorkspaceBindingMode.COPY_ON_WRITE`) are removed. System Git is now the single source of truth: the model creates and removes worktrees directly through the generic `execution_run` path inside an already authorized repository root, and Haifa no longer duplicates Git worktree create/merge/release/compensation lifecycle. Ordinary repository and linked-worktree recognition, revision/diff probes, generic `workspace_attach`/`WorkspaceAccess`, credential protection, approval boundaries, unknown-outcome no-replay, timeout/cancellation, and process cleanup are unchanged. Existing SQLite registry rows that still carry the retired source are decoded fail-closed to a disabled state without deleting user files; historical migration CHECK constraints are left untouched. Upgrade action: any `tools.enabled` list that still contains `workspace_worktree_create` must remove that entry; startup now fails fast with a message naming the retired key and pointing to the generic `execution_run` tool for git worktree commands, and no compatibility alias is registered.

- Coding Agent and Execution Core delete the Git/GitHub command micro-DSL. `SystemGitCliCommandClassifier` and `CodingExecutionRiskResolver` are removed, so ordinary `git`, `gh`, wrapper, and customer-script commands run through the same generic `execution_run` path as any other command with real exit codes and bounded stdout/stderr; Java no longer parses target/subcommand/operation, infers business risk, escalates policy, rejects `git -C`, or promotes Git/GH broker preflight failures into product-specific recovery. Approval returns to the frozen Tool execution baseline (`ask` shows the full command, `auto` is the user's broad trusted-host authorization), and classifier-derived output fields (`commandTarget`, `commandRisk`, `effectiveRisk`, `commandOperation`, `commandClassificationReason`, `riskResolutionCode`, `operationHintCode`, `riskResolverVersion`) are gone from schemas and delivery evidence. The smallest evidence-backed credential anti-exfiltration rules are extracted into the narrow `CredentialEgressGuard` (protected authentication environment assignments, `git credential*`, Git credential config overrides, `gh auth token`/`--show-token` and other `gh auth` state mutations) and stay fail closed before dispatch. Workspace/cwd authorization, timeout, cancellation, output limits, unknown-outcome no-replay, the exact product-internal Git read allowlist, and Worktree lifecycle code are unchanged.

- Coding Agent, Personal Assistant, the Skill base, and the CLI distribution remove the built-in shared `git` and `github` Classpath Skills from default Skill sources, default allowlists (`skills.allowed`), the distribution template, the Personal Assistant profile aliases, and adjacent tests. The model already has general Git/GitHub knowledge and uses the system `git`/`gh` executables directly through `execution_run` in an authorized environment; repository-specific conventions come from `AGENTS.md`/`CONTRIBUTING.md`. `task-planning`, `result-verification`, and the Personal Assistant `github-project-watch` Product Skill remain, and `haifa.requires.bins` stays plain metadata. Shipped `skills.allowed` defaults are now `[task-planning, result-verification]`; existing custom configurations that explicitly allow `git` or `github` under `skills.allowed` must remove them to avoid startup failure during allowed Skill availability validation. A user-directory Skill that happens to be named `git` or `github` is now handled by the generic discovery platform without a built-in alias.

- Coding `execution_run` 4.0.0 removes the fixed two-minute omitted-timeout default. Execution uses an explicit timeout or the product maximum, capped by the Run's remaining active wall time. Verification candidates no longer carry or expose a second timeout contract, and their frozen Session metadata clean-cuts to `coding-session-verification/3`. A timed-out process tree whose termination cannot be confirmed stays unknown with `TIMEOUT_TREE_UNCONFIRMED`; incomplete output is reported without replacing a confirmed process state. Unknown side effects remain fail closed and are never automatically replayed. Legacy CLI `execution.defaultTimeoutMillis` configuration now fails fast with a migration message.

- User model credentials and secret storage cut over to the native OS credential store (Windows Credential Manager via JNA Advapi32) and scoped point-of-use delivery. Legacy plaintext `~/.haifa-agent/auth.json` is completely decommissioned without migration; existing stored credentials are not automatically transferred. Upgrading users must reauthenticate (`/login` or web UI) or configure explicit references (`env://NAME` or `os://NAME`). Credentials are now resolved on-demand and redacted strictly within tool execution scopes, while configured `env://` secrets are stripped from child execution process environments.

- Execution API and generic Execution now expose every normally terminated process only as `EXITED` with its raw exit code. Runtime no longer interprets command exit codes, Coding `execution_run` 3.0.0 removes the exit-code allowlist and duplicate semantic/result fields, validation records only a trusted attempt, and generic shell execution no longer manufactures stage/commit/push/PR completion evidence. Frozen Coding delivery intent remains a pre-approval authorization ceiling for stage/commit/push/PR writes, without becoming a completion inference. Infrastructure, timeout, cancellation, resource-limit, and unknown-outcome safety remain unchanged. This is a clean schema cutover with no compatibility reader or migration.

- Execution API, Coding Agent (CA), and Personal Assistant (PA) unify on `ExecutionScratchSpaceSpec.none()` and unbounded process limits (`Optional.empty()`) as the global defaults. Tool execution by default inherits the host's temporary directories (`TEMP`/`TMP`/`TMPDIR`) without creating ephemeral per-tool scratch trees, while timeouts, process tree termination, cancellation, and stdout/stderr byte/line limits remain strictly enforced. `ExecutionScratchSpaceSpec` simplifies its record structure by removing the redundant `required` component and updates its canonical digest prefix to `execution-scratch-space-v2`. PA configurations, internal Git clients, and test fixtures drop hardcoded process limits, and `execution_run` input schemas consistently publish the canonical digest for policy validation.

- Coding Agent prompt version 1.8.1 removes the obsolete requirement for deterministic Change Review evidence after the corresponding Review/Baseline pipeline was removed. Change/create work now relies on authoritative workspace/no-change facts plus any required validation, while read-only diff inspection remains available when it materially helps review.

- Tool identity now uses one provider-safe underscore name from configuration and frozen bindings through model disclosure, persisted ToolCalls, policy, delivery evidence, and provider dispatch (for example, `file_write` and `execution_run`). Dotted tool names, implicit dot/hyphen-to-underscore alias conversion, and the Java SDK's configurable alias builder are removed; `ToolAlias` remains a separately typed frozen field but must equal `ToolName`. This is a clean, source- and data-incompatible cutover with no migration or compatibility reader.

- Semantic compaction is enabled by default. `CompressionPolicy.defaults()` now sets `semanticCompactionEnabled` to true, so Runtime Core routes automatic soft-threshold and overflow-triggered compaction through the validated `SemanticCompactionCoordinator` (structured semantic summary + deterministic checkpoints + lossless recent tail) instead of the deterministic fact-truncation compressor; product assemblies that never overrode the policy pick this up without code changes. Callers can still opt out with `withSemanticCompactionEnabled(false)`, which restores the previous deterministic behavior, and the manual product compaction entry keeps using deterministic compaction as before. The policy window version string moves from `session-window-v2` to `session-window-v3`; because checkpoint compatibility requires an exact policy-version match, existing persisted session summaries stop being reused as Context Window checkpoints and are deterministically regenerated by the next compaction pass, while source messages remain the authoritative facts throughout.

- Pre-existing product environment failures are not part of this change: a stale `C:\Users\wangr\.haifa-agent\coding\data\runtime.db` on the dev host diverts CLI persistence tests to the user profile (`MEMORY persistence does not accept durable store settings` errors and one runtime.db path mismatch in `CliFeatureConfigurationTest`/`CliModelConfigurationTest`/`HaifaCliMainTest`/`IdeCodingAgentMainTest`/`StandaloneCodingAgentsTest`), and a foreign `feat-sandbox-simplification-host-execution` worktree inside the repository root trips `TestingModuleArchitectureTest`. They reproduce identically on the clean `feat-semantic-compression` HEAD without this change; kernel/runtime modules are green.

- Runtime Core removes the public Runtime/HTTP `ExecutionLifecycle` payload and all `execution.*` client events. `ToolPipeline` no longer recognizes Execution aliases or interprets command, workdir, output, exit-code, scratch, or Execution/Coding failure-detail fields; clients receive only generic Tool lifecycle, result-reference, and resource facts. Coding Terminal and Personal Assistant intentionally do not retain command-specific Runtime presentation. This is source- and wire-incompatible; no compatibility reader or migration is provided.

- Runtime Core removes the unused Memory audit assembly parameter. `RuntimeCoreBuilder.memory(...)` now accepts only `MemoryService` and `MemoryRetriever`; `MemoryPlatformContribution` likewise no longer accepts or exposes an audit sink. This is source-incompatible and reflects that Runtime never consumed the audit sink.

- Runtime Core removes the generic Todo completion gate and the remaining business-phase inference from Completion. An unconverged plan no longer produces a `PENDING_TODO` blocker, no longer forces a repair round, and Runtime never rewrites Todo status on the model's behalf; `TodoReconciliationService` and `TodoConvergenceChecker` are deleted together with their Builder assembly and the `DefaultCompletionGuard` constructor parameter. `completion.deferred` now publishes the neutral phase `COMPLETION` instead of guessing `VERIFYING`/`RECOVERING` from `VALIDATION`/`DIFF` substrings in blocker codes, and the generic repair message is `[COMPLETION_REPAIR]` without a phase line or a hardcoded delivery next action. Coding Terminal shows a neutral "Completion deferred" for that event without intermediate narrative work-phase inference. Output contracts, frozen structured-output schemas, product `CompletionPolicy` verdicts, budget limits, uncertain tool execution, pending tool calls/interactions/child runs, repair attempt limits, exhaustion failures and restart recovery all keep their existing semantics. The `DefaultCompletionGuard` constructor change is source-incompatible; events, prompts and persisted data get no migration or compatibility reader.

- Runtime Core and Coding Agent eliminate the specialized `execution-recovery` Interaction and successor tool-call protocol. When explicitly typed, trusted preflight evidence proves that a tool invocation has not dispatched (`ToolDispatchState.NOT_DISPATCHED`), Runtime Core records the terminal `FAILED` status on the tool call, records failure facts (`failureCode`, `NOT_DISPATCHED`) into the tool result part, and returns `CONTINUE`. Credential redaction preserves that typed failure kind, while sandbox binding/configuration invariant failures remain fail closed. The model receives the failure facts in the normal conversation loop and can decide whether to report blockers or issue a brand new ordinary tool call under standard policy. Deleted `ExecutionRecoveryKeys`, `ProjectExecutionRecoveryAuthorization`, `ProjectExecutionRecoverySelector`, and dual recovery profiles from `CodingAgentExecutionPolicy` and `CliExecutionPlatform`.

- Runtime removes task-progress ledgers, failure-cluster strategy escalation and semantic repetition termination. The model selects task strategy within existing resource and authorization limits. Duplicate batch keys and unknown/cancellation safety remain enforced; checkpoint payload 5.0 removes decision fingerprints without legacy readers or migration; obsolete strategy event projections and error codes are removed. Coding prompt is now 1.8.0 and Personal Profile is 1.0.1. Removed Runtime Core Java strategy types and constructor parameters are source-incompatible.

- Runtime Core removes `ResumeCheckpointSelector`; Checkpoint restore receives the persisted Attempt source directly. Explicit missing sources fail closed; unselected restore retains latest fallback. Core constructors and restore method change; persistence schema is unchanged.

- Runtime Core: removed `RequiredArtifactChecker`, its Builder setter and Guard constructor parameter; product artifact checks now implement `CompletionPolicy`. Stable Runtime API and persistence formats are unchanged.

- Runtime Core 删除仅用于字段转换的 `RunFinalizer` / `DefaultRunFinalizer` 及 `DecisionExecutor` 对应构造参数；
  `RuntimeControlTraceReplay` 移至测试源码，不再进入生产制品。`ResumeCoordinator.prepare` 收口为
  同一 UoW 内校验后使用的 `prepareValidated`，同步 resume 只执行一次完整前置校验。SDK/Runtime API、
  最终结果格式、持久化协议与独立恢复/审批重验保持不变。

- 沙箱裁剪为受控宿主执行：删除 `haifa-agent-sandbox-local-native` 模块（bubblewrap / Seatbelt /
  Windows unsupported 三条路径）与工作区全量副本死代码（`WorkspaceIsolationProvider`、
  `EphemeralCopyRequest`、`WorkspaceCopyBudget`、`HostWorkspaceIsolationProvider`）。`SandboxProfile`
  只冻结 Provider、配置摘要、允许的可执行文件与环境名和 Shell 许可；`NetworkPolicy`、
  `SandboxFilesystemPolicy` 与隔离能力位删除，`SandboxCapabilities` 只保留进程树收割声明。
  `host-guarded` 成为唯一 Provider，Windows / Linux / macOS 行为完全一致，CLI 不再有
  `SANDBOX_ADAPTER_UNAVAILABLE` 平台分支；`execution.network` 与 `execution.extraPathPolicies`
  配置键退休（旧配置中被忽略），`provider: local-native` 在启动期 fail closed。平台如实声明不提供
  内核、容器或 namespace 级隔离，详见 `docs/34-sandbox-simplification-and-host-execution-design.md`。
- Coding Terminal 流式 Transcript 增长不再按渲染行数变化触发全屏清除；模型输出新增换行或自动折行时
  继续由 tui4j 正常更新 Viewport，避免每新增一行闪烁一次，同时保留自动跟随和 PageUp 回看语义。
- Coding Terminal 在 alternate screen 中启用应用拥有的 Transcript 鼠标交互：滚轮回看，左键拖拽按
  terminal cell 高亮并复制，支持 CJK/emoji、跨行选择和 viewport 边缘自动滚动，Editor 键盘路由保持独立。
- MCP Client 对 Streamable HTTP 与 ExecutionBroker-backed stdio 增加 `2025-03-26`、`2025-06-18`
  和 `2026-07-28` 精确协议配置；2026 使用无会话 `server/discover`、per-request metadata 和标准 HTTP
  routing/parameter headers，`2024-11-05` 与未知历史版本继续 fail closed；晚于 `2026-07-28` 的有效日期
  版本在连接或协商时返回包含版本号的待适配提示，不重试或猜测未来协议。
- `file.write` 在读写授权的 Workspace 根中遇到不存在的目标时改为原子创建并记录 Create；已有目标仍以
  revision/content-hash 保护整体替换，目录、敏感路径、只读根和并发冲突继续 fail closed。
- 新增原生 Google Gemini `generateContent` Integration，并严格区分官方 Standard 与个人本机
  CLIProxyAPI Antigravity 方言；Coding/Personal composition root 使用静态模型目录、精确 loopback/Bearer
  门禁、Tool allowlist 和 fail-closed Thought Signature continuation，不读取 Antigravity OAuth 或网关 Auth 文件。
- Coding Agent 完成门禁改用 terminal ChangeSet、Workspace Digest、内容寻址 Change Review 和结构化
  Validation Attempt；DIFF 命令和诊断事实继续保留，但不再作为修改任务的完成 fallback。轻量
  Verification Profile 在 Coding Session metadata 冻结有界验证阶梯，runner 输出不参与数量或 scope
  推断；Coding 客户端可直接查询与最终 Run 协议状态分离的 outcome/2 安全投影。
- Runtime 为 Tool 增加只读 reconcile 契约和持久化 dispatch 证据；Coding Agent 用 Execution 终态、
  ChangeSet 与文件摘要收敛未知本地命令/写入结果，未知副作用不重放。Host Sandbox 在 Windows 超时或取消时
  终止并复核整棵进程树，恢复会幂等补齐 ToolCall、Journal、Step 与 Tool Result 消息。
- Personal Assistant 真实环境脚本改从按内容摘要生成的 Maven `target/` 外运行副本启动后端 JAR，避免运行中的
  Java 进程锁定 Maven `target/` 产物；复制前校验 Spring Boot Manifest 与 `BOOT-INF`，残缺产物自动
  重新 `package` 并二次校验；停止时兼容新运行目录和旧 `target/` 命令行身份。
- Runtime 预算耗尽不再把普通文本 Run 一律判定为执行故障：在下一次 Model、Tool、Child 或迭代动作
  dispatch 前受控停止，原子保存用户可见总结和 `PARTIAL_SUCCESS` 结果，并以稳定 warning 记录具体限制
  资源；要求结构化输出的 Run 继续 fail closed。Coding Terminal 的预算卡片同时显示实际限制资源及用量。
- `web.fetch` 新增 Browserless Content 与 Tavily Extract Provider：通过短期 Credential Lease 和 Authorization
  请求头分别获取 JavaScript 渲染 HTML 或清洗后的 Markdown/文本；CLI 和 Personal Assistant 可显式选择，
  Personal Assistant 的 Search/Fetch 配置与凭据现已分离，并默认分别使用 Tavily Search 与 Extract。
- Coding Agent 将 `CodingSessionClient` 提升为产品模块公共 API，并由最高层 CLI 应用提供
  `StandaloneCodingAgents` 标准装配入口；Critical Path 与 Autonomous Delivery Capability Gate
  改为注入产品客户端，CLI shaded JAR/参数/YAML/stdio/退出码由独立 Platform Gate 验证。
- Personal Assistant 模型选择改为 Provider → 模型 → 设置的后端 Profile 驱动体验；DeepSeek V4 Flash/Pro
  的 Chat 与 Anthropic Binding 支持推荐/快速/深度和 high/max，Responses 作为高级连接方式保持推理只读；
  首轮 Conversation 原子保存精确 Binding 与偏好，raw reasoning 只在受保护 Tool continuation/恢复链内流转。
- SDK 新增 `chat(message, Record.class)` 类型化最终输出：复用 Java record Schema/Codec，把输出契约冻结进
  Run 配置，由 OpenAI-compatible Adapter 映射并在 Runtime 终态校验、持久化后解码；Tool Loop 不受最终
  Schema 限制，不提供类型化 partial stream，Unsupported/Invalid/Refusal/Truncation 保持稳定分类。
- Anthropic Messages Structured Output 按 dialect 分流：`standard` 映射官方 `output_config.format`
  JSON Schema，DeepSeek Anthropic-compatible 在未验证该字段前继续 fail closed。
- OpenAI-compatible Integration 新增类型化模型配置 Builder，覆盖现有 Chat Completions、Responses 与
  Anthropic Messages style 的标准/DeepSeek profile，并把超时和受限调用选项纳入冻结配置；Starter
  保留原有 `model(adapter, snapshot)` 高级入口。

## 0.1.0-SNAPSHOT

- SDK 新增复用现有 Conversation/Runtime 的轻量 `chat()` Facade、展示用 `name`、Starter
  默认指令诊断、Caller-scoped 进程内 Prompt Diagnostics，以及确定性 SDK Testkit；不声明 Stable API，
  不增加 Prompt 持久化或新的生产 Starter。
- 将遗漏在工作区外的 SDK 示例工程迁入 `examples/haifa-agent-example`，保持非 Reactor 外部消费者边界；
  独立工程精简为 Pure Java 与 Spring Boot 完整应用，五个不重复的教学主题归入分层 SDK 示例。
- Runtime Demo 统一迁入 `io.haifa.example.runtime`，将薄启动入口与 Model-only、Raw Tool、MCP、Skill
  四个能力场景分离，并明确这些底层装配示例不是 SDK API 或 Live Probe。
- SDK Starter 支持显式注册多模型/多 Provider 并按可信 Run Profile 选择；Spring 自动装配新增有序
  `HaifaAgentStarterCustomizer`，SQLite 采用应用拥有的单机持久化参考装配，不新增 Store-specific Starter。
- 新增按 Basic/Intermediate/Advanced 分层的可运行 SDK 示例，覆盖 Quickstart、类型化 Tool、多模型、
  管理、事件、输出和 SQLite 重启恢复；同时增加独立 Maven/Gradle/Spring 消费者 smoke、文档检查和
  SDK Release Artifact 门禁。
- 修复 macOS WatchService 取消窗口后迟到事件泄漏到下一观察窗口的竞态。
- 新增 `haifa-agent-spring-boot-starter`：自动装配单例 `HaifaAgent`、按 Spring 顺序收集
  `JavaTool` Bean、支持配置属性元数据与用户 Bean backoff，并以安全 Failure Analysis 报告缺失凭据；
  默认沿用 DeepSeek V4 Flash、关闭 Thinking 和进程内开发 Store。
- 新增纯 Java `haifa-agent-sdk-starter`：默认装配 DeepSeek V4 Flash、关闭 Thinking、从
  `DEEPSEEK_API_KEY` 解析凭据并使用进程内 Runtime/Conversation Store，提供可编译的最小 Quickstart。
- SDK 新增类型化 `JavaTool<I, O>`、Java record JSON Schema/编解码与
  `HaifaAgentBuilder.tool(...)` 单 Tool 注册入口；构建时自动派生有效 Profile、合并已有 Catalog，
  并保持统一 Schema、Policy、Approval、Credential、Journal 与精确 binding 执行链路。
- Personal Assistant adds durable Mission execution and an explicit Deep Research mode: frozen
  briefs and Skill coordinates, isolated Task Runs, bounded Search/Fetch, strict source and claim
  validation, partial synthesis, restart-safe final messages, five immutable Artifacts, and a
  shared Web workspace for plan confirmation and research delivery. Release hardening adds
  authoritative Mission usage settlement, fixed execution/capacity limits, deadline and budget
  partial synthesis, readiness and upgrade diagnostics, bounded shutdown, and verified offline
  SQLite/Artifact backup and fresh-directory restore.
- Coding Agent 新增顶层 `haifa-coding resume` 基础能力：支持选择、最近及指定 Session，并可在打开后
  提交首条 Prompt；Terminal 从权威 Session Message Store 恢复最近 100 条安全用户可见历史，活动
  Run 仅只读打开且禁止自动接管、恢复或重复提交。
- 新增默认 `file.patch` 1.1：支持 Codex 风格上下文定位、多文件新增/删除/更新/移动，以及大文件流式
  转换、提交前哈希复核、同目录临时文件和原子替换；`file.write` 仅保留给整体替换的小文件。
- CLI 执行审计从每条命令前后全量 Workspace Manifest 改为一次基线加 WatchService 候选增量哈希；仅在
  事件溢出或观察器失效时全量重建，并保持预执行/执行后两类失败语义不变。
- 修复 Coding Agent 执行失败链：Python 缓存/虚拟环境默认不进入 Manifest，`.gitignore` 否定规则只
  撤销相交目录；Manifest 预检失败明确为 `WORKSPACE_MANIFEST_UNAVAILABLE`，OS 进程启动后才标记
  DISPATCHED；Runtime 保留具体 Tool 错误、取消未启动兄弟调用，并将 Diagnostic ID 落盘为可查询诊断。
- Coding Terminal 现在按 `os.name` / `os.version` / `os.arch` 识别宿主，并为 macOS 使用与真实
  Control/Option 输入一致的 `MAC_SPECIAL` 快捷键符号（如 `⌃O`、`⌥↩`、`⌥↑`）；界面标签与按键判定
  由同一 Shortcut Profile 生成，不把无法从当前终端协议可靠接收的 Command 键显示成可用快捷键。
- Coding Agent 默认不再向模型披露 Java `file.search`；仓库级文件发现和内容搜索改走通用
  `execution_run` OS CLI 主路径，优先使用当前 Shell `PATH` 中的 `rg --files` / `rg`，不可用时由模型
  选择平台适配的替代命令。`file.search` 仍可显式启用以兼容既有配置，产品代码不拼接搜索命令选项。
- Coding `execution.run` 1.8.0 曾引入显式的退出码 allowlist；该过渡契约现已由 `execution_run` 3.0.0
  的事实化进程结果替代。Timeout、Cancel、资源限制和未知终止继续保持独立失败语义。
- Haifa Coding Agent 本地发行包默认改为 `SQLITE_WITH_JSONL + protection=NONE`：数据位于发行目录
  `data/`，无需 continuation key；可显式切换 `AES_GCM + env://HAIFA_CONTINUATION_KEY`。
- Personal Assistant 真实环境的 PowerShell、POSIX、Python 生命周期脚本及单测统一迁移至根目录
  `scripts/`，并同步启动、停止和环境搭建文档中的调用路径。
- 修复 Coding Terminal 未启用鼠标报告而将滚轮退化为输入历史方向键、以及内容超过一屏后因使用
  上一帧 Viewport 尺寸而无法回翻的问题；滚动现在于当前帧布局完成后生效，到达底部后恢复新输出
  自动跟随，并保持 Editor 草稿和输入历史不变。
- Coding Terminal 的 `@path` 补全现在过滤任意点号开头的文件或目录；活动状态显示累计耗时与
  `esc to interrupt`，Editor hint 下方显示当前模型、Workspace 绝对路径及可用的 Git 分支。
- Coding Agent 向模型冻结披露 `execution_run` 使用的宿主 Shell 方言，避免 Windows PowerShell
  环境生成 POSIX 混合命令；执行前后 Manifest 现在按冻结的生成目录/根 `.gitignore` 目录策略过滤，
  避免大型构建产物导致可信结果退化为 `OUTCOME_UNKNOWN`；新增真实批准后执行回归。
- Coding Agent 的普通交互不再因缺少工具或交付证据进入完成修复：未声明可信任务模式且没有权威
  Workspace 修改时，文本回答可正常结束；显式 CHANGE/CREATE/ANALYZE/REVIEW 及已观察到的修改
  继续执行既有证据门禁，真实 Provider 故障仍按原错误终止。
- 修复 Windows Host Guarded PowerShell 将命令不存在或复合原生命令失败误报为成功的问题；Autonomous
  Delivery 生成配置同步显式声明当前 CLI 所需的 DeepSeek dialect/version/streaming 字段，并在迁移
  窗口关闭后统一拒绝测试资产 Schema 1 台账。
- 新增标准 OpenAI Chat Completions dialect，并通过显式 dialect/version/streaming 配置与受信
  Endpoint 治理，为 Coding Agent 和 Personal Assistant 配置第二个 Provider `gpt-5.6-luna`；
  严格兼容该协议的 HTTPS 厂商可使用任意 Provider ID，仅通过配置接入，HTTP 仍只允许显式启用的
  loopback。

- Coding Agent 改用产品拥有的版本化短 Prompt，移除 CLI 中按 Case 累积的方法论字符串；动态 Context
  只披露预算、实际修改、验证、Diff 和缺失证据等事实，生产完成门禁不再依赖关键词验证计划或模型
  自报的语义覆盖标签。
- 新增 macOS/Linux Haifa Coding Agent 可搬运发行目录：打包脚本生成 shaded JAR、无密钥安全配置和
  `haifa-coding` 启动器；将发行目录加入 `PATH` 后可从任意项目目录启动，并默认以当前目录作为
  Workspace。
- 修复 Coding Agent 的 `execution.run` 失败链：macOS 启动器会物理解析符号链接 `JAVA_HOME`；
  Provider 异常会持久化终态 Tool Call、失败 Step 和关联 Tool Result，避免后续对话因孤立 Tool Call
  被模型 Provider 以 HTTP 400 拒绝，同时继续禁止 `OUTCOME_UNKNOWN` 自动重放。
- Coding Terminal Phase C 新增 grapheme/cell-width 安全编辑、完整 Resize 状态保持、ANSI16/256/
  TrueColor/NoColor 回归、修饰 Enter 兼容诊断，以及在产品 Runtime 装配前执行的非 TTY fail-closed
  门禁。
- 修复 Coding Terminal 在 Run 已进入 `COMPLETED`、`FAILED`、`CANCELLED` 或 `TIMEOUT` 后仍保留旧
  active Run 的问题；同一 Run 随后到达的 Checkpoint/Resource 事件不会重新激活它，下一次 Enter
  会创建新 Turn，而不是向已结束的 Run 发送 Steer。
- macOS Coding Terminal 人工测试启动器在 `/quit` 后禁用 Git/通用交互分页器，并对离线 Git 验收
  显式使用 `--no-pager`，避免主屏恢复后被空白的 `less (END)` 页面覆盖。
- Coding Terminal Phase B 新增稳定 ID 的 Tool/Execution lifecycle 原位更新、结构化 Approval 卡片与
  回执、Steer accepted/applied 和持久 Follow-up 反馈、viewport 新输出提示，以及带下一步操作的五类
  恢复诊断；UI 不再解析 Interaction 自由文本或按事件名后缀猜测状态，并在审批/错误时保留编辑草稿。
- Coding Terminal Phase A 按 Pi 参考体验重构单列信息层级：移除常驻 Diagnostics、空 Pending、
  Widgets 和假冻结 Footer，新增明暗自适应的 User/Tool/Execution/Approval/Error 状态 Theme、
  Send/Steer 动态提示、跨平台 Alt/Option 与 Ctrl+J 帮助，并保持 NoColor、60×16 和既有
  CodingSessionClient/Reducer 边界。
- Coding Agent Phase 3 新增 Session 搜索、CAS 重命名、Core 权威归档与逻辑删除、手动线性历史
  Compaction、根 `AGENTS.md` 安全发现和仅影响后续 Run 的 `/reload`、经既有
  Policy/Approval/ExecutionBroker/Sandbox 的 `!`/`!!`，以及脱敏且不覆盖目标的版本化 JSONL
  Session 导出；Tree、Fork、Clone、PTY 与后台 Job 继续延期。
- 新增 `local-native` Sandbox Provider：macOS 使用 Seatbelt、Linux 使用 bubblewrap，作为显式可选
  严格模式兑现一次性命令的 Workspace 文件策略、`NetworkPolicy.DENY` 和进程树收敛；Windows
  明确不支持且不伪装成具有同等级严格隔离。
- CLI 的 `execution.run`、Live E2E 和三端交付 Profile 默认统一为
  `host-guarded + allow + shell auto` 可信本地开发基线；命令保留真实可用路径并支持同一命令内的
  临时 loopback Server，不宣称外部网络隔离。三端 fast CI 与独立 Local Native/Windows unsupported
  门禁分别验证默认体验和可选严格能力。
- 新增 SQLite Runtime 全量持久化与跨进程恢复装配，覆盖 Session、Run、Attempt、Checkpoint、Interaction、Tool Journal、Event/Outbox、配置与扩展状态；Project Application/CLI 支持 `MEMORY`、`SQLITE`、`SQLITE_WITH_JSONL`。
- 新增安全 JSONL Transcript 投影，使用提交后 Outbox、fsync 后确认、eventId 去重、截断/中间损坏诊断、跨进程锁和原子轮转；JSONL 不参与恢复。
- 持久化文件统一执行 POSIX `0700/0600` 或 Windows 当前用户独占 ACL，并补齐事务故障注入、busy 有界重试、磁盘秘密负样本和真实 SQLite 重启测试。
- 新增端到端模型 SSE 输出、稳定 Runtime output cursor/replay/listener，并保持同步 `AgentChatModel` 兼容。
- DeepSeek 默认启用 thinking/high；reasoning 通过受保护 continuation 与 Checkpoint 引用完成 Tool Call 续接，公共输出不包含推理原文。
- 新增阿里云百炼与火山方舟 OpenAI Chat dialect、受治理 Provider factory/profile；方舟显式区分 Model ID 与 Endpoint ID。
- 初始化 Maven 多模块工程、BOM、基础领域模型、Runtime API、架构约束和 CI 基线。
- 新增纯 Java Credential API/Core 与 Tool API/Core，提供 AES-GCM Secret Store、scope 解析、短生命周期 Lease、内容寻址 Tool Catalog、Draft 2020-12 Schema 校验和 Provider 路由。
- Runtime 配置快照改为冻结精确 `FrozenToolBinding`，模型规格由冻结定义派生，并将 Tool 审批改为释放 Worker、Checkpoint 后由新 Attempt 恢复的异步协议。
- Project Application 的 14 个 File/Git/Execution Tool 迁入唯一 Tool Catalog/Provider，同时保留 Workspace、capability 与 ExecutionBroker 边界。
- 新增固定协议 `2025-11-25` 的 MCP Client Integration，使用 MCP Java SDK 2.0.0 支持 Streamable HTTP 与 ExecutionBroker-backed stdio，并将远端 Tool 通过内容寻址 binding 接入唯一 Runtime Tool Pipeline。
- 修复模型工具名的 OpenAI-compatible 协议兼容性：内部点号身份保持不变，模型披露 Alias 改用 `file_read`、`git_status` 等 1-64 位安全名称，并恢复首个模型集成约定的非 strict 工具 Schema 默认值。
- 修复 Tool Result 下一轮模型消息只包含摘要的问题：Runtime 从权威 ToolCall 重建结构化结果与裁剪状态，OpenAI-compatible Adapter 将其编码为关联 Tool Message 内容。
- CLI 新增受控的 Streamable HTTP MCP Server 配置、启动期工具发现与 allowlist/profile 审查，并把已审核远端工具接入现有 Catalog、Runtime Policy 和结构化 Tool Result 链路。
- 新增通用 `execution.run` / `execution_run` Shell Tool：明确区分 DIRECT argv 与 SHELL 文本，由可信 Host 配置选择 Bash/PowerShell，经唯一 ExecutionBroker 执行并关联 FileChangeSet。
- CLI 默认启用 ask 审批的本地 Shell 能力，支持 auto/deny disclosure、实时有界脱敏输出、Output Ref、timeout/Runtime/Ctrl+C 取消和受限环境名称继承；具体 CLI 命令均走同一实现，不含逐命令适配。
- 新增纯 Java Skill API/Core/Base，兼容 `SKILL.md`，提供 SDK/Product/Tenant/User/Project 分层发现、确定性解析、内容寻址冻结、渐进披露、Run 级受控激活、资源读取和两个无外部依赖的基础 Skill。
- Runtime 新增最弱 `SKILL` Context 层与 `skill.load` / `skill.resource.read` Tool；Skill 激活和精确内容引用进入 Checkpoint/Resume，脚本只索引审查而不执行。
- CLI 支持从可信配置装配绝对路径的只读本地用户 Skill 目录，并以显式 `skills.allowed` 控制冻结、摘要披露和激活范围。
- CLI 新增 `--trace summary|detail|jsonl` 与 `--trace-file <path>`，实时输出现有 Runtime 安全 Trace，并按 Provider 区分模型、普通 Tool、MCP 与 Skill 调用；Trace 不包含 Prompt、Tool 原始参数、Credential、reasoning 原文或供应商原始响应。
