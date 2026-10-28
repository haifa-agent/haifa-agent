package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The answer encoding and the option markers the clarification selector renders. */
class ClarificationDetailsTest {

    @Test
    void keepsAPastedLineBreakInsideTheSingleCustomField() {
        List<ContentPart> parts =
                ClarificationDetails.answerParts(List.of(List.of("opt-0-0")), List.of("first line\r\nsecond line"));

        assertThat(parts).hasSize(1);
        String text = ((TextPart) parts.get(0)).text();
        assertThat(text).isEqualTo("selected=opt-0-0\ncustom=first line second line");
        assertThat(text.split("\n", -1)).hasSize(2);
    }

    @Test
    void marksSingleChoiceWithParenthesesAndMultiChoiceWithBrackets() {
        assertThat(ClarificationDetails.marker(false, true)).isEqualTo("(\u2713)");
        assertThat(ClarificationDetails.marker(false, false)).isEqualTo("( )");
        assertThat(ClarificationDetails.marker(true, true)).isEqualTo("[\u2713]");
        assertThat(ClarificationDetails.marker(true, false)).isEqualTo("[ ]");
    }

    @Test
    void titlesEachSelectorStepOfAMultiQuestionClarification() {
        ClarificationDetails details = twoQuestions();

        assertThat(details.selectorTitle(0)).isEqualTo("Clarification · Step 1 of 2 · Storage");
        assertThat(details.selectorTitle(1)).isEqualTo("Clarification · Step 2 of 2 · Location");
        assertThatThrownBy(() -> details.selectorTitle(2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsTheTranscriptCardToOneLinePerQuestionWithoutRepeatingItsOptions() {
        ApprovalDetails card = twoQuestions().toApprovalDetails();

        assertThat(card.content()).isEqualTo("Storage: Which storage?\nLocation: Where should it live?");
        assertThat(card.content()).doesNotContain("Postgres", "In the repository");
    }

    private static ClarificationDetails twoQuestions() {
        return new ClarificationDetails(
                "Delivery plan",
                List.of(
                        new ClarificationDetails.Question(
                                "Storage",
                                "Which storage?",
                                false,
                                true,
                                List.of(
                                        new ClarificationDetails.Option(
                                                "opt-0-0", "Postgres", Optional.of("Runs as a server")),
                                        new ClarificationDetails.Option("opt-0-1", "SQLite", Optional.empty()))),
                        new ClarificationDetails.Question(
                                "Location",
                                "Where should it live?",
                                false,
                                true,
                                List.of(new ClarificationDetails.Option(
                                        "opt-1-0", "In the repository", Optional.empty())))));
    }
}
