# Haifa Agent Tool API

Provider-neutral Tool contracts. A tool's immutable identity is `name + semanticVersion + providerId + definitionHash`; the model-visible alias is a separate frozen binding. Public JSON values are represented as deeply immutable JDK maps/lists/scalars.

Canonical internal names remain lowercase dot-separated coordinates such as `file.read`. Model-visible aliases use the OpenAI-compatible function-name intersection: 1-64 ASCII letters, digits, underscores, or hyphens, starting with a letter or digit. Applications map namespaced identities to unambiguous aliases such as `file_read` before disclosure.

Model tool specifications use non-strict JSON Schema by default. Strict provider modes remain opt-in until their endpoint and supported-schema constraints are verified end to end.

Providers receive only the exact frozen binding, validated Core arguments, trusted caller/run references, deadline/cancellation data, idempotency key, and short-lived credential leases.

Tool versions are strict SemVer and Runtime matching is exact—there is no implicit `1`/`1.0` expansion. `ToolInvoker.validateBinding` lets a runtime fail closed during resume when an exact frozen provider or definition is no longer available.
