# Haifa Agent Policy Core

`haifa-agent-policy-core` is the pure-Java implementation of the Policy API. It shares policy mechanism between
products; each product owns its immutable rules.

## Current evaluation path

`DefaultPolicyDecisionService` is a deterministic, storeless function:

```text
PolicyRequest + PolicyRuleSet
            |
            v
effect + challenge + reasonCode + safeExplanation + requirementDigest
```

It does not use a Clock, ID generator, database, current-policy pointer, runtime overlay, or persistence adapter.
Matching covers the fixed tenant/product/project/session/action/resource/risk fields declared by
`PolicyRuleMatcher`. Ordering is stable:

```text
DENY > ASK > ALLOW
  -> priority descending
  -> ruleId
  -> ruleVersion
```

No match fails closed with `POLICY_NO_MATCH`. `PolicyRuleSet.contentDigest` is derived only from RuleSet content.
`PolicyRequirementDigest` additionally includes the exact secret-free request fields that can change the approval
requirement; it excludes principal, Run, attempt, project-trust reference, display text, and all live security
authorities.

## Approval boundary

`DefaultApprovalVerificationService` validates approval targets and responder authority independently from policy
evaluation. Ordinary Tool ASK uses the Interaction-owned target and local verification; external authority
requirements continue through the dedicated Approval contribution. Approval never converts `DENY` to `ALLOW` and
cannot replace WorkspaceAccess, host-path checks, Credential enforcement, or Sandbox enforcement.

## Module boundary

```text
haifa-agent-core
       ^
       |
haifa-agent-policy-api
       ^
       |
haifa-agent-policy-core
```

Architecture tests prohibit Runtime, Tool, Execution, Store, Application, Spring, Jackson, MyBatis, JDBC, and product
dependencies.

Legacy Snapshot/Decision-ID/Evidence/Grant/Trust implementations remain only for later source/database removal work.
They are absent from current production assembly and must not be used by new code.
