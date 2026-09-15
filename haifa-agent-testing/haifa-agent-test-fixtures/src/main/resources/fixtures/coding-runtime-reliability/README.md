# Coding Runtime Reliability Replay Fixture

`replay-v1.json` is a synthetic, manually reviewable contract fixture derived from aggregate failure shapes. It does
not contain a real prompt, transcript, command output, provider response, credential, host path, repository identity,
or user identifier.

The fixture freezes the minimum observations and expected future outcomes for the phased implementation in
`docs/prompts/19-coding-agent-git-and-long-running-task-reliability-improvement-prompt.md`. Phase-specific tests may
consume the same cases, but must not rewrite historical expectations in place. Add a new schema/version when the
contract changes.

Schema `1.1.0` removes the cases and baseline counts that depended on the deleted Git/GitHub command micro-DSL
(declared operation hint mismatch, trusted Git read risk, and composite-command classification rejection). The
remaining nine cases only assert generic execution, workspace, model/context, change-set, and unknown-outcome facts.

Commands are synthetic and intentionally limited to the smallest shape required by a deterministic test. Digests use
fixed non-production values so reports and tests never need access to the external runtime database.
