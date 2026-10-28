package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import java.util.List;
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
}
