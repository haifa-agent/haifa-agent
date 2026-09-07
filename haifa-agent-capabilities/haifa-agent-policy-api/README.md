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

## Legacy removal boundary

Snapshot, Decision-ID, Evidence, Grant, and Project Trust types remain source-level database compatibility material
until the later removal milestones. They are not exposed by current SDK or production SQLite assembly and normal
production flows do not write them. Do not use those types for new integrations.

This module does not depend on Runtime, Tool, Execution, databases, frameworks, or product-specific policy.
