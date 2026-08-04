package io.haifa.agent.application.project.product.coding.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodingAgentPromptTest {
    @Test
    void loadsStableVersionedPromptWithoutCaseSpecificTutorials() {
        CodingAgentPrompt.Snapshot first = CodingAgentPrompt.current();
        CodingAgentPrompt.Snapshot second = CodingAgentPrompt.current();

        assertThat(second).isEqualTo(first);
        assertThat(first.version()).isEqualTo("1.0.3");
        assertThat(first.digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.identity()).startsWith("coding-agent-prompt@1.0.3#sha256:");
        assertThat(first.text())
                .contains(
                        "You are Haifa Coding Agent",
                        "Read applicable repository instructions",
                        "smallest complete change",
                        "authoritative tool results show a workspace change",
                        "validation attempt",
                        "diff inspection",
                        "later runtime control updates",
                        "report it as blocked or not run",
                        "do not infer that the code is correct or claim the check passed",
                        "result-verification skill",
                        "checks, skipped checks, and remaining risks")
                .doesNotContain(
                        "deduplication",
                        "rejected-record",
                        "accepted-record",
                        "performance contract",
                        "Case 14",
                        "/Users/");
    }
}
