# Autonomous Delivery Fixtures

This directory contains the immutable, synthetic inputs for the Coding Agent
autonomous-delivery evaluation catalog.

Each numbered case contains only:

- `prompt.txt`;
- `acceptance.py`;
- `base-workspace/`.

The fixtures were authored for the Haifa Agent generalized-capability tests.
They are project-internal synthetic test data, not copied third-party projects.
The project may use, modify, and redistribute them with this repository.

The following are deliberately excluded:

- Git metadata;
- build and language caches;
- historical final workspaces;
- SQLite, trace, recording, and report files;
- secrets and host-specific paths.

`catalog-v1.json` freezes the case versions and SHA-256 digests. Cases 03 and
04 use version `2.0.0` because their acceptance programs contain the reviewed
corrections described in the catalog.

`schemas/acceptance-result.schema.json` accepts exactly two result shapes:

- the capability-ladder result (`schemaVersion`, `caseId` matching `Lx-yy`,
  `caseVersion`, `status`, `passed`, `checks`, `failures`, optional `durationMillis`),
  emitted by the `haifa-agent-autonomous-delivery` cases;
- the frozen legacy result of cases `01`..`17` (`passed`, `checks`, `failures`
  and, for cases `01`..`10`, a `case` identifier). These historical cases are
  frozen and are not migrated to the ladder shape.