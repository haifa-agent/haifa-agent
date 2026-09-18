# Phase C Same-SHA Gate Results

Date: 2026-08-09

## Contract

The final gate is a DAG over one immutable Git SHA:

```text
Unit (ci-fast)
  +-> Integration (ci-integration-only) -+
  +-> Release artifacts (release-artifacts) +-> Aggregate gate
```

Integration and Artifact jobs use fresh checkouts of the same SHA. They compile the sources they
need but do not rerun Unit/Contract/Architecture tests. A failed job is retried only in its own
scope; a new commit creates a new SHA and requires Unit again.

## Test and artifact inventory

| Gate | Includes | Excludes |
| --- | --- | --- |
| Unit | Surefire `*Test`, `*ContractTest`; Architecture tests; Spotless in `ci-fast` | Failsafe integration patterns |
| Integration | Failsafe `*IT`, `*LiveIT`, `*E2E` | All Surefire tests |
| Artifact | main/test compilation required by Maven, normal JAR/package, Source JAR, Javadoc JAR, CLI smoke | Surefire and Failsafe execution |

The legacy `ci-integration` and `release` profiles remain compatible for standalone callers.
They are not used by the new same-SHA workflows because they repeat Unit work.

## Local evidence

| Command scope | Result | Wall time | Executed tests |
| --- | --- | ---: | ---: |
| `:haifa-agent-common -Pci-fast verify` | PASS | 7.720 s | 8 Unit tests |
| `:haifa-agent-sandbox-local-native -am -Pci-integration-only ... verify` | PASS | 29.640 s | 1 selected Failsafe IT |
| `:haifa-agent-cli -am -Prelease-artifacts verify` | PASS | 181.998 s | 0 |
| CLI executable JAR `--help` | PASS | <1 s | n/a |

The artifact route produced the executable/shaded CLI JAR plus Source and Javadoc JARs. Profile
evaluation confirmed `ci-integration-only` resolves to `skipUnitTests=true, skipITs=false` and
`release-artifacts` resolves to `skipUnitTests=true, skipITs=true`.

## Workflow changes

- Feature PR retains the three-OS Unit gate and uses fixed `-T 4` instead of CPU-derived `-T 1C`.
- Dev runs three-OS Unit, Linux Integration, CLI Artifact, and the three platform-isolation IT jobs,
  then exposes one aggregate gate.
- Main release runs Unit once, then Integration and Artifact jobs in parallel, followed by the
  existing `Release gate` aggregate name for branch protection compatibility.
- Manual Dev workflow dispatch remains suite-planning only and does not create a misleading
  aggregate result for skipped build jobs.

All workflow files parse as YAML. No job downloads build output from another SHA, and no
Integration/Artifact job is allowed to satisfy the aggregate gate when Unit is skipped or failed.
