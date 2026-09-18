# Research types

Choose exactly one type from the complete frozen Brief. This is prompt context, not product state or an output field.
Task and Synthesis use this same table. If ambiguous, or settled Tasks conflict, use `GENERAL_RESEARCH`.

| Type | Primary question | Necessary dimensions | Report addition |
| --- | --- | --- | --- |
| `TRUTHFULNESS_INVESTIGATION` | Is a promotion/disputed claim true? | claim origin; technical source; real capability; independent validation; business model; counterevidence/limits; itemized judgment | claim-evidence-counterevidence-inference-unknown-judgment matrix |
| `DECISION` | Which option/action? | options; constraints; costs/benefits; risks; triggers; failure plan | comparison, triggers, and failure plan |
| `POLICY_RISK` | What does a rule permit, require, or risk changing? | subject; region; effective dates; authoritative text; enforcement; exceptions; change risk | applicability and effective-date table |
| `FAILURE_POSTMORTEM` | Why did it fail? | timeline; competing accounts; consensus/conflict; direct/root causes; decision mistakes | timeline and causal analysis |
| `GENERAL_RESEARCH` | Explanatory/mixed question | decomposition; key facts; opposing views; limits; synthesis | no type-specific section required |

Dimensions guide coverage; they never impose a Task, source, query, or Tool-call count.
