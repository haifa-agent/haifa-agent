package io.haifa.agent.application.project.product.coding.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodingAgentPromptTest {
    @Test
    void loadsStableVersionedPromptWithoutCaseSpecificTutorials() {
        CodingAgentPrompt.Snapshot first = CodingAgentPrompt.current();
        CodingAgentPrompt.Snapshot second = CodingAgentPrompt.current();

        assertThat(second).isEqualTo(first);
        assertThat(first.version()).isEqualTo("1.0.0");
        assertThat(first.digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.identity()).startsWith("coding-agent-prompt@1.0.0#sha256:");
        assertThat(first.text())
                .contains(
                        "Read applicable repository instructions",
                        "smallest complete change",
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
