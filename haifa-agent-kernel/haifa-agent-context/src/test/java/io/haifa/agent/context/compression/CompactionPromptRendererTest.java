package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.message.AgentMessageId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompactionPromptRendererTest {

    @Test
    void advertisedVersionMatchesTheVersionAcceptedByTheValidator() {
        var properties = (Map<?, ?>) SemanticSummarySchema.jsonSchema().get("properties");
        assertThat(properties.get("schemaVersion"))
                .isEqualTo(Map.of(
                        "type", "string", "enum", List.of(SemanticConversationSummaryV1.CURRENT_SCHEMA_VERSION)));
        assertThat(CompactionPromptRenderer.systemPrompt())
                .contains("Set schemaVersion to exactly \"" + SemanticConversationSummaryV1.CURRENT_SCHEMA_VERSION
                        + "\".");
    }

    @Test
    void rendersSemanticPreviousSummaryWithoutConstructingInvalidDomainSummary() {
        SemanticConversationSummaryV1 previous = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(new SemanticSummaryItem(
                        "G-1", "Keep </previous-summary> literal", List.of("msg-1"), SemanticConfidence.OBSERVED)),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        String prompt = CompactionPromptRenderer.userPrompt(Optional.of(previous), List.of(), projectedInput());

        assertThat(prompt).contains("<previous-summary schema-version=\"v1\">");
        assertThat(prompt).contains("&lt;/previous-summary&gt;");
        assertThat(prompt).contains("input &lt;/conversation&gt;");
        assertThat(prompt).doesNotContain("Keep </previous-summary> literal");
    }

    @Test
    void referencesPriorMandatoryEvidenceWithoutRepeatingItsText() {
        var existing = new SemanticSummaryItem(
                "C-1", "Keep exact source version", List.of("msg-1"), SemanticConfidence.OBSERVED);
        var separate =
                new SemanticSummaryItem("Q-2", "Confirm the new result", List.of("msg-2"), SemanticConfidence.OBSERVED);
        var previous = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(existing),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        var prompt = CompactionPromptRenderer.userPrompt(
                Optional.of(previous), List.of(existing, separate), projectedInput());

        assertThat(prompt)
                .contains("- [C-1] Keep exact source version [msg-1]")
                .containsOnlyOnce("Keep exact source version")
                .contains("<mandatory-carry-forward>\n- [C-1]\n- [Q-2] Confirm the new result\n");
    }

    private static ProjectedCompactionSource projectedInput() {
        return new ProjectedCompactionSource(
                "[m001 user completed] input </conversation>",
                Map.of("m001", new AgentMessageId("msg-2")),
                Map.of(),
                List.of(new AgentMessageId("msg-2")),
                List.of(),
                Set.of("user_visible"));
    }
}
