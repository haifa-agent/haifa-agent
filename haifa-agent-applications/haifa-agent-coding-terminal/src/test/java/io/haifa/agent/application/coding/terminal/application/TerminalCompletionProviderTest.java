package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalCompletionProviderTest {
    @Test
    void listsFrequentWorkflowCommandsBeforeSessionMaintenanceCommands() {
        assertThat(TerminalCompletionProvider.COMMANDS)
                .containsExactly(
                        "/model",
                        "/new",
                        "/resume",
                        "/compact",
                        "/session",
                        "/reload",
                        "/rename",
                        "/export",
                        "/archive",
                        "/delete",
                        "/commands",
                        "/help",
                        "/quit");
    }

    @Test
    void filtersEveryDotPrefixedPathSegmentFromAtCompletion() {
        var completion = new TerminalCompletionProvider(() -> List.of(
                ".hidden",
                ".config/settings.json",
                "src/.secret.java",
                "src/.private/value.txt",
                "src/",
                "src/App.java"));

        assertThat(completion.suggestions("@")).containsExactly("@src/", "@src/App.java");
    }
}
