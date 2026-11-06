# Browser Coding Client (B3 feature preview)

`@haifa-agent/coding-client@0.1.5-b3.0` is a framework-independent browser package. It contains the
shared Coding schema types, precompiled validation, atomic presentation reduction, current-page
request correlation and a same-origin fetch/SSE transport. Its runtime has no Node imports, React,
Provider SDK, engine, SQLite or runtime schema compiler/eval. Node 22/npm 10 and Python 3 (canonical generation freshness) are build tools only.
This is a private local preview; `npm pack` is supported, public publication is not part of this task.

The package connects to an explicitly enabled [Coding Host](../haifa-coding-host/README.md).
Package and synthetic renderer tests cover independent consumption. Real Host acceptance separately
exercises the packed SDK with Windows Chrome, Windows Edge, and Linux Chromium.

```bash
npm ci
npm test
npm run pack:preview
```

`artifacts/haifa-agent-coding-client-0.1.5-b3.0.tgz` contains a browser ESM bundle and declarations.
Consumers install that tarball; they do not alias this source tree. Build copies canonical generated
B1/B2 types and browser-safe shared invariants, and precompiles the one API JSON Schema with Ajv.
The pack command creates the output directory on a clean checkout. Generated build inputs and artifacts are ignored. The existing Node contract runner uses those same
invariants and fixtures. No second schema or Java lifecycle implementation is introduced.

```ts
import {CodingBrowserClient} from '@haifa-agent/coding-client';
const client = await CodingBrowserClient.pair(oneTimeChallengeFromTrustedOwner);
const sessions = await client.listSessions();
const view = await client.observe(selectedSessionId);
const unsubscribe = view.onChange(() => renderPlainText(view.snapshot));
// Use an available action from this exact confirmed view. Never infer target revisions from text.
await view.act(confirmedAction, {text: userInput});
unsubscribe();
view.close();
await client.close();
```

The browser must load trusted assets from the Host's own origin. `pair` first checks safe browser
capabilities, exchanges the owner-created challenge once and negotiates the same grant-bound client.
It never calls native v1, creates challenges, exposes the grant in public diagnostics or places proofs
in URLs/cookies/localStorage/sessionStorage. A page reload loses proof and request correlation; pair
again and inspect authoritative sessions/history. There is no persistent send queue or refresh token.
`origin` must match the page origin; the optional standard `fetch` adapter supports controlled browser
embedding and deterministic tests, not a Node transport or an origin proxy.

`execute` sends a command once and returns accepted/rejected/unknown. Accepted means admission,
not successful completion. Invalid/lost/stalled replies preserve unknown. Reusing a tracked request ID
is refused. `inspectRequest` can associate an exact already-known Run fact; it never infers that an
unknown command caused that state. Current-page tracking is bounded at 128 requests, retains unknown
associations and stores no command text or durable operation records.

`observe` starts from a validated immutable snapshot. Matching duplicates are ignored; gaps/resync
cause a fresh view, while unknown critical content/auth/protocol failures stop automatic recovery.
The parser handles arbitrary UTF-8 byte splits, CR/LF/CRLF, multiline data, comments and EOF, with
strict decoding and byte budgets. Network recovery has at most three retries (100/500/1500 ms)
between applied stream updates. It reuses the valid bound client and never retries mutations.
`view.refresh()` is explicit recovery.
Callbacks are sequential; there is no unbounded update queue. Snapshot/update bounds remain
256 KiB/64 KiB; response/request bounds are 1 MiB/256 KiB. RPC timeouts include body reads.

`foreground()` is single-flight: it immediately disables stale actions, validates renewal, then reads
fresh authoritative snapshots. Timer-based renewal cannot restore lost consent. Authorization expiry,
revocation and Host restart require pairing again. `preview(session, run, signal)` is an independently
cancelled, non-durable channel with exact scope, 4 KiB text and 16 KiB serialized frame limits.
Closing views/streams/clients never issues Run cancellation. Cancellation is an explicit exact action.

The [prepared Web](../haifa-coding-web/README.md) and [external UI example](../../examples/coding-browser-ui/README.md)
consume only the packed SDK. Their build does not establish real Host acceptance.
