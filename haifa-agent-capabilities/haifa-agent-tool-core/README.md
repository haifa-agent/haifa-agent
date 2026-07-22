# Haifa Agent Tool Core

Build-then-freeze catalog, deterministic definition/catalog hashing, provider routing, model disclosure mapping, and bounded JSON Schema Draft 2020-12 validation for the platform's supported keyword subset. Remote and file references are rejected.

Runtime depends on Tool API only; applications assemble this module with concrete providers and inject the frozen catalog, invoker, and schema validator.

Canonical definition hashing uses UTF-8 SHA-256 over a platform-owned serialization with lexically sorted map keys and security/behavior fields included; alias and runtime health are excluded. Catalog snapshots are alias-sorted and content-addressed, reject duplicate aliases/coordinates/providers, and cannot be mutated after freeze.

The schema validator supports the bounded Draft 2020-12 subset used by built-in tools: object/properties/required/additionalProperties, arrays/items, scalar types, enum/const, numeric/string/collection bounds, document-local `$ref`, and allOf/anyOf/oneOf. Catalog build rejects non-document-local or unresolved `$ref`, schemas deeper than 64 levels, schemas over 4,096 nodes or 1 MiB of schema text, and `pattern` (excluded to avoid unbounded regular-expression execution). Invocation validation is limited to 10,000 instance nodes, 64 levels, 20 errors, 1,000,000 characters per string, and a 100 ms soft deadline; errors return bounded structured paths without echoing full values.
