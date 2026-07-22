# Haifa Agent Credential API

Pure Java contracts for credential definitions, scoped bindings, authorization requests, short-lived leases, encrypted stores, brokers, and secret redaction. Secret material is never part of a definition or binding.

This module depends only on Core/JDK types and must not expose Spring, JSON library, persistence, provider, or MCP protocol types.

Resolution requests bind tenant, principal, run, exact tool coordinate, purpose, scopes, exposure mode and lifetime. `CredentialLease` is `AutoCloseable`; callers can use secret bytes only through a callback and access is rejected after close. Definitions, bindings and query-safe references never contain plaintext secret material.
