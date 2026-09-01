# Google Gemini Integration

Pure Java adapter for the official Gemini `generateContent` protocol. The `standard` binding accepts only Google's
official HTTPS endpoint and `x-goog-api-key`; the `antigravity-direct` dialect accepts the governed CloudCode PA API
Prod or Daily endpoint (`https://cloudcode-pa.googleapis.com/v1internal` or
`https://daily-cloudcode-pa.googleapis.com/v1internal`) using direct Google OAuth Bearer credentials and Antigravity
headers. The Coding Agent local-compatibility catalog defaults to Daily; endpoint choice is frozen per Run and never
falls back implicitly.
Its private generate envelope supplies a stable numeric `request.sessionId`, omits control-plane `metadata`, and removes
the public Gemini `maxOutputTokens` field that the Antigravity generate endpoint does not accept. Direct responses also
require the CloudCode PA `response` envelope; the adapter unwraps that envelope for both JSON and SSE responses.

The dialects are for personal local development. Haifa never reads external OAuth files, OS keyrings, or cookies directly from disk.
429 quota exhaustion (`QUOTA_EXHAUSTED` / `INSUFFICIENT_G1_CREDITS_BALANCE`) triggers non-retryable fail-closed errors.

`generateContent` accepts native inline image and audio parts. Image URLs are rejected: callers must resolve trusted
uploads to `ImageDataPart` before invocation. Audio uses `AudioDataPart`. The adapter Base64-encodes both as Gemini
`inlineData`, limits the combined decoded media payload to 12 MiB so the encoded JSON remains below the official
20 MiB inline-request boundary, and never logs or persists the bytes. Personal Assistant applies the narrower upload
allowlist and a 10 MiB per-file limit before this adapter boundary.

Gemini function-call part order and thought signatures are stored as protected provider continuation. Missing or
corrupt signatures fail closed even if a gateway would repair them.

## Profile factory and admission

`GeminiModelProfileFactory` derives profiles strictly for 4-tuple bindings registered in `GeminiBindingRegistry`:
- `google-antigravity` + `gemini-3.6-flash` + `google-gemini-generate-content` + `antigravity-direct`
- `google-antigravity` + `gemini-3-flash` + `google-gemini-generate-content` + `antigravity-direct`

The `standard` dialect currently lacks independent live compatibility evidence and remains `UNVERIFIED`.
Unknown `providerModelId` values, unadmitted dialects, or mutated identity dimensions fail closed as `UNVERIFIED`
(`selectable() == false`).
