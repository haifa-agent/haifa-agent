# Coding Web (local preview)

This standalone TypeScript/Vite application consumes only the packed browser SDK. Its source does not
import the SDK source, CLI/private Java types, the Personal Assistant application or a second RPC
implementation. It builds trusted static assets for `/browser/`; no Node server, Vite proxy or BFF is
part of the product runtime. It does not enable Host browser mode automatically.

This Web application remains an opt-in preview. Package and synthetic renderer checks cover the
standalone build; real Host and platform acceptance are recorded separately.

Build the SDK tarball first, then install/build this application:

```bash
cd ../haifa-coding-browser-client
npm ci
npm run pack:preview
cd ../haifa-coding-web
npm ci
npm test
npm run build
```

After changing the SDK, repack and update the local file dependency/lock integrity with `npm install --save-exact ../haifa-coding-browser-client/artifacts/haifa-agent-coding-client-0.1.5-b3.0.tgz`.
Fresh builds use the committed lockfiles. The tarball itself is ignored, reproducibly generated, and
is not a public npm release. A compatible trusted Host owner can later supply `dist` through its
public `BrowserOptions`.

The prepared UI includes session search/list/create/open, ordinary input, Markdown replies and live
output, exact-target approval/structured clarification/cancellation, bounded independent
history, connection/recovery status and Host/workspace/model context. Approval requires explicit review;
an active session consent lease can handle eligible new approvals once per exact target. Revoke it in the session toolbar to return to per-call confirmation.
The selected row takes its Run status from the same confirmed observation as the conversation.
The transcript places transient tools before their Run's final reply and preserves durable history order.
The workspace panel uses the primary workspace from the SDK's structured workspace query. The panel
describes its source and file access in plain language. Opaque workspace labels fall back to a generic
local project folder label.
Unknown interaction types are unavailable. The Web UI has no editor/file tree/PTY or model-key form.
Assistant replies use Markdown with raw HTML disabled, HTTP(S) links only, and image alt text in place
of media. User input, tool output and approval review continue to render as literal text.

Drafts, caret, reading position and unfinished interaction input remain in page memory across session
switches. SDK owns confirmed authority/request correlation/recovery; UI owns rendering and selection
generations. Late responses cannot change the newly selected session or clear a newer draft. Older
history is displayed in a bounded four-page window. When the live boundary advances, the Web UI
clears older cached pages and resumes pagination from the new boundary. Tool previews are separately
bounded and explicitly non-durable.

Pairing takes a challenge from an explicit trusted owner operation and clears the field after exchange.
The SDK hides the grant in private page memory. Refresh/reopen requires pairing and inspection of
sessions/history, with no persistent send queue or silent token refresh. Returning to a visible tab
or restoring its network rechecks authority before enabling sensitive actions. An unknown outcome keeps the draft and requires user
inspection. Closing the page ends observation, while accepted Host execution continues.

The [external consumer](../../examples/coding-browser-ui/README.md) proves separate packed SDK use.
The independent browser preparation probe is documented in the testing scripts README and keeps its
scope distinct from real Host/Provider acceptance.
