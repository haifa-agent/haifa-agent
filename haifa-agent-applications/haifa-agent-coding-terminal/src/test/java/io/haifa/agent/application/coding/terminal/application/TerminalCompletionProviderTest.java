package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalCompletionProviderTest {
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
