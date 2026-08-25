package io.haifa.agent.application.project.product.coding.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodingAgentPromptTest {
    @Test
    void loadsStableVersionedPromptWithoutCaseSpecificTutorials() {
        CodingAgentPrompt.Snapshot first = CodingAgentPrompt.current();
        CodingAgentPrompt.Snapshot second = CodingAgentPrompt.current();

        assertThat(second).isEqualTo(first);
        assertThat(first.version()).isEqualTo("1.2.0");
        assertThat(first.digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.identity()).startsWith("coding-agent-prompt@1.2.0#sha256:");
        assertThat(first.text())
                .contains(
                        "You are Haifa Coding Agent",
                        "complete task contract as the source of truth",
                        "Correct core logic alone is insufficient",
                        "compact, risk-proportionate checklist",
                        "original request and authoritative sources",
                        "track source, implementation/verification",
                        "inferred, missing, conflicting, or blocked",
                        "required observable behavior as correctness",
                        "semantic equivalence is insufficient",
                        "required dynamic values verbatim",
                        "Read applicable repository instructions",
                        "smallest complete change",
                        "authoritative tool results show a workspace change",
                        "validation attempt",
                        "deterministic change-review evidence",
                        "Do not run a DIFF-family command only to satisfy the completion protocol",
                        "public API/types",
                        "input/output grammar, encoding, boundaries, shape, serialization, and framing",
                        "invalid/error contracts",
                        "state, side effects, ordering, mutation scope, compatibility",
                        "explicit non-functional constraints",
                        "authoritative evidence provides expected and actual behavior",
                        "contract-conformance",
                        "selected test count",
                        "later runtime control updates",
                        "report it as blocked or not run",
                        "do not infer that the code is correct or claim the check passed",
                        "re-read the original request and authoritative contracts",
                        "every item against final implementation/evidence",
                        "unresolved items are not complete",
                        "result-verification skill",
                        "checks, skipped checks, and remaining risks")
                .doesNotContain(
                        "aider/polyglot_",
                        "deduplication",
                        "rejected-record",
                        "accepted-record",
                        "performance contract",
                        "Case 14",
                        "/Users/");
    }
}
