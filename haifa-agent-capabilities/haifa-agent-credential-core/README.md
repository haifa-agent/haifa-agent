# Haifa Agent Credential Core

Framework-independent credential brokering and secret redaction.

`DefaultCredentialBroker` resolves credentials on demand via a dynamic lookup function (`Function<String, Optional<String>>`) and automatically registers newly resolved non-blank secrets with `SecretRedactor`. It avoids retaining a permanent in-memory broker map of plaintext secrets. Legacy static map constructors are deprecated.

`DefaultSecretRedactor` performs thread-safe dynamic registration and replacement of known secrets as well as standard authorization header/token patterns to prevent credential leakage into prompts, tool results, diagnostics, and logs.
