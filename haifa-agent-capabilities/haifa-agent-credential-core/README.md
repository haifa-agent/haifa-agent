# Haifa Agent Credential Core

Framework-independent credential resolution and protection. The initial store encrypts secrets with AES-GCM using a random nonce, binding reference/tenant/definition as AAD, and obtains its AES key through an injected provider. It is an embeddable baseline, not a claim of production KMS or vault completeness.

Resolution is fail-closed and follows invocation, session, project, user, organization, then system precedence without widening a binding's authorization.

The AES-GCM store uses a fresh 96-bit nonce for every write and authenticates `credential reference + tenant + definition` as AAD. The master key is supplied by `CredentialEncryptionKeyProvider`; this module intentionally does not provide a production KMS/Vault adapter. `DefaultCredentialBroker` is the only decryption route for Tool/Skill/MCP callers and caps leases at binding expiry. Header/environment injection is provider-neutral and leases are closed immediately after execution.
