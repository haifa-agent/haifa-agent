# Google Gemini Integration

Pure Java adapter for the official Gemini `generateContent` protocol. The `standard` binding accepts only Google's
official HTTPS endpoint and `x-goog-api-key`; the `cliproxyapi-antigravity` dialect accepts only explicitly enabled
loopback HTTP and a Bearer downstream credential; the `antigravity-direct` dialect accepts the governed CloudCode PA API
endpoint (`https://cloudcode-pa.googleapis.com/v1internal`) using direct Google OAuth Bearer credentials and Antigravity headers.

The dialects are for personal local development. Haifa never reads external OAuth files, OS keyrings, or cookies directly from disk.
429 quota exhaustion (`QUOTA_EXHAUSTED` / `INSUFFICIENT_G1_CREDITS_BALANCE`) triggers non-retryable fail-closed errors.

`generateContent` accepts native inline image and audio parts. Image URLs are rejected: callers must resolve trusted
uploads to `ImageDataPart` before invocation. Audio uses `AudioDataPart`. The adapter Base64-encodes both as Gemini
`inlineData`, limits the combined decoded media payload to 12 MiB so the encoded JSON remains below the official
20 MiB inline-request boundary, and never logs or persists the bytes. Personal Assistant applies the narrower upload
allowlist and a 10 MiB per-file limit before this adapter boundary.

Gemini function-call part order and thought signatures are stored as protected provider continuation. Missing or
corrupt signatures fail closed even if a gateway would repair them.
