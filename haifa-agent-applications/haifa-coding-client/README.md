# Coding Java Client SDK (local preview)

`io.haifa:haifa-coding-client` is a Java 21 native loopback Client. Production dependencies are only
`haifa-coding-api` and Jackson. JDK HttpClient supplies HTTP/SSE; there is no engine, Runtime, SQLite,
Host, CLI, TUI or Spring dependency. ArchUnit and Maven Enforcer check this direction.

`CodingClients.connect(URI)` or `connect(ClientOptions)` returns the pure `CodingClient` API:
connection metadata, finite typed `query`/`execute`, presentation `observe`/`open`/`history`,
transient `preview` and explicit `renew`/`close`. DTO mapping and the embedded implementation belong
to the engine; the network Client only validates/serializes transport. Product result DTOs do not
expose Runtime objects, arbitrary model context or protected continuation.

The Host must select `coding-product-v1` and `session-presentation-v1`. Old Hosts are refused with
`PRODUCT_CAPABILITY_UNSUPPORTED`; there is no legacy fallback. URI must resolve only to loopback,
have no userinfo/query/fragment and use the root path. Redirects are disabled. This native client is
for trusted local applications; it is not a browser grant holder.

Commands are sent once. An accepted receipt means admission, not successful completion. Query the
exact existing Run/outcome/Shell execution after a lost receipt; otherwise preserve unknown. Transport
failure after possible mutation is `CodingClientException.outcomeUnknown=true`, never an automatic
retry or a new idempotency key. Invalid/unavailable sensitive actions are refused. Exact interaction
Session/Run/request/revision comes from authoritative presentation, not displayed text.

SSE strictly decodes UTF-8 and bounded frames. The atomic reducer rejects foreign scope, gaps and
unknown critical content before any partial application. Duplicates are ignored. Disconnect, invalid
framing, overflow or restart requires a fresh snapshot; there is no durable presentation replay.
Bounds: initial snapshot 256 KiB, update 64 KiB, queue 512 KiB; preview text 4 KiB, serialized preview
16 KiB, preview queue 512 KiB. Preview is independently transient and never durable tool history.

Renew explicitly renegotiates an expired native client on the same project and closes old observations;
it never restores consent. Observation close cancels its HTTP request and subscription only. Client
close releases owned observations and context with a bounded best effort, leaving the Host and already
accepted execution alive. No `unload` or socket closure is interpreted as Run cancellation.

See [API contract](../haifa-coding-api/README.md), [Host](../haifa-coding-host/README.md), and
[external examples](../../examples/coding-product-sdk/README.md). Publish/consume only the feature
preview manifest's real JARs and flattened POMs; upstream class directories are not an external SDK test.
