package io.haifa.agent.personalassistant.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonalExecutionPlatformTest {
    @Test
    void boundsLongExecutionContentWithoutDroppingApprovalIdentityOrRiskSummary() {
        String summary =
                """
                Approve execution
                Mode: SCRIPT
                Language: powershell
                Purpose: generate a word frequency report
                Invocation digest: sha256:test
                Risks: HIGH, PROCESS_EXECUTION, NON_IDEMPOTENT, host access; approve once or reject""";
        String content = "Write-Output 'word'\n".repeat(400);

        String prompt = PersonalExecutionPlatform.boundedContent(summary, content);

        assertThat(prompt)
                .hasSizeLessThanOrEqualTo(2_048)
                .contains(
                        "Mode: SCRIPT",
                        "Language: powershell",
                        "Invocation digest: sha256:test",
                        "Risks: HIGH",
                        "Full content:",
                        "Content truncated",
                        "original length=");
    }

    @Test
    void preservesCompleteShortExecutionContent() {
        String summary = "Approve execution\nInvocation digest: sha256:test";
        String content = "Get-Date";

        assertThat(PersonalExecutionPlatform.boundedContent(summary, content))
                .isEqualTo(summary + "\nFull content:\n" + content);
    }
}
