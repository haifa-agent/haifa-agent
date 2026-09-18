# Haifa Agent Policy API

Provider-neutral, pure-Java contracts for transient Action Policy evaluation and approval verification.

## Current M3 contract

- `PolicyRuleSet(rules, defaultRule, approvalMode, contentDigest)` is an immutable, storeless product policy value.
  Its digest covers only the rules, default rule, and approval mode; product, tenant, and Run identity remain outside
  the RuleSet.
- `PolicyDecision(effect, challenge, reasonCode, safeExplanation, requirementDigest)` is a transient result. It has
  no ID, timestamp, snapshot reference, persistence port, or authority semantics.
- `PolicyRequirementDigest` binds only the secret-free facts that can change the approval requirement. It is not an
  identity, bearer, Store key, grant, or substitute for live enforcement.
- `ApprovalVerificationService` remains independent from policy evaluation. Runtime owns Interaction lifecycle,
  response persistence, and restart/resume.

Public effects are `ALLOW`, `ASK`, and `DENY`. An `ASK` must carry a challenge; only an `ASK` may carry one.
Policy cannot expand Tool capabilities, Workspace access, Credential scope, Sandbox availability, or host-path safety.

Snapshot, Decision-ID/Store, Evidence, Grant, Project Trust, and the obsolete full-request digest have been removed.
`PolicyContext` contains only live request context needed by the pure evaluator; CA authorization comes from the current
authorized-directory record and path safety, while PA keeps its own product facts.

This module does not depend on Runtime, Tool, Execution, databases, frameworks, or product-specific policy.

## 标准 preset

`PolicyPresets.standardApproval()` 是 Policy 模块提供的标准 preset：`CRITICAL` 风险 DENY；`FILE_WRITE`、
`PROCESS_EXECUTION`、`NETWORK_ACCESS`、`EXTERNAL_SYSTEM_MUTATION`、`PERMISSION_ELEVATION` 要求审批；其余默认
`ALLOW`，`ApprovalMode.ASK`。preset 只是不可变 `PolicyRuleSet` 数据：产品显式选择它（SDK Starter 选择该
preset，PA/CLI 提供自有 rules），任何装配层都不得代为构造 Policy 规则。
