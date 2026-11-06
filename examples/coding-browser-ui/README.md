# Independent browser consumer

Copy this directory outside the checkout, copy the SDK's packed preview into `vendor/`, then run
`npm ci` and `npm run build`. The only product dependency is the tarball, not a source alias, Java/CLI
implementation, Personal Assistant module or hand-written RPC. Node/Vite run only while building.

This minimal independent UI supports explicit owner pairing, session list/create and read-only
presentation through the public SDK. It intentionally offers no approval shortcut. Serve the built
`dist` as trusted assets through an explicitly configured compatible Coding Host, using `/browser/`.
Runtime requests stay on the page origin; there is no development proxy or BFF. Do not put a grant
or owner challenge in URLs, logs or browser storage.

Actual Host execution is gated by B2 review/acceptance. The B3 independent build/browser fixture
checks establish packed-package consumption and browser behavior only, not product acceptance.
A reload requires pairing and manual inspection of authority; an unknown create is never replayed.
