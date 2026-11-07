# Independent browser consumer

Copy this directory outside the checkout, copy the SDK's packed preview into `vendor/`, then run
`npm ci` and `npm run build`. The only product dependency is the tarball, not a source alias, Java/CLI
implementation, Personal Assistant module or hand-written RPC. Node/Vite run only while building.

This minimal independent UI supports explicit owner pairing, session list/create and read-only
presentation through the public SDK. It intentionally offers no approval shortcut. Serve the built
`dist` as trusted assets through an explicitly configured compatible Coding Host, using `/browser/`.
Runtime requests stay on the page origin; there is no development proxy or BFF. Do not put a grant
or owner challenge in URLs, logs or browser storage.

The independent build and synthetic browser checks cover packed-package consumption. Real Host
acceptance serves this same build through an isolated, browser-enabled owner.
A reload requires pairing and manual inspection of authority; an unknown create is never replayed.
