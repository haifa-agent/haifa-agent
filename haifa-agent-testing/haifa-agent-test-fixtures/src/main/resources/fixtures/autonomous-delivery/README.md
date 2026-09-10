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
- SQLite, trace, transcript, recording, and report files;
- secrets and host-specific paths.

`catalog-v1.json` freezes the case versions and SHA-256 digests. Cases 03 and
04 use version `2.0.0` because their acceptance programs contain the reviewed
corrections described in the catalog.
