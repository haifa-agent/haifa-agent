# Google Gemini Integration

Pure Java adapter for the official Gemini `generateContent` protocol. The `standard` binding accepts only Google's
official HTTPS endpoint and `x-goog-api-key`; the `cliproxyapi-antigravity` dialect accepts only explicitly enabled
loopback HTTP and a Bearer downstream credential.

The dialect is for personal local development. Haifa never reads CLIProxyAPI OAuth files, OS keyrings, cookies, or
upstream tokens. Files, Cache, Batch, Embedding, Live API, built-in tools, Interactions, dynamic discovery, and fallback
are intentionally unsupported.

`generateContent` accepts native inline image and audio parts. Image URLs are rejected: callers must resolve trusted
uploads to `ImageDataPart` before invocation. Audio uses `AudioDataPart`. The adapter Base64-encodes both as Gemini
`inlineData`, limits the combined decoded media payload to 12 MiB so the encoded JSON remains below the official
20 MiB inline-request boundary, and never logs or persists the bytes. Personal Assistant applies the narrower upload
allowlist and a 10 MiB per-file limit before this adapter boundary.

Gemini function-call part order and thought signatures are stored as protected provider continuation. Missing or
corrupt signatures fail closed even if a gateway would repair them.
