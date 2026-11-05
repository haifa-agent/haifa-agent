# External Coding SDK examples

Copy each example to a directory outside this checkout. Each POM has exactly one product dependency:
Host or Java Client. Java 21 is required. No CLI, source directory, upstream `target/classes`, fat JAR or
new Tool SPI is used. A trusted YAML file selects the existing models, credential references, tools,
Skills/MCP and approval policy; `HostOptions` supplies owner paths and the application's display identity.

Publish a clean feature worktree through the existing tool:

```powershell
./scripts/publish-local-sdk.ps1 -Mode coding-product-preview -LocalRepo /absolute/path/to/preview-repository
```

The equivalent Unix entry is `sh scripts/publish-local-sdk.sh coding-product-preview --local-repo ...`.
Use an isolated repository, separate from the base SDK cache. Read the resulting
`io/haifa/haifa-coding-host/<version>/haifa-coding-product-preview-manifest.json`; require `state=complete`
and use its exact `version` and source SHA. Build the copied samples with
`mvn -Dmaven.repo.local=<repository> -Dcoding.version=<version> package`, then resolve the normal Maven
runtime classpath. Run the application's JAR with those published dependency JARs, never with upstream
class directories. This is a local preview, not a stable or public release.

`HostExample` takes workspace, data directory and trusted configuration paths. Its optional asset root
and grant duration enable the loopback browser boundary. Assets are trusted application code and must
not expose owner/workspace data. Default browser mode is off. Only the creator closes the Host; closing
an embedded or remote Client releases that client's observations/consent and leaves accepted execution
owned by Host. A database ownership conflict fails without taking over another process.

The owner's `pair` command creates a short-lived, one-time challenge only after explicit local input.
It is shown on a trusted console; `--owner-ipc` instead uses a private launcher's stdin/stdout control pipe.
Never record that pipe, put proofs in URLs, persist them or expose it as a public HTTP administration API.
`revoke <clientId>` is likewise an owner operation. Ordinary SDK diagnostics redact proof values.

`ClientExample` takes endpoint, `create|await|inspect` and a structured result-file path. Await/inspect
also take exact Session and Run IDs. It submits once, observes/queries authority and never retries an
unknown outcome. Pending approval normally requires operator input. `--approve-fixture` is an explicit
opt-in for the isolated synthetic acceptance fixture; it responds only to an available exact approval
from the confirmed product interaction. It is not an automatic approval policy.
