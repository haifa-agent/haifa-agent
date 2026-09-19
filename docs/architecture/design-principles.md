# Evidence-driven design principles

Haifa Agent deliberately treats "enterprise-grade", "auditable", "recoverable", and "extensible" as outcomes that require evidence, not instructions to create more framework layers.

These principles guide new public abstractions.

## Start from a concrete failure or product promise

Add durable state, a state machine, SPI, or shared domain type only when there is a concrete reason, such as:

- a reproducible failure that a simpler implementation cannot handle safely;
- an explicit cross-restart/retry/approval/recovery promise;
- a security invariant that must be enforced consistently;
- multiple real consumers that need the same semantics.

"Maybe useful later" is not enough.

## Persist only authoritative facts

Recovery does not require copying every transient object.

Prefer one authoritative fact source and rebuild transient views. Duplicate persistence increases disagreement and migration cost.

## Keep product semantics in products

A feature used by Coding Agent does not automatically belong in Runtime.

Move behavior down only when the lower layer owns a genuine invariant or multiple consumers demonstrate stable shared semantics.

## Prefer explicit composition over dynamic meta-frameworks

A typed builder with a small number of explicit components is usually easier to understand, test, and remove than a generic capability-resolution engine.

Introduce dynamic discovery only when the product genuinely needs dynamic discovery.

## Separate safety from complexity

Necessary safety boundaries stay hard:

- exact-target approval;
- secret isolation;
- unknown side-effect protection;
- idempotency;
- cancellation and budget enforcement.

But those requirements do not imply a full IAM platform, universal audit event sourcing, distributed coordinator, or capability marketplace.

## Design for deletion

A good early abstraction should be easy to remove if the evidence disappears.

Prefer local, bounded mechanisms before introducing public types or persistence formats that create long-term compatibility obligations.

## Documentation rule

Public documentation describes current supported behavior. Historical architecture plans, prompts, implementation reports, and retrospectives are useful engineering records, but they should not silently become public contracts.
