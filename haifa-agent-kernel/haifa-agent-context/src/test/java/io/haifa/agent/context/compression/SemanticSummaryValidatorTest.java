package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticSummaryValidatorTest {

    private final ProjectedCompactionSource projectedSource = new ProjectedCompactionSource(
            "[m001 user completed] Goal\n[m002 assistant completed] Done",
            Map.of("m001", new AgentMessageId("msg-1"), "m002", new AgentMessageId("msg-2")),
            Map.of("t001", new ToolCallId("tool-1")),
            List.of(new AgentMessageId("msg-1"), new AgentMessageId("msg-2")),
            List.of(new ToolCallId("tool-1")),
            Set.of("user_visible"));

    @Test
    void validatesConformingSemanticSummary() {
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Compile water treatment report", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem("c01", "Use ENV-ACCEPT-V3", List.of("m001"), SemanticConfidence.OBSERVED)),
                new SemanticProgress(
                        List.of(new SemanticSummaryItem("p01", "Monitoring records extracted", List.of("m002", "t001"), SemanticConfidence.OBSERVED)),
                        List.of(new SemanticSummaryItem("p02", "Drafting final document", List.of("m002"), SemanticConfidence.OBSERVED)),
                        List.of()),
                List.of(new SemanticDecisionItem("d01", "Adopted limit <= 8 mg/L", "Based on official approval", SemanticDecisionStatus.ACCEPTED, List.of("m001"))),
                List.of(new SemanticSummaryItem("n01", "Submit to environmental board", List.of("m002"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem("ctx01", "Approval ref HuanShen [2025] 18", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of());

        assertThatCode(() -> SemanticSummaryValidator.validate(summary, projectedSource, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownSourceRef() {
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Goal", List.of("m999"), SemanticConfidence.OBSERVED)),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(summary, projectedSource, List.of()))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("unknown sourceRef 'm999'");
    }

    @Test
    void rejectsCompletedItemWithInferredConfidence() {
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Goal", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(),
                new SemanticProgress(
                        List.of(new SemanticSummaryItem("p01", "Done without evidence", List.of("m001"), SemanticConfidence.INFERRED)),
                        List.of(),
                        List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(summary, projectedSource, List.of()))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("cannot have INFERRED confidence");
    }

    @Test
    void rejectsCompletedItemWithoutSourceRefs() {
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Goal", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(),
                new SemanticProgress(
                        List.of(new SemanticSummaryItem("p01", "Claimed complete", List.of(), SemanticConfidence.OBSERVED)),
                        List.of(),
                        List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(summary, projectedSource, List.of()))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("must cite supporting sourceRefs");
    }

    @Test
    void rejectsDroppedMandatoryCarryForward() {
        SemanticSummaryItem carryItem = new SemanticSummaryItem("b01", "Night noise exceeding limit", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Goal", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(summary, projectedSource, List.of(carryItem)))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("mandatory carry-forward item 'b01' was dropped");
    }

    @Test
    void rejectsForbiddenPatternInSummaryText() {
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Leaked key: sk-abcdef1234567890abcdef12345", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(summary, projectedSource, List.of()))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("forbidden or sensitive pattern detected");
    }
}
