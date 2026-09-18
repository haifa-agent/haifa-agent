# Phase D Incremental Tooling Decision

Date: 2026-08-09  
Status: Build Cache and mvnd not adopted; read-only scope advisor adopted

## Context

Phase A established measurements and fixed local concurrency. Phase B showed that the remaining
PA product time is dominated by SQLite transaction-heavy fixtures, recovery tests, WebFlux, and
multiple Spring restarts. Phase C removed repeated Unit execution from Integration and Artifact
gates. Phase D therefore evaluates incremental tooling only after those deterministic changes.

## Decision table

| Candidate | Local evidence | Benefit against current hotspot | Risk/complexity | Decision |
| --- | --- | --- | --- | --- |
| Read-only Reactor impact advisor | POM graph and Git diff tests PASS; Phase C diff correctly expands to full gate | Faster edit-loop command selection without weakening final gate | Low; advisory output only | Adopt |
| Maven Build Cache extension | No extension/config exists in `.mvn`; invalidation matrix not yet implemented | May reduce compilation/package work, not 60-114 s product tests | High: Java/POM/resource/generated/OpenAPI/Skill invalidation, nested repos, dirty worktrees, Windows locks | Do not enable; separate prototype required |
| `mvnd` | `mvnd` is not installed on this host | Saves startup seconds, not minute-level SQLite/WebFlux/Restart work | Medium: daemon/JDK/POM isolation and Windows handle lifecycle | Do not install or adopt |
| Worktree-local Maven repository | Maven already uses the repository Wrapper; no isolated cold download was forced | Prevents cross-worktree SNAPSHOT overwrite, but increases first-build time and disk use | Low-medium operational cost | Optional manual experiment only |
| CI test sharding | Stable Unit/Integration/Artifact classifications now exist | Removes repeated Unit CPU/wall time | Medium but bounded to workflows | Adopted in Phase C |

## Scope advisor

`build-support/scripts/suggest_maven_scope.py`:

- includes tracked, staged, unstaged, and untracked root-repository changes;
- discovers Maven modules and direct internal dependencies from POMs;
- reports changed modules, transitive compile dependencies, and transitive consumers;
- expands root POM/Wrapper/Workflow, API, Runtime/Core, SQLite, security, architecture/contract,
  and test-selection changes to the full final gate;
- excludes the independently versioned `docs/` and `test-config/` repositories from the root graph;
- emits JSON with `advisoryOnly=true` and never executes or skips validation.

The Phase C commit was used as a high-risk example. It reported the root POM and workflows,
identified Maven/CI/test-selection reasons, and recommended the full `ci-fast clean verify` gate.
Eight Python tests, including the build-metrics tests, pass.

## Worktree-local repository experiment boundary

No host path is committed or selected automatically. A developer who explicitly chooses isolation
may pass a controlled path outside the repository and user profile:

```powershell
.\mvnw.cmd "-Dmaven.repo.local=D:\m2-worktrees\<feature>" ...
```

Before wider use, an experiment must measure cold dependency download, warm repeat time, cleanup
policy, disk use, executable JAR hashes, and cross-worktree SNAPSHOT isolation. CI must not contain
a hard-coded host path.

## Reconsideration gates

Build Cache or mvnd may be reconsidered only if all of the following are true:

1. the same command has at least ten comparable cold/warm samples without host sleep or OOM;
2. median warm wall time improves at least 20%;
3. Java, test, POM, resource, generated source, OpenAPI, Skill/schema, and executable-JAR changes
   invalidate correctly in every case;
4. cold and cached outputs/tests agree, including required artifact hashes where reproducible;
5. Windows locks, JDK/POM changes, nested repositories, and dirty worktrees are covered;
6. the tool improves the actual slow path rather than only Maven startup.

Until then, the repository Wrapper and same-SHA gates remain authoritative. A missing optional tool
is recorded as not evaluated, never as a passing benchmark.
