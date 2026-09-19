# Security

Haifa Agent is designed around explicit trust boundaries, but it is not a complete security platform.

## Secrets

Secret values must not enter prompts, ProductProfile, public diagnostics, browser DTOs, or ordinary logs.

Model and Tool integrations should use indirect Credential references and resolve secrets at the trusted execution boundary.

## Policy and approval

Policy decisions are transient. A request classified ASK creates an exact-target Interaction.

Approval means "this trusted responder approved this exact action under the current binding." It is not a reusable bearer token and does not create a general IAM permission.

## Tool inputs

Tool input is schema-validated before execution. Rejected untrusted values should not be echoed into arbitrary diagnostic messages.

## Unknown side effects

If a side-effecting Tool was dispatched and the outcome becomes unknown, Runtime fails closed rather than automatically replaying the action.

## Host execution

The host-guarded provider is **not** a hostile-code sandbox.

It cannot be used as the sole isolation mechanism for untrusted multi-tenant code. Use an external VM/container/security boundary when that threat model applies.

## Filesystem

Application-level workspace/path validation limits what Haifa intends to access. It is not equivalent to kernel-enforced filesystem isolation.

## Public output and logs

Public Runtime events and diagnostics are designed to carry bounded lifecycle facts, not:

- credentials;
- full prompts;
- protected reasoning/continuation;
- raw provider responses;
- arbitrary Tool payloads;
- host filesystem secrets.

## Local applications

The Personal Assistant server is loopback-oriented and the Coding Agent is a trusted local developer tool. Neither should be interpreted as a hardened public multi-tenant service without an additional deployment security layer.
