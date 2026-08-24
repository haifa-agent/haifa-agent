# Google Gemini Integration

Pure Java adapter for the official Gemini `generateContent` protocol. The `standard` binding accepts only Google's
official HTTPS endpoint and `x-goog-api-key`; the `cliproxyapi-antigravity` dialect accepts only explicitly enabled
loopback HTTP and a Bearer downstream credential.

The dialect is for personal local development. Haifa never reads CLIProxyAPI OAuth files, OS keyrings, cookies, or
upstream tokens. Files, Cache, Batch, Embedding, Live API, built-in tools, Interactions, dynamic discovery, and fallback
are intentionally unsupported.

Gemini function-call part order and thought signatures are stored as protected provider continuation. Missing or
corrupt signatures fail closed even if a gateway would repair them.
