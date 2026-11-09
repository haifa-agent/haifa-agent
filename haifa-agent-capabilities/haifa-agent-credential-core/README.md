# Haifa Agent Credential Core

Framework-independent credential brokering and secret redaction.

`DefaultCredentialBroker` resolves credentials on demand via a dynamic lookup function (`Function<String, Optional<String>>`) and automatically registers newly resolved non-blank secrets with `SecretRedactor`. It avoids retaining a permanent in-memory broker map of plaintext secrets. Legacy static map constructors are deprecated.

`DefaultSecretRedactor` performs thread-safe dynamic registration and replacement of known secrets as well as standard authorization header/token patterns to prevent credential leakage into prompts, tool results, diagnostics, and logs.


`DefaultSecretRedactor.openStream(maximumPendingCharacters)` provides thread-confined, bounded fragment
redaction using the same registered secrets and credential patterns as whole-text redaction. It forwards unambiguous
progress immediately and withholds ambiguous prefixes/unterminated values until another fragment establishes a
safe boundary. Capacity exhaustion closes the redaction stream with a content-free exception; callers must drop
that observation rather than resume from an unknown suffix. Unfinished carry may be discarded when a best-effort
observation ends. This is transient redaction state, not credential storage or a durable event stream.
