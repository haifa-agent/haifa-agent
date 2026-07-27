package io.haifa.agent.application.coding.terminal.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TerminalRendererTest {
    @Test
    void keepsLongTranscriptInsideTheTerminalViewport() {
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState state = TerminalUiState.initial(120, 40);
        for (int index = 0; index < 30; index++) {
            state = reducer.reduce(
                    state,
                    new TerminalUiAction.UserMessageCommitted(
                            "message-" + index, "Message body " + index));
        }

        var view = new TerminalRenderer().render(state);
        String rendered = view.lines().stream()
                .map(value -> value.toString())
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(view.lines()).hasSizeLessThanOrEqualTo(40);
        assertThat(view.cursorRow()).isBetween(0, view.lines().size() - 1);
        assertThat(rendered)
                .startsWith("Haifa Coding Agent")
                .contains("… earlier transcript lines hidden", "Message body 29", "context:")
                .doesNotContain("Message body 0");
    }

    @ParameterizedTest
    @CsvSource({"80,24", "120,40", "180,50", "40,10"})
    void preservesPrototypeInformationOrderAtRequiredSizes(int columns, int rows) {
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(columns, rows),
                new TerminalUiAction.UserMessageCommitted("message-1", "Please inspect the project"));
        state = reducer.reduce(
                state,
                new TerminalUiAction.PendingChanged(
                        List.of(new PendingMessage("p-1", PendingMessage.Kind.FOLLOW_UP, "run tests", 0))));

        String rendered = new TerminalRenderer()
                .render(state).lines().stream()
                        .map(value -> value.toString())
                        .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(rendered.indexOf("Haifa Coding Agent")).isLessThan(rendered.indexOf("Loaded resources"));
        assertThat(rendered.indexOf("Loaded resources")).isLessThan(rendered.indexOf("You"));
        assertThat(rendered.indexOf("You")).isLessThan(rendered.indexOf("Pending messages"));
        assertThat(rendered.indexOf("Pending messages")).isLessThan(rendered.indexOf("* Idle"));
        assertThat(rendered.indexOf("* Idle")).isLessThan(rendered.indexOf("Message"));
        assertThat(rendered).doesNotContain("reasoning", "/model", "/login", "/tree", "/compact");
    }
}
