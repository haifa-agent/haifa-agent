# Items HTTP protocol

Public API:

```js
fetchAllItems(baseUrl, options?) -> Promise<Array<Item>>
```

CLI:

```text
node src/cli.js BASE_URL
```

The CLI prints the final item array as JSON.

Protocol:

- request `GET BASE_URL/items` for the first page;
- subsequent pages use `GET BASE_URL/items?cursor=URL_ENCODED_CURSOR`;
- response JSON is `{ "items": [...], "nextCursor": string|null }`;
- preserve first-seen item order and keep only the first object for each
  non-empty string `id`;
- an invalid response shape is an error;
- HTTP 429 is retried up to two times per request, honoring `Retry-After`
  seconds before retrying; a missing header means zero delay;
- other non-2xx responses fail immediately;
- stop on `nextCursor: null`;
- detect repeated cursors and fail rather than loop;
- default maximum is 20 pages; exceeding it fails;
- `options.maxPages` may set a positive integer and `options.fetch` may inject
  a compatible fetch function for deterministic tests.

Errors produce a concise stderr message and non-zero CLI status. Node.js 22
standard APIs are sufficient.
