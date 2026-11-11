# Coding Host SDK (local preview)

`io.haifa:haifa-coding-host` is the reusable Java 21 product owner. It depends on the Coding engine,
existing model/tool/Skill/MCP/Execution/SQLite assembly and the JDK-only Coding API. It has no CLI,
Terminal or Web UI dependency. The engine owns DTO mapping and embedded observation. Classes in
`io.haifa.coding.host.internal` are compatibility implementation, not a supported consumer API.

Use `CodingHosts.start(HostOptions)` explicitly, then retain the `CodingHost` owner handle. Options
supply workspace, data directory, optional trusted configuration, numeric loopback bind/port, product
display name and optional `BrowserOptions`. Trusted configuration must explicitly select durable
persistence (`SQLITE` or `SQLITE_WITH_JSONL`) and its protection; the factory never converts memory
configuration to plaintext storage. The owner database path is `<dataDirectory>/coding.sqlite`;
configured protection and transcript policy are preserved. Configuration does not load workspace
`.haifa` overrides. The environment-map overload supplies existing credential references without a
new tool or credential SPI. Database ownership conflicts fail without takeover.

`owner.embeddedClient()` returns the same safe API used by the remote Client. Closing that client
releases its observations and consent; it does not close Host or cancel accepted execution. Only
`owner.close()` closes the assembly. Legacy standalone/native compatibility remains in CLI.

New Coding Runs default to unified context with authorized history readers. Configure
`context.policy: legacy` to roll back selection. Set `context.historyRetrieval: true` with legacy
to retain saved-version readback. The policy remains frozen for each admitted Run.
A paused Run admitted before the history readers were added can resume when its frozen tool
catalog matches the current catalog before those two additions. The Run retains its original tools.

The native terminal capability adds `product/terminalSession` and `product/activity` to a negotiated
native connection. The Host verifies both Session ownership and Run membership before returning a
bounded activity page. The browser grant does not authorize these operations.

## Optional same-origin browser boundary

Web mode is disabled unless the trusted owner supplies `BrowserOptions(assetRoot, entryPoint)`.
Assets must be packaged trusted application code, outside owner/workspace data; traversal, escaped
traversal and symlinks outside the root are refused. Responses use CSP `default-src 'self'`,
`connect-src 'self'`, `nosniff`, `no-referrer` and `no-store`. The Host serves no arbitrary workspace
file endpoint. The default browser mode is local. For an explicitly configured, single-operator
HTTPS origin, see [the Nginx ingress profile](../../docs/applications/coding-nginx/README.md).

| Endpoint | Behavior |
| --- | --- |
| `GET /browser/…` | Trusted static assets only |
| `GET /browser/v1/info` | Safe protocol/incarnation metadata; no authority |
| `POST /browser/v1/pair` | Strict `PairingRequest {challenge, hostIncarnation}`, exact Origin and Host |
| `POST /browser/v1/rpc` | JSON-RPC 2.0; `Authorization: Bearer <grant>` required |
| `GET /browser/v1/events?subscriptionId=…[&channel=presentation]` | Authorized owned SSE subscription; grant stays in header |

The trusted owner calls `newPairingChallenge()`. This is not exposed through a public administration
HTTP endpoint. Challenge/proof values must never enter ordinary logs, URLs or persistent browser
storage. Pair atomically consumes one challenge and returns the schema-defined `BrowserGrant`
(`grant`, `clientId`, `hostIncarnation`, `expiresAtEpochMillis`). Grant proofs stay only in
page memory. Reinitialization negotiates the same bound client, never allocates a second client.

Browser RPC supports `initialize`, `product/query`, `product/command`, `presentation/subscribe`,
`presentation/history`, `presentation/close`, `preview/subscribe`, `preview/unsubscribe`,
`client/renew`, `client/close`. Native legacy operations cannot be used as browser fallbacks.
`initialize` offers protocol version `1` and optional capabilities `coding-product-v1`,
`session-presentation-v1`, `session-consent-v1`, `preview-v1`. The response identifies the selected
capabilities, Host incarnation, display identity and `clientSource=browser`.
`product/query` accepts the generated `Query`; `product/command` accepts `CommandRequest`.
Browser clients cannot remove stored model credentials through the product logout command. Native
Java clients and embedded clients retain the logout operation.
A duplicate SSE attach is rejected while the original stream keeps its subscription.
All optional record fields are explicit nulls. SSE presentation events carry the canonical update
or resync envelope; preview events carry the separate canonical transient Preview.

A grant binds incarnation, exact allowed origin and one browser client. Missing Origin still requires
a valid grant; present Origin must match exactly (`null` is refused). Supplying a different client ID
is refused. Browser clients never enter the native-client registry, so clearing Origin and using
native RPC or GET events cannot bypass browser authorization.

| Limit | Default | Configurable range |
| --- | --- | --- |
| Challenge lifetime | 2 minutes | 100 ms–10 minutes |
| Grant absolute lifetime | 15 minutes | 100 ms–24 hours |
| Client idle lifetime | 90 seconds | 100 ms–30 minutes |
| Outstanding challenges | 4 | 1–16 |
| Attempts per challenge | 5 | 1–8 |
| Pair exchange rate | 64/minute per Host | fixed |
| Owned resources per browser client | 64 | fixed |

Renew extends only idle lifetime, capped by the original absolute grant expiry. SSE heartbeats do
not renew grants/clients/consent. Expiry checks run every 250 ms, close already established streams,
release views and revoke consent. Owner `revokeBrowserClient(id)` performs the same cleanup.
Admission and revocation share only a short metadata lock; accepted Run/Shell work executes outside
it and continues after revoke. Lost mutation replies remain unknown and are never automatically
replayed. After grant expiry/revoke/restart, pair again and create fresh views and explicit consent.

Authorization errors include `BROWSER_AUTH_REQUIRED`, `BROWSER_AUTH_EXPIRED`,
`BROWSER_ORIGIN_REJECTED`, `PAIRING_REJECTED`, `PAIRING_LIMIT`, `PAIRING_RATE_LIMIT`,
`BROWSER_CLIENT_LIMIT` and `BROWSER_OBSERVATION_UNAVAILABLE`. Invalid grants or cross-client claims
both return `BROWSER_AUTH_REQUIRED`. Capability/unsupported-method errors are explicit; they never cause native fallback.

## Artifacts and verification

Run `scripts/publish-local-sdk.ps1 -Mode coding-product-preview -LocalRepo <isolated-repository>`
from a clean feature worktree. It builds the real thin JAR/POM production closure, expands feature
versions, excludes CLI/TUI, and writes an owned hash/length/source-SHA manifest separately from the
base SDK. It does not install into baseline coordinates. The manifest must say `state=complete`.
See [external examples](../../examples/coding-product-sdk/README.md) and
[Client](../haifa-coding-client/README.md). This is a feature preview, not a stable API release.

Adjacent tests cover public signatures, dependency direction, owner lifecycle, authorization admission,
negative routing, trusted resources and established SSE revoke/expiry with accepted execution alive.
The process/browser acceptance probe in `haifa-agent-testing/scripts/coding-sdk-b2-acceptance.py`
uses copied external example application JARs and actual Chromium against an independent Host JVM.
