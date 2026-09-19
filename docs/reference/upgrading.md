# Upgrading

Haifa Agent is pre-1.0, so an upgrade should be treated as a code-and-data compatibility event rather than assuming semantic-versioning stability across every internal/public surface.

## Before upgrading

1. Read [CHANGELOG.md](../../CHANGELOG.md).
2. Review changes in the BOM/Starter version.
3. Check provider binding changes if your deployment uses non-default providers.
4. Back up durable SQLite/application data according to the product's operational procedure.
5. Run your own product tests against the new version.

## Frozen Runs and persisted data

Do not assume a newer binary can reinterpret every historical experimental payload.

The project intentionally removes some unused/superseded compatibility layers before 1.0 rather than accumulating readers for every internal prototype.

If a release note calls for rebuilding development data, do so instead of bypassing migration/codec checks.

## Provider changes

Provider capabilities and dialects are explicit frozen bindings. When upgrading, revalidate:

- endpoint;
- credential reference;
- provider/model ID;
- API style/dialect;
- capability set;
- reasoning/structured-output behavior.

## Application products

Coding Agent and Personal Assistant can have product-owned persistence in addition to common Runtime state. Follow product-specific migration/backup instructions when present.

## Verification

Build and test the exact revision you will deploy. Do not validate one commit and release another.
