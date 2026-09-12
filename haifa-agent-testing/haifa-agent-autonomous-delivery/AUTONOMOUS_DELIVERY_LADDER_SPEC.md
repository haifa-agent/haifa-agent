# Autonomous Delivery Capability Ladder Specification & Placeholder

> **Status**: Implemented runner / externally versioned assets
> **Architecture Reference**: [`docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md`](../../docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md)
> **Implementation Contract**: [34-autonomous-delivery-ladder-case-authoring-prompt.md](../../docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-ladder-case-authoring-prompt.md)
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

1. **Error Recovery**: Initial state intentionally triggers compilation/test failures - an unfinished
   or plainly wrong previous fix attempt is already in the working tree. It verifies whether the agent
   recovers and resumes repair instead of failing closed or looping. No workspace Git history is
   required: the red initial state and the wrong attempt carry the variant.
2. **Regression Protection**: Modifying feature A while maintaining unmentioned feature B; verifies protection of existing behaviors.

---

## 4. Three-Dimensional Diagnostic Labeling

Every case is tagged across three orthogonal axes (1 to 5):
- **Localization Complexity** (`1..5`)
- **Modification Span** (`1..5`)
- **Acceptance Verification Complexity** (`1..5`)

*Example: Case with `Loc: 4 / Span: 1 / Acc: 1` immediately diagnoses search/indexing regressions rather than multi-file edit failures.*

---

## 5. Published Case Assets (23 Cases)

`haifa-agent-autonomous-delivery-assets` is the only source of authored task statements, titles,
labels, variants and workspaces. Its `assets-manifest.json` enumerates the 23 cases; each
`case.yaml` carries the per-case metadata. The main repository pins one exact asset commit and
the manifest SHA-256 in `assets.lock.json`, then validates the published case-tree digest before
running a case.

The intended distribution is L1×5, L2×5, L3×4, L4×4, L5×3 and L6×2. L1..L4 therefore carry 18
of 23 cases (78% ≥ 70% baseline); L5/L6 carry 5 (22% ≤ 30%).

The published `cases/` tree is generated from the asset repository's `authoring/` sources; the asset
version in use is `2026.09.11.2` (every case at `caseVersion` 2.0.0). Invariants the case set keeps,
regardless of the individual case:

- Hygiene checks guard only what a case promises: existing tests and protected files stay
  byte-identical and changed source files stay inside the case's editable scope and change budget.
  Adding tests, running the test tooling and leaving scratch files in the workspace are allowed.
- Every hidden check runs in its own interpreter with its own timeout, so one crashing or hanging
  check cannot zero the rest; check names keep the `functional.` / `boundary.` / `regression.` /
  `constraint.` prefixes so that a failure maps to a dimension.
- Performance-sensitive cases compare against an O(n) baseline measured on the same machine instead
  of a fixed wall-clock budget.
- L3 and L4 share one medium-size project with enforced layering, so localization labels reflect a
  real search space and cross-layer cases can assert the dependency direction.

---

## 6. Current State & Legacy Material

- **Legacy Cases (`cases/01` ~ `cases/17`)**: 已冻结为历史素材（决策：冷冻 Legacy），保留在 `haifa-agent-test-fixtures` 中可运行、可校验，不进入能力阶梯探针题集。
- **Implementation State**: All 23 cases (L1..L6) are authored in the external asset repository. The Maven module publishes the result contract, validates the immutable asset lock locally, and provides the explicit `fetch_assets.py` and offline `run_case.py` workflow. Each run validates against the shared `acceptance-result.schema.json` published by `haifa-agent-test-fixtures`. The current asset revision passes the NOP and oracle gates three times per case, plus an adversarial probe suite kept in the asset repository.
