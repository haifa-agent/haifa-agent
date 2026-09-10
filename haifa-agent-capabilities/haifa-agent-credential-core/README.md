# Haifa Agent Credential Core

Framework-independent credential brokering and secret redaction.

`DefaultCredentialBroker` resolves plaintext credentials from an in-memory map (sourced from environment variables or application configuration) and provides access to `DefaultSecretRedactor`.

`DefaultSecretRedactor` performs static replacement of known secrets and standard authorization header/token patterns to prevent credential leakage into prompts, tool results, and logs.
