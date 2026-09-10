# Autonomous Delivery Capability Ladder Specification & Placeholder

> **Status**: Design Approved / Module Placeholder (v2 implementation deferred)
> **Architecture Reference**: [`docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md`](../../../../../../../../docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md)
> **External Evaluation Repo**: `haifa-agent-evals` (owns industry benchmarks like SWE-bench & polyglot)

---

## 1. Overview & Positioning

The Autonomous Delivery test suite within `haifa-agent-testing` is being repositioned from an over-engineered mini-benchmark platform to an internal **Capability Regression Probe**.

- **Goal**: Answer *"Did recent refactoring or model updates cause coding capability regression, and in which specific dimension?"*
- **Scope Boundary**: Industry benchmarks (SWE-bench Verified, aider-polyglot) and cross-agent comparative evaluations reside exclusively in `haifa-agent-evals`. The internal autonomous-delivery suite focuses on lightweight, high-sensitivity regression prevention.

---

## 2. Six-Level Capability Ladder (Main Axis)

Instead of monolithic tasks that conflate all cognitive burdens at once, tasks are structured into a 6-level ladder where each level adds exactly one primary difficulty:

1. **L1: Single-point Bugfix (5 cases, ~22%)**
   Explicit bug and failing test, 1 file modification. Assesses error comprehension, minimal edit, and verification loop. High baseline pass rate (>90%).
2. **L2: Local Multi-file Feature (5 cases, ~22%)**
   Clear requirement without pre-mapped path, modifies 2~3 local files (e.g., config parameter, DTO field, simple branch). Assesses reading + localized design + regression.
3. **L3: Diagnostic & Localization (4 cases, ~17%)**
   No file paths given; only user symptoms or error logs (e.g., "configuration ignored on Windows"). Assesses search and diagnostic discovery.
4. **L4: Cross-layer Contract Propagation (4 cases, ~17%)**
   Crosses API / Core / Adapter layers (e.g., tool parameter addition with caller propagation). Assesses architectural understanding and contract consistency.
5. **L5: Constraint-bounded Delivery (3 cases, ~13%)**
   Straightforward feature with strict engineering boundaries (e.g., "no new public types", "backward compatible", "no runtime modifications"). Assesses adherence to engineering discipline.
6. **L6: Open-ended Autonomous Delivery (2 cases, ~9%)**
   Real GitHub issue without guidance. Agent independently plans, implements, tests, reviews diff, and delivers.

### Difficulty Distribution
- **70% in L1 ~ L4** (regression detection baseline).
- **30% in L5 ~ L6** (high-capability probe).

---

## 3. Two Orthogonal Variant Types

1. **Error Recovery**: Initial state intentionally triggers compilation/test failures; verifies whether the agent recovers and resumes repair instead of failing closed or looping.
2. **Regression Protection**: Modifying feature A while maintaining unmentioned feature B; verifies protection of existing behaviors.

---

## 4. Three-Dimensional Diagnostic Labeling

Every case is tagged across three orthogonal axes (1 to 5):
- **Localization Complexity** (`1..5`)
- **Modification Span** (`1..5`)
- **Acceptance Verification Complexity** (`1..5`)

*Example: Case with `Loc: 4 / Span: 1 / Acc: 1` immediately diagnoses search/indexing regressions rather than multi-file edit failures.*

---

## 5. Planned Case Catalog (23 Cases)

The catalog is mirrored in code by `AutonomousDeliveryLadderCatalog` (module `src/main`); the
structural test `AutonomousDeliveryCapabilityLadderTest` keeps both in sync. Labels are
`Loc` (localization) / `Span` (modification span) / `Acc` (acceptance complexity), each 1..5.
Case task statements are seeded from public benchmark styles: Exercism-style
instructions+stub+unit-test tasks for L1/L2, symptom/stack-trace driven tasks for L3
(SWE-bench issue style), and open GitHub-issue tasks for L6. Authoring of per-case
workspaces (`prompt.txt`, `base-workspace/`, `acceptance.py`) is the next execution phase.

| ID | Title | Level | Loc/Span/Acc | Variant |
| --- | --- | --- | --- | --- |
| L1-01 | Fix off-by-one page window slicing | L1 | 1/1/1 | |
| L1-02 | Fix century leap-year rule | L1 | 1/1/1 | |
| L1-03 | Recover slugify edge cases from a red suite | L1 | 2/1/2 | Error Recovery |
| L1-04 | Fix integer division in conversion rate | L1 | 1/1/1 | |
| L1-05 | Make record search case-insensitive | L1 | 1/1/1 | |
| L2-01 | Thread verbose flag through config to CLI | L2 | 1/3/2 | |
| L2-02 | Add cancelled status to order state machine | L2 | 1/3/2 | |
| L2-03 | Propagate optional coupon field through service | L2 | 1/3/2 | |
| L2-04 | Add CSV export beside existing JSON export | L2 | 2/3/2 | |
| L2-05 | Add page size while preserving default ordering | L2 | 1/3/3 | Regression Protection |
| L3-01 | Diagnose settings ignored on Windows paths | L3 | 4/1/1 | |
| L3-02 | Recover report generator from stack trace | L3 | 4/1/2 | Error Recovery |
| L3-03 | Diagnose duplicate nightly aggregation entries | L3 | 4/1/2 | |
| L3-04 | Diagnose batch import hang on large input | L3 | 3/1/2 | |
| L4-01 | Propagate priority field across all layers | L4 | 1/4/3 | |
| L4-02 | Introduce new event type through pipeline | L4 | 2/4/3 | |
| L4-03 | Add validated tool parameter across boundaries | L4 | 1/4/3 | |
| L4-04 | Extend status API without breaking consumers | L4 | 2/4/4 | Regression Protection |
| L5-01 | Validate emails without new public types | L5 | 2/2/4 | |
| L5-02 | Speed up dedup using standard library only | L5 | 3/2/4 | |
| L5-03 | Rename calculation entry point backward compatibly | L5 | 2/2/4 | |
| L6-01 | Open issue: bulk inventory import from CSV | L6 | 3/5/4 | |
| L6-02 | Open issue: resumable batch export | L6 | 4/5/5 | |

Distribution check: L1..L4 carry 18 of 23 cases (78% ≥ 70% baseline); L5/L6 carry 5 (22% ≤ 30%).

---

## 6. Current State & Legacy Material

- **Legacy Cases (`cases/01` ~ `cases/17`)**: Preserved as historical synthetic test material for curation into the new ladder.
- **Implementation State**: Ladder case definitions (ids, titles, statements, three-dimensional labels, variant marks) are committed in `AutonomousDeliveryLadderCatalog`. Authoring of per-case workspaces, acceptance scripts, and the minimal single-task runner is scheduled for the next execution phase.
