package io.haifa.agent.application.project.product.coding.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingAgentPromptTest {
    @Test
    void loadsStableVersionedPromptWithoutCaseSpecificTutorials() {
        CodingAgentPrompt.Snapshot first = CodingAgentPrompt.current();
        CodingAgentPrompt.Snapshot second = CodingAgentPrompt.current();

        assertThat(second).isEqualTo(first);
        assertThat(first.version()).isEqualTo("1.6.0");
        assertThat(first.digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.identity()).startsWith("coding-agent-prompt@1.6.0#sha256:");
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
                        "failed build or test does not expose actionable evidence",
                        "do not repeat the same noisy command unchanged",
                        "stdout and stderr redirected to a temporary log",
                        "bounded exact-match context",
                        "preserve the test exit code",
                        "clean up the log",
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
                        "workspace_attach",
                        "Use host absolute paths for every file operation",
                        "aider/polyglot_",
                        "deduplication",
                        "rejected-record",
                        "accepted-record",
                        "performance contract",
                        "execution.output.read",
                        "execution_output_read",
                        "capturedOutputRef",
                        "Case 14",
                        "/Users/");
    }

    @Test
    void rendersWorkspaceAttachmentGuidanceOnlyWhenTheToolIsDisclosed() {
        CodingAgentPrompt.Snapshot withoutAttachment =
                CodingAgentPrompt.forDisclosedToolAliases(Set.of("file_read", "execution_run"));
        CodingAgentPrompt.Snapshot withAttachment =
                CodingAgentPrompt.forDisclosedToolAliases(Set.of("file_read", "workspace_attach"));

        assertThat(withoutAttachment.text())
                .contains("does not expose a workspace attachment tool")
                .doesNotContain("request workspace_attach");
        assertThat(withAttachment.text())
                .contains("request workspace_attach", "least permission needed")
                .doesNotContain("does not expose a workspace attachment tool");
        assertThat(withAttachment.identity()).isNotEqualTo(withoutAttachment.identity());
    }
}
