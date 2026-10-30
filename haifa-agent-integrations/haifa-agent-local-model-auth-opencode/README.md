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
`/inference/go/openai/v1` path and adds no vendor request extension. Reasoning profiles for this
channel are intentionally not declared yet.

## Verified and unverified behaviour

Verified in this repository: endpoint derivation and query stripping, user-code propagation, poll
classification (`authorization_pending`, `slow_down`, `access_denied`, `expired_token`), response size
and content-type rejection, cancel/expiry semantics, refresh rotation, `invalid_grant` → re-login,
cross-account refresh rejection, and the connection-center method list.

Not verified yet (needs a real console account): the live device-code payload shape, the poll interval
and `expires_in` values the console actually returns, whether `org_id`/`supports_org_scope` carries
product meaning for Go, the provider's subscription check for non-Go workspaces, and whether the
`/auth/device/token` endpoint prefers JSON over form encoding.
