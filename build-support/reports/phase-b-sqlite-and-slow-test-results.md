# Phase B SQLite and Slow-Test Results

Date: 2026-08-09  
Host: Windows 10, Java 21.0.3, Maven Wrapper 3.9.15  
Branch: `feat-pa-mission-deep-research`

## Decision

Phase B adopts the low-complexity production changes only:

- remove the duplicate file-security pass from one SQLite connection path;
- reuse one FileStore/permission-view strategy per `SqliteConnectionFactory`;
- keep no-follow type checks, frozen directory identity, fail-closed errors, and owner-only
  permission write/read verification on every connection;
- avoid a file-generation/250ms result cache after concurrent SQLite writes demonstrated that
  the additional identity state machine was difficult to prove and maintain safely.

The deferred cache may be reconsidered only through a separate ADR and benchmark. It is not
required for correctness and is not enabled by a test-only production switch.

## Evidence

| Scope | Result | Wall time | Tests | Notes |
| --- | --- | ---: | ---: | --- |
| Permission and concurrent SQLite targeted regression | PASS | 28.861 s | 13 | T4; includes permission strategy, connection, conversation concurrency, and SDK fixture |
| MCP clean regression | PASS | 62.703 s | 177 | Removed stale incremental test classes before the product sample |
| SQLite module in the PA product sample | PASS | 69.000 s | included below | No permission, concurrency, recovery, or close failure |
| 32-module PA Server product sample | PASS | 344.453 s | 694 | Warm `test`, T4, no host sleep or OOM |

The PA product sample does not meet the old approximately 280-second reference or the 180-second
target. The current suite includes the new Mission/Deep Research restart coverage, so it is not a
like-for-like historical baseline. The result is retained as a failed performance objective, not
rewritten as a pass.

## Current slow tests

| Test class | Time | Interpretation |
| --- | ---: | --- |
| `PersonalAssistantRestartTest` | 113.720 s | Multiple real Spring restarts; one fixture appends 600 legacy events in separate transactions |
| `PersonalAssistantWebFluxTest` | 60.359 s | Just above the 60-second review threshold |
| `SqliteRuntimeRecoveryTest` | 33.601 s | Recovery contract rather than edit-loop feedback |
| `LocalWorkspaceMutationServiceTest` | 15.135 s | Independent filesystem test hotspot |
| `SqliteSdkPersonalFixtureTest` | 11.119 s | SDK/SQLite integration coverage |

Within `PersonalAssistantRestartTest`, the legacy admin aggregation case took 48.131 seconds and
the Deep Research restart case took 37.120 seconds. The condition-based waits returned normally;
no old fixed ten-second sleep window remains. Future low-risk work should first batch fixture setup
without weakening its pagination semantics and reduce redundant Spring context construction.

## Safety coverage

The regression set proves strategy detection occurs once per factory while file permissions are
still checked on every connection. It also covers directory replacement fail-closed behavior,
symbolic-link rejection where the host permits link creation, externally changed permission repair,
configuration-time database symlink rejection, WAL/foreign-key/busy-timeout behavior, concurrent
conversation/SDK access, Runtime recovery, and factory close behavior.

JSON measurements remain under ignored `local-tmp/maven-build-metrics/`; raw Maven logs are not
retained by default.
