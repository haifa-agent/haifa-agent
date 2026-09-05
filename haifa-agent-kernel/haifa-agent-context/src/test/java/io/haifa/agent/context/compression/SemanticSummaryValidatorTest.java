package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                List.of(new SemanticSummaryItem(
                        "g01", "Compile water treatment report", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem(
                        "c01", "Use ENV-ACCEPT-V3", List.of("m001"), SemanticConfidence.OBSERVED)),
                new SemanticProgress(
                        List.of(new SemanticSummaryItem(
                                "p01",
                                "Monitoring records extracted",
                                List.of("m002", "t001"),
                                SemanticConfidence.OBSERVED)),
                        List.of(new SemanticSummaryItem(
                                "p02", "Drafting final document", List.of("m002"), SemanticConfidence.OBSERVED)),
                        List.of()),
                List.of(new SemanticDecisionItem(
                        "d01",
                        "Adopted limit <= 8 mg/L",
                        "Based on official approval",
                        SemanticDecisionStatus.ACCEPTED,
                        List.of("m001"))),
                List.of(new SemanticSummaryItem(
                        "n01", "Submit to environmental board", List.of("m002"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem(
                        "ctx01", "Approval ref HuanShen [2025] 18", List.of("m001"), SemanticConfidence.OBSERVED)),
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
                        List.of(new SemanticSummaryItem(
                                "p01", "Done without evidence", List.of("m001"), SemanticConfidence.INFERRED)),
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
                        List.of(new SemanticSummaryItem(
                                "p01", "Claimed complete", List.of(), SemanticConfidence.OBSERVED)),
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
        SemanticSummaryItem carryItem = new SemanticSummaryItem(
                "b01", "Night noise exceeding limit", List.of("m001"), SemanticConfidence.OBSERVED);

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
                List.of(new SemanticSummaryItem(
                        "g01",
                        "Leaked key: sk-abcdef1234567890abcdef12345",
                        List.of("m001"),
                        SemanticConfidence.OBSERVED)),
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

    @Test
    void allowsHistoricalDurableRefsInCarryForward() {
        SemanticSummaryItem carryItem = new SemanticSummaryItem(
                "C-1", "Historical constraint", List.of("msg-historical-1"), SemanticConfidence.OBSERVED);
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(new SemanticSummaryItem("G-1", "Goal", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(carryItem),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatCode(() -> SemanticSummaryValidator.validate(
                        summary, projectedSource, List.of(carryItem), Set.of("msg-historical-1"), Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCategoryJumpingForConstraints() {
        SemanticSummaryItem carryItem = new SemanticSummaryItem(
                "C-1", "Must keep this constraint", List.of("m001"), SemanticConfidence.OBSERVED);
        // C-1 moved to nextSteps instead of constraints
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(new SemanticSummaryItem(
                        "C-1", "Now a next step", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(
                        summary, projectedSource, List.of(carryItem), Set.of(), Optional.empty()))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("constraint item 'C-1' cannot transition to another category");
    }

    @Test
    void allowsUnresolvedQuestionResolvedInDecisions() {
        SemanticSummaryItem questionItem = new SemanticSummaryItem(
                "Q-1", "Should we enable feature X?", List.of("m001"), SemanticConfidence.OBSERVED);
        // Q-1 is omitted from unresolvedQuestions, but resolved in decisions citing Q-1
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(),
                SemanticProgress.empty(),
                List.of(new SemanticDecisionItem(
                        "D-1",
                        "Enabled feature X",
                        "Resolved Q-1 following stakeholder review",
                        SemanticDecisionStatus.ACCEPTED,
                        List.of("m001"))),
                List.of(),
                List.of(),
                List.of());

        assertThatCode(() -> SemanticSummaryValidator.validate(
                        summary, projectedSource, List.of(questionItem), Set.of(), Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnresolvedQuestionDroppedWithoutResolution() {
        SemanticSummaryItem questionItem = new SemanticSummaryItem(
                "Q-1", "Should we enable feature X?", List.of("m001"), SemanticConfidence.OBSERVED);
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(new SemanticSummaryItem("G-1", "Goal", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> SemanticSummaryValidator.validate(
                        summary, projectedSource, List.of(questionItem), Set.of(), Optional.empty()))
                .isInstanceOf(SemanticSummaryValidationException.class)
                .hasMessageContaining("mandatory carry-forward item 'Q-1' was dropped without resolution");
    }

    @Test
    void strictFailClosedSchemaParsingRejectsMissingFields() {
        Map<String, Object> base = Map.of(
                "schemaVersion", "v1",
                "language", "en",
                "goals", List.of(),
                "constraints", List.of(),
                "progress", Map.of("completed", List.of(), "active", List.of(), "blocked", List.of()),
                "decisions", List.of(),
                "nextSteps", List.of(),
                "criticalContext", List.of(),
                "unresolvedQuestions", List.of());

        // Valid base parses cleanly
        assertThatCode(() -> SemanticConversationSummaryV1.fromMap(base)).doesNotThrowAnyException();

        // Missing goals
        var missingGoals = new java.util.HashMap<>(base);
        missingGoals.remove("goals");
        assertThatThrownBy(() -> SemanticConversationSummaryV1.fromMap(missingGoals))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required field 'goals'");

        // Missing progress
        var missingProgress = new java.util.HashMap<>(base);
        missingProgress.remove("progress");
        assertThatThrownBy(() -> SemanticConversationSummaryV1.fromMap(missingProgress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("progress must be a non-null object");

        // Missing unresolvedQuestions
        var missingQuestions = new java.util.HashMap<>(base);
        missingQuestions.remove("unresolvedQuestions");
        assertThatThrownBy(() -> SemanticConversationSummaryV1.fromMap(missingQuestions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required field 'unresolvedQuestions'");

        // Missing language
        var missingLang = new java.util.HashMap<>(base);
        missingLang.remove("language");
        assertThatThrownBy(() -> SemanticConversationSummaryV1.fromMap(missingLang))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
    }
}
