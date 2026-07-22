# Haifa Agent Tool API

Provider-neutral Tool contracts. A tool's immutable identity is `name + semanticVersion + providerId + definitionHash`; the model-visible alias is a separate frozen binding. Public JSON values are represented as deeply immutable JDK maps/lists/scalars.

Providers receive only the exact frozen binding, validated Core arguments, trusted caller/run references, deadline/cancellation data, idempotency key, and short-lived credential leases.

Tool versions are strict SemVer and Runtime matching is exact—there is no implicit `1`/`1.0` expansion. `ToolInvoker.validateBinding` lets a runtime fail closed during resume when an exact frozen provider or definition is no longer available.
