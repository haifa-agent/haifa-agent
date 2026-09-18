# Haifa Agent JSONL Transcript Projection

This module projects a deliberately small, safe subset of committed Runtime Outbox events to
UTF-8 JSON Lines files.

## Boundary

- SQLite remains the only runtime fact source. JSONL is optional, disposable, and never used for
  Runtime recovery.
- The projector consumes `RuntimeOutboxPublisher` messages and acknowledges them through the same
  `RuntimeUnitOfWork` only after the line has been forced to stable storage.
- Delivery is at least once. A crash after `force(true)` and before the SQLite acknowledgement can
  produce a duplicate line; readers deduplicate by event ID.
- Runtime Journal and Outbox now share the committed stable Event ID. JSONL preserves that ID for
  deduplication but never allocates, replaces, or interprets Run Feed cursors.
- Only registered event types and explicitly selected payload fields are written. Unknown event
  types or schema versions fail closed.
- Event timestamps are serialized as UTC ISO-8601 values at epoch-millisecond precision; readers
  remain able to parse older lines that contain finer precision.
- Credentials, tokens, reasoning, prompts, raw tool arguments/results, and provider responses are
  forbidden transcript content.
- Files are resolved below one controlled root and guarded by an operating-system file lock. This
  module does not expose Runtime repositories, transaction ownership, or a JSONL-only Runtime.
- The controlled root and every transcript, lock, and rotated segment use the same POSIX
  `0700/0600` or current-user-only Windows ACL baseline. Permission verification failures fail
  closed.

## Main API

- `JsonlTranscriptProjector` projects pending Outbox messages.
- `JsonlTranscriptReader` reads and deduplicates transcripts, diagnoses truncated tails, and can
  repair only a truncated final line.
- `SafeTranscriptMapperRegistry.defaults()` supplies the current event whitelist.

The initial durability policy forces every complete line before acknowledging its Outbox event.
The writer rotates at a bounded file size while holding a stable per-run lock file. It appends and
forces a recognizable `transcript.rotated` marker, atomically renames the closed segment, and then
continues in a new current file. The reader orders segments by their stable numeric suffix.

Project Application exposes this adapter only through `SQLITE_WITH_JSONL`. It attaches projection to
Runtime's post-commit listener, performs a final pending projection during ordered shutdown, and then
closes SQLite. `SQLITE` mode does not create transcript files, and no JSONL-only configuration is
accepted.

Task 02 does not turn JSONL into a client-event feed. Run Event range reads, retention and
replay-then-tail always read SQLite (or the in-memory journal in memory mode); deleting or failing a
transcript write cannot remove authoritative Interaction, Run Input, Checkpoint or Runtime Event state.
