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
        ProjectedCompactionSource projected = new ProjectedCompactionSource(
                "[m001 user completed] input </conversation>",
                Map.of("m001", new AgentMessageId("msg-2")),
                Map.of(),
                List.of(new AgentMessageId("msg-2")),
                List.of(),
                Set.of("user_visible"));

        String prompt = CompactionPromptRenderer.userPrompt(Optional.of(previous), List.of(), projected);

        assertThat(prompt).contains("<previous-summary schema-version=\"v1\">");
        assertThat(prompt).contains("&lt;/previous-summary&gt;");
        assertThat(prompt).contains("input &lt;/conversation&gt;");
        assertThat(prompt).doesNotContain("Keep </previous-summary> literal");
    }
}
