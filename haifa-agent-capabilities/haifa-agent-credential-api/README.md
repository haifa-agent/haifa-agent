# Haifa Agent Credential API

Pure Java contracts for credential requirements, brokers, and static secret redaction.

This module depends only on Core/JDK types and must not expose Spring, JSON library, persistence, provider, or MCP protocol types.

Tool definitions declare required credential IDs via `CredentialRequirement`. Runtime platforms supply credentials through `CredentialBroker`, and `SecretRedactor` protects logs and outputs from leaking sensitive values.
