# Coding Web through same-origin Nginx

This opt-in preview serves one operator's server workspace over a single HTTPS origin. Build
Coding Web with its packed browser SDK and provide only its `dist` directory to Agent Host.
Keep Nginx and Host on the same trusted machine, with Host bound to `127.0.0.1`. The browser
uses the server workspace, and Host owns its Run, SQLite and approvals.

## Configure the isolated profile

1. Choose a canonical HTTPS origin without a path or trailing slash. Use the default HTTPS port
   without writing `:443`. Keep the existing `/browser/` prefix.
2. Generate 32 random bytes encoded as 64 lowercase hexadecimal characters. Store them in an
   owner-readable proof file. Provide the same value in a restricted Nginx include containing
   `proxy_set_header X-Haifa-Proxy-Proof "VALUE";`. Only the Host owner and Nginx configuration
   loader need access. Do not print the rendered config with `nginx -T`.
3. Start the external `HostExample` with trusted assets, a separate data directory, and a fixed
   loopback port. Set `HAIFA_CODING_EXTERNAL_ORIGIN` and `HAIFA_CODING_PROXY_PROOF_FILE` in the
   trusted launcher. Java embedders can configure the equivalent `BrowserIngress` in
   `BrowserOptions`.
4. Render [the Nginx template](nginx.conf.template) with the listen address, TLS files, public
   hostname and authority, loopback Host port, and proof include. Leave
   `GATEWAY_SESSION_INCLUDE` empty for the isolated transport profile. Validate it with
   `nginx -t -p <isolated-prefix> -c nginx.conf` before starting a dedicated instance.
5. Open `https://<public-authority>/browser/`. Issue a one-time challenge from the trusted Host
   owner console. Browser pairing and later Bearer grants remain owned by Host.

The proxy forwards the upstream authority and the browser's original Origin separately. A
header allowlist drops client-supplied forwarding metadata and gateway cookies. Only the
bundled assets and four browser endpoints are routed. Native RPC, events and health stay
local. Keep other local users and workspace execution away from the Host listener and proof
files using the operating system's process and network isolation. A loopback bind alone does
not isolate mutually untrusted local processes.

## Optional gateway session

Build Web with `VITE_HAIFA_CODING_GATEWAY_SESSION=true` and render
[the gateway include](gateway-session.conf.template). The existing gateway session verifier
must return 2xx for a valid session and 401 for a missing or expired session. Cookie issuance
belongs to that gateway. Use a Secure, HttpOnly, same-site cookie, and use `nginx -V` to verify
the auth request module. The include sends only the cookie to the verifier, and returns a
marked 401 for a failed session check before forwarding. The browser then asks the operator
to log in and pair again. Host authorization remains independent.

Gateway session expiry rejects new RPC and SSE requests. A request-time session check leaves
an already-open SSE connection established. Host grant revocation or expiry closes its
observations. Immediate stream termination on gateway logout requires a separate trusted
revocation integration.

## SSE and rollback

The event location uses HTTP/1.1 upstream, no response buffering, cache or gzip, and a
15-second upstream read inactivity timeout. Host flushes an idle heartbeat about every two
seconds. Request bodies remain bounded at 256 KiB, and upstream retries are disabled. Access
logs omit queries and credentials. Restrict logs and keep request debugging disabled.

To roll back, remove the public route or stop the dedicated ingress instance. End existing
browser access through Host owner grant revocation. Restore the prior Host launch without
the ingress settings if local browser access is needed. Do not take over the owner database
with a second process.
