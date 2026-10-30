# Haifa Agent Local Model Auth OpenCode Go

OpenCode Go subscription sign-in for Haifa Coding Agent. The provider is a normal integration
(`unofficial = false`, no local-compatibility switch, no manual gate): the connection center lists
`OpenCode Go` for every user, and the only sign-in method it offers is the authorization code
(RFC 8628 device grant) that the console exposes.

## What this module owns

| Concern | Decision |
| --- | --- |
| Console base | `https://opencode.ai/console` |
| Client identifier | `opencode-cli` (public string of the open source CLI; not a secret, not environment injected) |
| Device grant | `POST /auth/device/code`, poll `POST /auth/device/token` with `urn:ietf:params:oauth:grant-type:device_code` |
| Refresh | `POST /auth/token` with `grant_type=refresh_token` (form encoded) |
| Account label | `GET /api/user`, `id` first, `email` only as a fallback |
| Verification page | `https://opencode.ai/console/device`, displayed without a query string |
| Local credential slot | `model-auth://opencode-go/default`, `methodId = opencode-go`, `clientRegistrationRef = opencode-go-console-v1` |
| Supported login modes | `DEVICE_CODE` only; `BROWSER` fails with `AUTH_LOGIN_MODE_UNAVAILABLE` |

Headless Linux hosts are the primary target: the user code is printed, the verification page can be
opened on any device, and no browser, localhost callback port, or desktop session is required.

## Failure contract

Every transport, protocol, and classification failure surfaces one stable `AUTH_*` reason code and
never a raw response body, device code, or token:

`AUTH_DEVICE_CODE_UNAVAILABLE`, `AUTH_DEVICE_CODE_REQUEST_FAILED`,
`AUTH_DEVICE_CODE_RESPONSE_INVALID`, `AUTH_DEVICE_CODE_POLL_FAILED`, `AUTH_DEVICE_CODE_DENIED`,
`AUTH_DEVICE_CODE_EXPIRED`, `AUTH_DEVICE_RESPONSE_TOO_LARGE`, `AUTH_CANCELLED`,
`AUTH_REAUTH_REQUIRED`, `AUTH_LOGIN_SERVICE_UNAVAILABLE`.

Responses are capped at 256 KiB, requests at 30 s, one attempt at 15 minutes, and the poll interval
grows by 5 s per `slow_down` up to 60 s. Timestamps always come from the injected `Clock`.

## Wiring

`LocalCodingAgent` registers the driver next to the Codex and Antigravity drivers, so the shared
`ExternalLoginRegistry` / `ExternalLoginCoordinator` path applies unchanged and the TUI reuses its
existing device-code card. The TUI entry points are `authentication.loginDevice("opencode-go", …)`,
`/connect opencode-go` (or `/connect opencode device`).

## Configuration

```yaml
models:
  providers:
    - id: opencode-go
      endpoint: https://opencode.ai/inference/go/openai/v1
      credentialRef: model-auth://opencode-go/default
      nativeStreaming: true
      allowedBindings:
        [opencode-go-deepseek-v4-pro, opencode-go-deepseek-flash, opencode-go-kimi-k3]
```

The packaged catalog ships three reviewed bindings (`DeepSeek V4 Pro`, `DeepSeek V4.1 Flash`,
`Kimi K3`) on the
`opencode-go-openai-chat` dialect, which freezes the official HTTPS host and the
`/inference/go/openai/v1` path. The request body stays standard, but the channel's documented
transport contract needs two headers, so the dialect owns exactly those: `x-opencode-session` carries
the run id of the conversation turn, and `User-Agent` names this product
(`haifa-coding-agent/opencode-go`). `OpenAiCompatibleDialects.OPENCODE_GO` adds them through
`OpenAiCompatibleDialect#applyHeaders` on both the streaming and the synchronous path; every other
dialect keeps the shared headers unchanged. Reasoning profiles for this channel are intentionally not
declared yet.

## Verified and unverified behaviour

Verified in this repository: endpoint derivation and query stripping, user-code propagation, poll
classification (`authorization_pending`, `slow_down`, `access_denied`, `expired_token`), response size
and content-type rejection, cancel/expiry semantics, refresh rotation, `invalid_grant` → re-login,
cross-account refresh rejection, and the connection-center method list.

Verified against the live console (2026-10-30, unauthenticated part of the grant): `POST
https://opencode.ai/console/auth/device/code` answers `device_code`, `user_code`,
`verification_uri` (`/console/device`, console-relative), `verification_uri_complete` (same path plus a
`user_code` query), `expires_in` = 600 and `interval` = 5; a pending `POST
https://opencode.ai/console/auth/device/token` answers HTTP 400 with `error` =
`authorization_pending`; and the edge in front of both endpoints answers a client without a
recognised `User-Agent` with an HTTP error page, so every request sends the product user agent
declared in `OpenCodeGoEndpoints#USER_AGENT`.

Verified against the live Go channel (2026-10-30, authorized console account): a Chat Completions
request without `x-opencode-session` is answered with HTTP 400 `missingsessionid` and the documented
routing explanation, which Runtime reports as `MODEL_REQUEST_INVALID`. With the session header and
the product user agent, the same credential, endpoint and configuration completed eight one-shot
Coding Agent runs across all three bindings (`deepseek-v4.1-flash`, `deepseek-v4-pro`, `kimi-k3`),
including three forced `file_list` Tool round-trips and multi-call runs that reported prompt-cache
reads (6144 and 10240 tokens). So the console device-grant access token authenticates Go inference
directly and no Console API key is needed. No `reasoning_content` delta appeared in any run, which
matches the bindings that declare no reasoning profile.

Known gap from the same session: an invalid bearer token is answered with HTTP 400 and an empty body
plus an `x-opencode-log-id` header, so a revoked credential would surface as `MODEL_REQUEST_INVALID`
rather than an `AUTH_*` reason.

Not verified yet (needs an authorized console account): the issued-token payload shape (the client
accepts snake_case, camelCase, `token`/`data` wrappers, and relative or absolute expiry), whether
`org_id`/`supports_org_scope` carries product meaning for Go, the provider's subscription check for
non-Go workspaces, and whether `/auth/token` prefers form encoding for refresh.
