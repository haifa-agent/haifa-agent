package io.haifa.agent.runtime.core.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.InteractionRequestId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClarificationAnswersTest {

    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    @Test
    void decodesSingleChoiceAnswerToItsLabel() {
        InteractionRequest request = request(spec(false, false));

        ClarificationAnswers answers = ClarificationAnswers.parse(List.of(part("opt-0-1", "")), request);

        assertThat(answers.declined()).isFalse();
        assertThat(answers.answers()).containsExactly(List.of("SQLite"));
        assertThat(answers.modelText(request))
                .isEqualTo("User has answered your questions: \"Which storage?\"=\"SQLite\"."
                        + " You can now continue with the user's answers in mind.");
        assertThat(answers.structuredData()).containsEntry("declined", false);
    }

    @Test
    void decodesMultiChoiceWithCustomAnswerInOptionOrder() {
        InteractionRequest request = request(spec(true, true));

        ClarificationAnswers answers =
                ClarificationAnswers.parse(List.of(part("opt-0-1,opt-0-0", "in-memory is fine")), request);

        assertThat(answers.answers()).containsExactly(List.of("Postgres", "SQLite", "in-memory is fine"));
    }

    @Test
    void dismissalNeverFabricatesAnswers() {
        InteractionRequest request = request(spec(false, true));

        ClarificationAnswers answers =
                ClarificationAnswers.declined(request.inputContracts().size());

        assertThat(answers.declined()).isTrue();
        assertThat(answers.answers()).containsExactly(List.of());
        assertThat(answers.modelText(request))
                .startsWith("User dismissed the questions without answering: \"Which storage?\".")
                .endsWith("Continue with the available information, or stop and explain what you still need.");
    }

    @Test
    void rejectsUnknownOption() {
        InteractionRequest request = request(spec(false, false));

        assertThatThrownBy(() -> ClarificationAnswers.parse(List.of(part("opt-9-9", "")), request))
                .hasMessageContaining("unknown option");
    }

    @Test
    void rejectsEmptyAnswerAndWrongPartCount() {
        InteractionRequest request = request(spec(true, false));

        assertThatThrownBy(() -> ClarificationAnswers.parse(List.of(part("", "")), request))
                .hasMessageContaining("is empty");
        assertThatThrownBy(() -> ClarificationAnswers.parse(List.of(), request))
                .hasMessageContaining("One answer is required per question");
    }

    @Test
    void rejectsFreeTextWhenTheQuestionDoesNotAllowIt() {
        InteractionRequest request = request(spec(false, false));

        assertThatThrownBy(() -> ClarificationAnswers.parse(List.of(part("", "typed")), request))
                .hasMessageContaining("does not accept a free-text answer");
    }

    @Test
    void summarisesAnswersWithoutTheWirePayload() {
        InteractionRequest request = request(spec(true, true));

        ClarificationAnswers answers =
                ClarificationAnswers.parse(List.of(part("opt-0-1", "in-memory is fine")), request);

        assertThat(answers.userSummary(request))
                .isEqualTo("Which storage?: SQLite, in-memory is fine")
                .doesNotContain("selected=")
                .doesNotContain("custom=");
    }

    @Test
    void summarisesADismissalWithoutFabricatingAnswers() {
        InteractionRequest request = request(spec(false, true));

        assertThat(ClarificationAnswers.declined(1).userSummary(request)).isEqualTo("Dismissed the questions");
    }

    private static TextPart part(String selectedIds, String customText) {
        return new TextPart("selected=" + selectedIds + "\ncustom=" + customText, ClarificationAnswers.PART_FORMAT);
    }

    private static ClarificationSpec spec(boolean multiple, boolean custom) {
        return ClarificationSpec.from(
                new ToolCallId("call-1"),
                Map.of(
                        "questions",
                        List.of(Map.of(
                                "header",
                                "Storage",
                                "question",
                                "Which storage?",
                                "multiple",
                                multiple,
                                "custom",
                                custom,
                                "options",
                                List.of(Map.of("label", "Postgres"), Map.of("label", "SQLite"))))));
    }

    private static InteractionRequest request(ClarificationSpec spec) {
        return new InteractionRequest(
                new InteractionRequestId("int-1"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("principal-1", "USER"),
                ClarificationSpec.INTERACTION_TYPE,
                spec.prompt(),
                false,
                spec.target(),
                NOW,
                Optional.empty(),
                InteractionExpirationOutcome.RETURN_TO_AGENT,
                Optional.empty(),
                spec.contracts(),
                Optional.of(spec.presentation()));
    }
}
