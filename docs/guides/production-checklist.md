# Production checklist

The safe-default Starter is optimized for development, not durable production operation.

Before deploying an application that depends on Haifa Agent, review these boundaries.

## Identity

- Provide caller identity from a trusted host authentication boundary.
- Do not accept Tenant/Principal identity from prompt text or an untrusted Run request field.

## Persistence

- Choose a durable persistence implementation when restart continuity is a product promise.
- Protect the SQLite database and any external Artifact/media payload directories with appropriate OS permissions and backup procedures.
- Treat JSONL as a projection, not the recovery database.

## Models

- Register only reviewed model/provider bindings.
- Keep endpoint and CredentialRef under trusted host configuration.
- Do not implement implicit fallback that silently changes the frozen model semantics of a Run.

## Credentials

- Keep secret values out of source code, ProductProfile, prompts, logs, and browser responses.
- Use environment/OS secret storage or an application-owned secret manager boundary appropriate to the deployment.

## Tools and execution

- Minimize the Tool allowlist.
- Mark a Tool pure only when it truly has no external side effect.
- Configure Policy/Approval for side effects.
- Remember that host-guarded execution is not hostile-code isolation.

## Recovery

- Decide which user-visible actions must survive restart.
- Test intentional Interaction/pause recovery.
- Test unknown Tool outcome and interrupted execution behavior; do not assume transparent replay.

## Limits

- Set Run budgets, timeouts, model limits, Tool limits, and storage limits appropriate to the product.
- Treat human waiting separately from active execution where the product supports approvals.

## Observability

- Consume safe lifecycle/events rather than logging full prompts/provider bodies.
- Preserve diagnostic IDs for incident investigation.

## Release verification

Use the repository build/test guidance in [build-support/README.md](../../build-support/README.md) and verify the same commit you intend to release.
