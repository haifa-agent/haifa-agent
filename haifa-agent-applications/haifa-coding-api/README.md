# Coding product contract

`io.haifa:haifa-coding-api` contains JDK-only Coding product records, cross-field checks, an atomic
presentation reducer and an observation handle. It has no production dependencies. It belongs to the
Coding application, outside the base SDK BOM. Java compilation requires Java 21.

This is an experimental product contract. The [Host](../haifa-coding-host/README.md) and
[Java Client](../haifa-coding-client/README.md) implement safe embedded/native binding and optional
browser authorization. The [browser SDK](../haifa-coding-browser-client/README.md) and [Web](../haifa-coding-web/README.md)
now have independently tested feature preparation; real Host acceptance remains gated. TUI migration
remains a separate delivery. Existing native
v1 methods remain unchanged.

## One schema, two consumers

The authority is [contract/coding-presentation.schema.json](contract/coding-presentation.schema.json),
JSON Schema Draft 2020-12, schema version **1**. `contract/generate.py generate` produces
`io.haifa.coding.api.CodingProtocol` and [contract/generated.ts](contract/generated.ts). The schema is
also packaged as `io/haifa/coding/api/coding-presentation.schema.json`. Package versions do not select a
wire version. Changing incompatible required fields requires a new schema/capability version.

Base record fields are required on the wire; nullable base values use explicit `null`. Numbers are nonnegative
safe integers (at most 2^53 − 1), and timestamps are UTC epoch milliseconds. Unknown object fields,
envelope kinds, critical entry kinds or schema versions are rejected before application. Strings are
plain display text: consumers must escape them, never execute HTML, terminal escapes or scripts, and
treat unfamiliar content/input/action types as unavailable. Display text and hashes confer no authority.

The opt-in native capability `native-tool-details-v1` extends tool entries with `toolDetails`.
The Host omits the extension for browsers and clients that did not negotiate it. The detail
includes bounded redacted output, a safe result reference, process state, signed exit code and
timestamps only when observed. It does not grant tool or result access.

`native-terminal-activity-v1` adds `CodingClient.terminalSession` and `CodingClient.activity`.
The session read returns safe model and task display facts. Native snapshots may include an activity
watermark. An activity page is scoped to a Session and a Run, with an exclusive sequence cursor and
at most 16 source events. The cursor advances over source events that have no terminal projection.
This capability is available to native and embedded clients. Browser responses omit the watermark.

Run the same fixtures in both languages:

```bash
./build-support/scripts/invoke-haifa-maven.sh --layer L1 -- \
  -pl :haifa-coding-api -am -Dtest=CodingContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
cd haifa-agent-applications/haifa-coding-api/contract
npm ci
npm run contract:check
```

The private Node tooling validates schema, generated-type freshness and cross-field invariants.
Browser-safe cross-field rules live in `contract/invariants.ts` and are reused by the precompiled
browser SDK build; the fixture runner remains Node-only. It is not itself a transport/npm SDK. Maven tests use the same fixtures and schema, round-trip
the Java records and check public signatures. `CodingApiArchitectureTest` checks JDK-only bytecode
dependencies in the architecture profile. Java constructors check local shape; untrusted JSON must
first pass schema validation and then `CodingProtocolChecks`, as the fixture entry points demonstrate.

## Snapshot and update semantics

`Snapshot.scope` fixes `viewId`, `hostIncarnation`, `clientId`, `sessionId`, `schemaVersion` and
`source` (`native` or `browser`). Host labels native/embedded observations `native` and grant-bound browser observations `browser`. A view revision
is a process-local publication number, distinct from Session/model/Run/Interaction CAS revisions,
history sequence, Run event cursor, output sequence and local UI generation.

The product engine registers Session invalidation before reading authority, attaches known-Run event
replay/tail and output observation, rereads authority, then serially installs snapshot N. Subsequent
`Update` envelopes carry `baseRevision`, `revision` and one whole replacement snapshot. Queuing starts
before the subscribe RPC returns, covering the RPC-to-SSE attach interval. This guarantees ordering at
one presentation publisher; it does not promise an atomic transaction across independent source reads.
The Host owner's maintenance remains responsible for reconciliation; reading a presentation/history
does not dispatch follow-up work.

`CodingPresentationReducer.apply` validates before applying the entire envelope. Matching-scope old
updates are ignored; a missing next revision, foreign scope, invalid replacement or explicit `Resync`
requires a fresh subscription. Consumers must not apply a subset of entries or infer domain CAS from
view revision. Disconnect, Host restart or an expired view also requires a new snapshot. There is no
durable view replay and no authorization conveyed by possession of a view ID.

Entries are `message`, `tool`, `interaction` or `notice`. Message identity retains source message ID,
Session sequence, exact source Run ID and occurrence time. Failure notices also retain safe code and
diagnostic ID when available. Tool identity hashes a length-prefixed Run/ToolCall pair; the explicit
`toolCallId` remains available. Tool-to-interaction links use trusted target references, never display
text. Bounded tool summaries are incomplete review data. Pending interactions carry exact
Session/Run/Interaction/targetRevision and allowed actions. Missing or incomplete critical review data
disables approval; session consent eligibility reuses the existing product approval rules.

Live output is transient, scoped by Run/model-call/generation/physical-attempt and independently
deduplicated by output sequence. Split control sequences and credential fragments are normalized and
redacted before publication. Unsupported streaming redactors suppress live text explicitly. Final
history replaces live output in the same envelope only after an assistant message with the exact
source Run is visible. During an unproven Run handoff the view remains `refreshing`, preserving the
old live lane with sensitive actions disabled. Equal text, current array position and the latest Run
are never used as correlation evidence. Provider payloads, credentials, protected continuation and
full model context do not enter this contract.

## Bounds and incomplete data

| Surface | Enforced bound / behavior |
| --- | --- |
| Serialized snapshot | 256 KiB UTF-8; omit oldest complete history groups, then conservatively disable critical actions if necessary |
| Serialized update | 64 KiB UTF-8; oversized replacements produce `Resync` and close observation |
| Outgoing view queue | 512 KiB UTF-8; overflow produces `Resync` |
| Source work queue | 64 serial tasks; overflow produces `Resync` |
| Snapshot window | 32 entries; prioritize pending interaction and up to 8 recent tool facts |
| Independent history | At most 100 entries and 256 KiB UTF-8; retain complete source-sequence groups and a strict before-sequence cursor |
| Displayed message/live text | Engine caps at 8,000 Unicode code points; schema permits entry text up to 16,384 |
| Approval review content | 16,384 code points plus bounded environment/technical facts; incomplete targets disable sensitive actions |
| Source output chunk | 32 KiB UTF-8; larger chunks require resync |
| Preview | Existing separately negotiated transient channel, 4,096 UTF-8 bytes of text, 16 KiB serialized frame, exact Session/Run/ToolCall/channel |

`history.hasEarlier`, `beforeSequence`, `complete`, entry `truncated`, live `truncated`, review
`targetComplete`/`contentTruncated` and preview `outputTruncated`/`previewDropped` describe different
losses. A reduced history window does not imply the Run or tool failed. Fetch older history separately;
never append it to the active view without a local merge policy. Preview has no replay cursor and is
never durable history. The whole replacement bound can intentionally resync a large view rather than
silently drop facts or raise budgets.

## Commands, receipts and compatibility ownership

`Command` describes a finite product operation and safe inputs. `clientRequestId` correlates a local
request, JSON-RPC `id` correlates one transport exchange, and `idempotencyKey` identifies an existing
domain deduplication boundary. These identities are independent. `Receipt.disposition=accepted`
means admission, not terminal success. `unknown` remains unknown if existing queries cannot prove an
outcome. Never resend an uncertain side effect under a new key. There is no generic operation store. B2 adds finite `Query`/`QueryResult`, `CommandRequest`/
`CommandResult`, pairing/grant DTOs and `CodingClient`; Host dispatches these through engine mapping.

| Product capability | Contract / existing supported binding | Outcome and migration responsibility |
| --- | --- | --- |
| Session list/search/create/open/reconcile | `Session`, `Snapshot`; `Command.create`; native `session/list`, `session/create`, `session/open`, `session/reconcile` | B2 product result mapping belongs to engine; legacy `session/open` keeps deferred history |
| Submit/new turn | `Command.submit`; native `run/start` | Existing dispatch key/Run binding; query exact Run after lost receipt |
| Steer/follow-up/restore queue | `Command.steer`, `followUp`, `restore`; native `run/steer`, `run/followUp`, `session/queuedMessages`, `session/restoreQueued` | Retain exact Run, queue identity and Session CAS; legacy remote bridge remains in CLI |
| Run fact/outcome and observation | `Run`, `Live`, `Snapshot`, `CodingPresentationSubscription`; native `run/get`, `run/outcome`, `events/*`, `output/*` | B2 converts safe product results; keep event/output cursors separate |
| History recent/older | `HistoryPage`, `Entry`, `History`; `presentation/history` | Stable source Run/sequence, independent before cursor |
| Approval/clarification/consent | `Interaction`, `Input`, `Question`, `Review`, `Consent`; `Command.respond`, `prepareApproval`, `allowForSession`, `resetConsent` | Exact target revision; Runtime receipt remains authority; no automatic grant on reconnect |
| Cancel | `Command.cancel`; native `run/cancel` | Exact Run required; stale target cannot cancel successor; remote Session-only overload stays unsupported |
| Model catalog/selection | `Session` model context; `Command.selectModel`; native `model/list`, `session/selectModel` | Existing model preference CAS/idempotency; no active-Run hot swap |
| Rename/archive/delete/compact/export | Corresponding `Command` operations; native `session/*` | Entity CAS where supported; no invented durable outcome query for compact/export |
| Manual Shell | `Command.shellPlan`, `shellStart`, `shellGet`, `shellCancel`, `shellDiscard`; native `shell/plan`, `start`, `get`, `list`, `cancel`, `discard`, legacy `execute` | Existing plan token/execution ID and bounded Host receipts; query instead of re-executing |
| Workspace/resources/configuration | Safe `Session.context`; `Command.workspaceRevoke`, `resourcesReload`; native `workspace/*`, `resources/*`, `host/configuration` | Host-authorized scope; no credential or raw configuration DTO |
| Authentication/account/connection | Existing authentication facade and native safe status/logout/local-management command | B2 preserves safe binding; secret entry/login remains local, remote secret mutation unsupported |
| Tool preview | `Preview`; existing optional `preview-v1` | Separate transient stream, explicit truncation/drop; no durable recovery |

The Web first phase consumes Session creation/open, input, bounded presentation/history, exact approval,
clarification, consent and cancellation. The other supported native/TUI behaviors remain required
compatibility bindings even when absent from that UI. Product mapping and the lightweight
embedded binding belong to the engine; the old Runtime-bearing remote compatibility bridge stays in
CLI. The new safe Client methods/result mappings are available for the separately authorized TUI migration;
this contract module does not itself migrate the TUI.

## Lifetimes and browser boundary

Grant, client context, consent lease and view have separate lifetimes. Current native contexts are
Host-process-local, expire on idle timeout, and are not browser credentials. Presentation SSE heartbeats
do not renew client context or consent. Explicit client renewal can replace an expired empty consent
lease, never restore a previous grant. Closing a view releases observation only; client expiry/revocation
terminates its views and consent, while already accepted Runs/tools retain their owner.

Browser pairing/grant validation, unique grant-to-client binding and accept/revoke serialization are
implemented in Host and are not enabled by `source=browser` in a DTO. A new browser client must refuse
an old Host lacking browser authorization rather than fall back to native RPC. Same origin and client ID
are not authentication. Reconnect with a valid grant reuses its bound client and requires a fresh view; it never restores lost consent;
expired/revoked grants require pairing again. Unknown accepted-command outcomes still require an
existing exact query or remain unknown. This module opens no browser ingress.

## Trusted local authentication

Terminal assembly can supply the JDK-only `CodingAuthenticationClient` independently of
`CodingClient`. Browser and device login instructions, progress, cancellation and credential input
stay within that trusted process. The CLI/Host assembly supplies the implementation and owns the
credential store. The contract retains the existing Java class names for native classpath
compatibility. The browser wire exposes no interactive login or credential input.
