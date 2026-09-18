# Haifa Agent Skill API

Provider-neutral, pure Java contracts for scoped Skill discovery, immutable registration, deterministic
catalogs, exact Run bindings, progressive disclosure, activation, and bounded resource loading.

Skill content is not authorization. Tool hints never expand a Run's frozen Tool bindings, and this module
contains no YAML, filesystem path, Runtime, provider, Hub, or executable-script types.

## Trusted script grants

`SkillPackageReviewGrant` and `SkillScriptExecutionGrant` are separate pure-Java authorization facts.
The package grant can make one exact `REVIEW_REQUIRED` registration eligible for a catalog; it never
authorizes execution. The script grant can authorize automatic approval only for one exact script resource,
fixed Tool binding, argument policy, runtime/profile/sandbox envelope, and caller scope, and only while its
referenced package grant is also valid.

`SkillTrustSnapshot` is frozen into each Run configuration. Grant state, expiry, revocation, subject, package,
registration, script, Tool, and execution digests are exact-match and fail closed. These contracts contain no
script source, credentials, physical paths, product types, framework types, or script-language-specific types.
