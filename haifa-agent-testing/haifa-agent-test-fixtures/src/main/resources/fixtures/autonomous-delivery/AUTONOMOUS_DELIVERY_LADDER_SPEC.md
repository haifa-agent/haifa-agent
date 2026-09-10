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

## 5. Current State & Legacy Material

- **Legacy Cases (`cases/01` ~ `cases/26`)**: Preserved as historical synthetic test material for curation into the new ladder.
- **Implementation State**: Placeholder. Case authoring and runner restructuring are scheduled for subsequent execution phases.
